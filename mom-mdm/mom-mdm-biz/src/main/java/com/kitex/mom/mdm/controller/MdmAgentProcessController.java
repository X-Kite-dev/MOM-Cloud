package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.agent.service.MdmAgentActualScopeAccess;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.entity.MdmAgentProcessReceiptEntity;
import com.kitex.mom.mdm.entity.MdmProcessParameterStandardEntity;
import com.kitex.mom.mdm.mapper.MdmAgentProcessReceiptMapper;
import com.kitex.mom.mdm.mapper.MdmProcessParameterStandardMapper;
import com.kitex.mom.mdm.service.MdmProcessParameterStandardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * AI只能转交经过人工审议的标准草稿，正式发布仍执行MDM原有权限和编制/发布分岗。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmAgentProcess")
public class MdmAgentProcessController {

    private final MdmProcessParameterStandardService standards;

    private final MdmProcessParameterStandardMapper standardMapper;

    private final MdmAgentProcessReceiptMapper receipts;

    private final ObjectMapper mapper;

    private final MdmAgentActualScopeAccess actualScope;

    @PostMapping("/detail")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_view')")
    public R<Map<String, Object>> detail(@Valid @RequestBody DetailQuery command) throws Exception {
        scope(command.factoryCode());
        MdmProcessParameterStandardDTO.Detail value = standards.detail(command.id());
        return R.ok(Map.of("standard", value.getStandard(), "items", value.getItems(), "logs", value.getLogs(),
                "draftFingerprint", draftFingerprint(value)));
    }

    @PostMapping("/create-draft")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_edit') and @pms.hasPermission('agent:process:handoff')")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> create(@Valid @RequestBody DraftCommand command) throws Exception {
        actualScope.require(command.factoryCode(), "write");
        scope(command.factoryCode());
        String payload = hash(mapper.writeValueAsString(command));
        MdmProcessParameterStandardEntity source = lock(command.sourceStandardId());
        MdmAgentProcessReceiptEntity replay = replay(command.requestId(), payload);
        if (replay != null)
            return R.ok(mapper.readValue(replay.getReceiptSnapshot(), Map.class));
        if (!"RELEASED".equals(source.getStatus()) || !Objects.equals(source.getContentFingerprint(),
                command.expectedSourceFingerprint()) || command.draft().getId() != null ||
                !Objects.equals(source.getProcessRouteId(), command.draft().getProcessRouteId()) ||
                !Objects.equals(source.getStandardNo(), command.draft().getStandardNo()) || command.draft()
                .getVersionNo() <= source.getVersionNo()) {
            throw new CheckedException("来源标准已漂移或建议超出同路线新版本边界，须重新调查/审议");
        }
        // NODE_V2 的路线节点是标准身份的一部分；AI转交不能借旧协议省略节点后再由MDM猜测。
        String sourceSchema = source.getContentSchemaVersion() == null
                ? MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1 : source.getContentSchemaVersion();
        String draftSchema = command.draft().getContentSchemaVersion() == null
                ? MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1 : command.draft().getContentSchemaVersion();
        if (!Objects.equals(sourceSchema, draftSchema)
                || (MdmProcessParameterStandardDTO.NODE_V2.equals(sourceSchema)
                && command.draft().getItems().stream().anyMatch(item -> item.getRouteNodeCode() == null
                || item.getRouteNodeCode().isBlank()))) {
            throw new CheckedException("工程草稿必须保留来源内容协议和全部真实路线节点，不能将NODE_V2降级为主工序标准");
        }
        Long id = standards.createDraft(command.draft());
        Map<String, Object> receipt = Map.of("factoryCode", command.factoryCode(), "proposalNo", command.proposalNo(),
                "requestId", command.requestId(), "sourceStandardId", String.valueOf(source.getId()), "standardId",
                String.valueOf(id), "status", "DRAFT", "draftFingerprint", draftFingerprint(standards.detail(id)));
        save(command.factoryCode(), command.proposalNo(), command.requestId(), payload, "CREATE_DRAFT", source.getId(), id, receipt);
        return R.ok(receipt);
    }

    @PostMapping("/publish")
    @PreAuthorize("@pms.hasPermission('mdm_processParameterStandard_approve') and @pms.hasPermission('agent:process:publish')")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> publish(@Valid @RequestBody PublishCommand command) throws Exception {
        actualScope.require(command.factoryCode(), "write");
        scope(command.factoryCode());
        String payload = hash(mapper.writeValueAsString(command));
        MdmAgentProcessReceiptEntity replay = replay(command.requestId(), payload);
        if (replay != null)
            return R.ok(mapper.readValue(replay.getReceiptSnapshot(), Map.class));
        MdmAgentProcessReceiptEntity handoff = receipts.selectOne(new LambdaQueryWrapper<MdmAgentProcessReceiptEntity>()
                .eq(MdmAgentProcessReceiptEntity::getFactoryCode, command.factoryCode())
                .eq(MdmAgentProcessReceiptEntity::getProposalNo, command.proposalNo())
                .eq(MdmAgentProcessReceiptEntity::getTargetStandardId, command.standardId())
                .eq(MdmAgentProcessReceiptEntity::getOperationType, "CREATE_DRAFT").last("LIMIT 1"));
        if (handoff == null)
            throw new CheckedException("正式标准缺少本提案的转交回执");
        // 全部转交统一先锁来源、再锁草稿，避免来源/目标交叉锁顺序造成循环等待。
        MdmProcessParameterStandardEntity source = lock(handoff.getSourceStandardId());
        MdmProcessParameterStandardEntity target = lock(command.standardId());
        if (!"RELEASED".equals(source.getStatus()) || !"DRAFT".equals(target.getStatus()) ||
                !Objects.equals(source.getProcessRouteId(), target.getProcessRouteId()) ||
                !Objects.equals(source.getStandardNo(), target.getStandardNo()) ||
                (MdmProcessParameterStandardDTO.NODE_V2.equals(source.getContentSchemaVersion())
                        && !MdmProcessParameterStandardDTO.NODE_V2.equals(target.getContentSchemaVersion())) ||
                !draftFingerprint(standards.detail(target.getId())).equals(command.expectedDraftFingerprint())) {
            throw new CheckedException("工艺草稿不是本提案转交版本或审议后参数已变化，请重新审议");
        }
        standards.changeStatus(new MdmProcessParameterStandardDTO.ActionCommand().setId(target.getId())
                .setAction("RELEASE").setReason(command.reason()));
        MdmProcessParameterStandardEntity published = standards.detail(target.getId()).getStandard();
        Map<String, Object> receipt = Map.of("factoryCode", command.factoryCode(), "proposalNo", command.proposalNo(),
                "requestId", command.requestId(), "standardId", String.valueOf(published.getId()), "versionNo",
                published.getVersionNo(), "contentFingerprint", published.getContentFingerprint(), "status", published.getStatus());
        save(command.factoryCode(), command.proposalNo(), command.requestId(), payload, "RELEASE",
                handoff.getSourceStandardId(), target.getId(), receipt);
        return R.ok(receipt);
    }

    private void scope(String factory) {
        actualScope.require(factory, "read");
        if (SecurityUtils.getUser() == null || TenantContextHolder.getTenantId() == null ||
                TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) ||
                !Objects.equals(factory, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("Agent工艺转交须实名并选择实际租户/工厂");
        }
    }

    private MdmProcessParameterStandardEntity lock(Long id) {
        MdmProcessParameterStandardEntity value = standardMapper.selectOne(new
                LambdaQueryWrapper<MdmProcessParameterStandardEntity>().eq(MdmProcessParameterStandardEntity::getId, id)
                .eq(MdmProcessParameterStandardEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("FOR UPDATE"));
        if (value == null)
            throw new CheckedException("当前工厂不存在工艺标准");
        return value;
    }

    private MdmAgentProcessReceiptEntity replay(String request, String payload) {
        MdmAgentProcessReceiptEntity value = receipts.selectOne(new LambdaQueryWrapper<MdmAgentProcessReceiptEntity>()
                .eq(MdmAgentProcessReceiptEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MdmAgentProcessReceiptEntity::getRequestId, request).last("LIMIT 1"));
        if (value != null && !payload.equals(value.getPayloadHash()))
            throw new CheckedException("永久工艺请求号已被不同命令占用");
        return value;
    }

    private void save(String factory, String proposal, String request, String payload, String operation, Long source,
                      Long target, Map<String, Object> receipt) throws Exception {
        receipts.insert(new MdmAgentProcessReceiptEntity().setFactoryCode(factory).setProposalNo(proposal)
                .setRequestId(request).setPayloadHash(payload).setOperationType(operation).setSourceStandardId(source)
                .setTargetStandardId(target).setReceiptSnapshot(mapper.writeValueAsString(receipt)));
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 包含标准元信息和全部参数；外部不能只核验一部分参数后发布已被改写的草稿。
     */
    private String draftFingerprint(MdmProcessParameterStandardDTO.Detail detail) throws Exception {
        Map<String, Object> frozen = new java.util.TreeMap<>();
        frozen.put("standard", detail.getStandard());
        frozen.put("items", detail.getItems());
        return hash(mapper.writeValueAsString(frozen));
    }

    public record DetailQuery(@NotBlank String factoryCode, @NotNull Long id) {
    }

    public record DraftCommand(@NotBlank String factoryCode, @NotBlank String proposalNo, @NotBlank String requestId,
                               @NotNull Long sourceStandardId, @NotBlank String expectedSourceFingerprint,
                               @Valid @NotNull
                               MdmProcessParameterStandardDTO.SaveCommand draft) {
    }

    public record PublishCommand(@NotBlank String factoryCode, @NotBlank String proposalNo, @NotBlank String requestId,
                                 @NotNull Long standardId, @NotBlank String expectedDraftFingerprint,
                                 @NotBlank String reason) {
    }
}
