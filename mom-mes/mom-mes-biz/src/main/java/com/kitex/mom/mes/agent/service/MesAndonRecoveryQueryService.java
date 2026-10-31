package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.agent.dto.MesAndonRecoveryDTO;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.entity.MesAndonProductionRelease;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.service.MesAndonRecordService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.service.impl.MesAndonProductionReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 冻结单个安灯事件的处理、验证和复产控制事实。
 *
 * <p>所有数据均来自MES原业务表和现有复产门，Agent不自行推断闭环或放行。当前派工事实
 * 变化后，既有复产批准会由原服务判为失效，本查询同步反映该结果。</p>
 */
@Service
@RequiredArgsConstructor
public class MesAndonRecoveryQueryService {

    public static final String TOOL_CODE = "MES_ANDON_RECOVERY_QUERY@1";
    private static final int MAX_OPERATIONS = 100;

    private final MesActualFactoryAccessService factoryAccess;
    private final MesAndonProductionReleaseService productionReleaseService;
    private final MesAndonRecordService recordService;
    private final ObjectMapper objectMapper;

    /**
     * 用原安灯创建请求号核对Agent转交是否真正提交；查不到时不制造伪回执。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesAndonRecoveryDTO.Receipt receipt(MesAndonRecoveryDTO.ReceiptQuery query) {
        require(query != null && StringUtils.hasText(query.getFactoryCode())
                && StringUtils.hasText(query.getRequestId()), "安灯转交回执查询缺少工厂或请求号");
        String factory = factoryAccess.require("read");
        require(factory.equals(query.getFactoryCode()), "安灯转交回执工厂与当前实际工厂不一致");
        require(query.getRequestId().startsWith("PDEV-"), "仅接受参数异常Agent的安灯转交请求号");
        require(SecurityUtils.getUser() != null && SecurityUtils.getUser().getTenantId() != null,
                "安灯回执查询缺少实名租户上下文");
        List<MesAndonRecordEntity> matches = recordService.list(new LambdaQueryWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getTenantId, SecurityUtils.getUser().getTenantId())
                .eq(MesAndonRecordEntity::getFactoryCode, factory)
                .eq(MesAndonRecordEntity::getRequestId, query.getRequestId())
                .last("LIMIT 2"));
        require(matches.size() <= 1, "安灯转交请求号存在重复记录，需先核查原业务数据");
        if (matches.isEmpty()) return new MesAndonRecoveryDTO.Receipt().setFound(false);
        MesAndonRecoveryDTO.Query byId = new MesAndonRecoveryDTO.Query()
                .setFactoryCode(factory).setRecordId(String.valueOf(matches.get(0).getId()));
        return new MesAndonRecoveryDTO.Receipt().setFound(true).setEvidence(query(byId));
    }

    /**
     * 在同一只读快照中采集安灯及复产事实；坏主键、跨工厂或歧义范围直接失败。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesAndonRecoveryDTO.Evidence query(MesAndonRecoveryDTO.Query query) {
        require(query != null && StringUtils.hasText(query.getFactoryCode()), "安灯调查必须指定实际工厂");
        String factory = factoryAccess.require("read");
        require(factory.equals(query.getFactoryCode()), "安灯调查工厂与当前实际工厂不一致");
        Long recordId = positiveId(query.getRecordId());

        Map<String, Object> releaseView = productionReleaseService.view(recordId, 1);
        MesAndonRecordEntity event = (MesAndonRecordEntity) releaseView.get("event");
        require(event != null && Objects.equals(event.getId(), recordId)
                        && factory.equals(event.getFactoryCode()),
                "MES未返回当前工厂唯一安灯事件");
        List<MesAndonOperationEntity> allOperations = recordService.listOperations(recordId);
        boolean operationsTruncated = allOperations.size() > MAX_OPERATIONS;
        int from = Math.max(0, allOperations.size() - MAX_OPERATIONS);
        List<MesAndonRecoveryDTO.OperationSnapshot> operations = allOperations.subList(from, allOperations.size())
                .stream().map(this::operation).toList();
        MesAndonProductionRelease latest = releaseView.get("latest") instanceof MesAndonProductionRelease value
                ? value : null;
        boolean terminal = terminal(event);
        boolean releaseRequired = Boolean.TRUE.equals(event.getProductionReleaseRequired());
        boolean releaseAllowed = Boolean.TRUE.equals(releaseView.get("allowed"));
        boolean recoveryVerified = terminal && (!releaseRequired || releaseAllowed);
        List<String> gaps = gaps(event, latest, operationsTruncated, releaseView);
        List<String> refs = references(event, allOperations, latest);

        MesAndonRecoveryDTO.Evidence evidence = new MesAndonRecoveryDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("以MES安灯记录主键冻结异常对象；事件闭环必须由发起人验证PASS，影响生产时还必须取得与当前派工事实指纹一致的独立复产批准。模型只能解释证据、提出根因假设和责任建议。")
                .setTenantId(event.getTenantId()).setFactoryCode(factory)
                .setRecordId(String.valueOf(event.getId()))
                .setRequestId(event.getRequestId()).setCreateTime(event.getCreateTime())
                .setRecordCode(event.getCode()).setTypeCode(event.getTypeCode()).setTypeName(event.getTypeName())
                .setLevel(event.getLevel()).setState(event.getState()).setExceptionDescription(event.getExDesc())
                .setImpactProduction(event.getImpactProduction()).setProductionPaused(event.getProductionPaused())
                .setProductionPauseResult(event.getProductionPauseResult()).setTaskCode(event.getTaskCode())
                .setProduceTaskCode(event.getProduceTaskCode()).setArrangeCode(event.getArrangeCode())
                .setDeviceCode(event.getDeviceCode()).setWorksiteCode(event.getWorksiteCode())
                .setHandlerName(event.getHandlerName()).setReceiverName(event.getReceiverName())
                .setRootCause(event.getRootCause()).setCorrectiveAction(event.getCorrectiveAction())
                .setHandleResult(event.getResult()).setResponseDeadline(event.getResponseDeadline())
                .setHandleDeadline(event.getHandleDeadline()).setEscalationCount(event.getEscalationCount())
                .setReceiveTime(event.getReceiveTime()).setHandleTime(event.getHandleTime())
                .setCloseTime(event.getCloseTime()).setVerificationResult(event.getVerificationResult())
                .setVerificationRemark(event.getVerificationRemark()).setVerifierName(event.getVerifierName())
                .setVerificationTime(event.getVerificationTime()).setCancelReason(event.getCancelReason())
                .setEventTerminal(terminal).setProductionReleaseRequired(releaseRequired)
                .setProductionReleaseAllowed(releaseAllowed).setRecoveryVerified(recoveryVerified)
                .setArrangeStatus(Objects.toString(releaseView.get("arrangeStatus"), null))
                .setLatestRelease(release(latest)).setOperations(operations)
                .setOperationsTruncated(operationsTruncated).setObservedAt(LocalDateTime.now())
                .setDataGaps(gaps)
                .setLimitations(List.of("根因、措施和处理结果以MES实名处理记录为准，模型建议不是正式结论",
                        "复产批准只解除本安灯事件的逻辑阻断，不启动设备、派工或替代其他开工门",
                        "PLAN重排、TPM维修和质量处置如被触发，仍须在对应领域状态机独立办理"))
                .setEvidenceRefs(refs);
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private List<String> gaps(MesAndonRecordEntity event, MesAndonProductionRelease latest,
                              boolean operationsTruncated, Map<String, Object> releaseView) {
        List<String> gaps = new ArrayList<>();
        if (!StringUtils.hasText(event.getCode()) || !StringUtils.hasText(event.getTypeCode())
                || !StringUtils.hasText(event.getState()))
            gaps.add("安灯缺少流程编码、异常类型或状态");
        if (!Set.of("un_receive", "un_handle", "up_un_receive", "up_un_handle", "handled", "closed", "cancel")
                .contains(event.getState()))
            gaps.add("安灯状态无法识别");
        if (Boolean.TRUE.equals(event.getImpactProduction())
                && (!StringUtils.hasText(event.getTaskCode()) || !StringUtils.hasText(event.getArrangeCode())))
            gaps.add("影响生产的安灯缺少工单或派工归属");
        if (Boolean.TRUE.equals(event.getImpactProduction()) && !Boolean.TRUE.equals(event.getProductionPaused()))
            gaps.add("影响生产的安灯尚无成功暂停生产回执");
        if (List.of("handled", "closed").contains(event.getState())
                && (!StringUtils.hasText(event.getRootCause()) || !StringUtils.hasText(event.getCorrectiveAction())))
            gaps.add("已处理安灯缺少根本原因或纠正措施");
        if ("closed".equals(event.getState())
                && (!"PASS".equals(event.getVerificationResult()) || event.getVerificationTime() == null))
            gaps.add("安灯关闭状态缺少发起人验证通过回执");
        if (Boolean.TRUE.equals(event.getProductionReleaseRequired())
                && terminal(event) && !Boolean.TRUE.equals(releaseView.get("allowed")))
            gaps.add(latest == null ? "安灯已终结但尚未申请独立复产审批" : "最新复产申请/审批尚未形成当前派工有效放行");
        if (operationsTruncated) gaps.add("安灯操作履历超过100条，本次仅返回最近100条");
        return gaps;
    }

    private List<String> references(MesAndonRecordEntity event, List<MesAndonOperationEntity> operations,
                                    MesAndonProductionRelease latest) {
        List<String> refs = new ArrayList<>();
        refs.add("MES:mes_andon_record:" + event.getId());
        operations.forEach(row -> refs.add("MES:mes_andon_operation:" + row.getId()));
        if (latest != null) refs.add("MES:mes_andon_production_release:" + latest.getId());
        if (StringUtils.hasText(event.getTaskCode())) refs.add("MES:mes_task:" + event.getTaskCode());
        if (StringUtils.hasText(event.getArrangeCode())) refs.add("MES:mes_task_arrange:" + event.getArrangeCode());
        return List.copyOf(refs);
    }

    private MesAndonRecoveryDTO.OperationSnapshot operation(MesAndonOperationEntity row) {
        return new MesAndonRecoveryDTO.OperationSnapshot().setOperationId(String.valueOf(row.getId()))
                .setOperationType(row.getOperationType()).setBeforeState(row.getBeforeState())
                .setAfterState(row.getAfterState()).setContent(row.getContent())
                .setOperatorCode(row.getOperatorCode()).setOperatorName(row.getOperatorName())
                .setOperationTime(row.getOperationTime()).setPauseOutcome(row.getPauseOutcome());
    }

    private MesAndonRecoveryDTO.ReleaseSnapshot release(MesAndonProductionRelease row) {
        return row == null ? null : new MesAndonRecoveryDTO.ReleaseSnapshot()
                .setReleaseId(String.valueOf(row.getId())).setAction(row.getAction())
                .setOperatorName(row.getOperatorName()).setReason(row.getReason())
                .setOperationTime(row.getOperationTime()).setEvidenceFingerprint(row.getEvidenceFingerprint());
    }

    private boolean terminal(MesAndonRecordEntity event) {
        return "closed".equals(event.getState()) && "PASS".equals(event.getVerificationResult())
                && event.getVerificationTime() != null
                || "cancel".equals(event.getState()) && StringUtils.hasText(event.getCancelReason());
    }

    private String fingerprint(MesAndonRecoveryDTO.Evidence evidence) {
        try {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("factoryCode", evidence.getFactoryCode());
            facts.put("recordId", evidence.getRecordId());
            facts.put("recordCode", evidence.getRecordCode());
            facts.put("state", evidence.getState());
            facts.put("eventTerminal", evidence.getEventTerminal());
            facts.put("productionPaused", evidence.getProductionPaused());
            facts.put("productionReleaseAllowed", evidence.getProductionReleaseAllowed());
            facts.put("arrangeStatus", evidence.getArrangeStatus());
            facts.put("rootCause", evidence.getRootCause());
            facts.put("correctiveAction", evidence.getCorrectiveAction());
            facts.put("verificationResult", evidence.getVerificationResult());
            facts.put("latestRelease", evidence.getLatestRelease());
            facts.put("operations", evidence.getOperations());
            facts.put("dataGaps", evidence.getDataGaps());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("安灯事实无法生成完整指纹", failure);
        }
    }

    private Long positiveId(String value) {
        try {
            require(value != null && value.matches("[1-9][0-9]{0,18}"), "安灯记录主键无效");
            long id = Long.parseLong(value);
            require(id > 0, "安灯记录主键无效");
            return id;
        } catch (NumberFormatException invalid) {
            throw new CheckedException("安灯记录主键无效");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
