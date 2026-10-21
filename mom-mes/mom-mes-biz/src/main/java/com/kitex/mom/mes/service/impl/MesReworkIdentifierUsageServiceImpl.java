package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesReworkIdentifierCandidateDTO;
import com.kitex.mom.mes.dto.MesReworkMaterialCommand;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesIdentifierBindingEntity;
import com.kitex.mom.mes.entity.MesIdentifierBindingGroupEntity;
import com.kitex.mom.mes.entity.MesReworkIdentifierUsageEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesIdentifierBindingGroupMapper;
import com.kitex.mom.mes.mapper.MesIdentifierBindingMapper;
import com.kitex.mom.mes.mapper.MesReworkIdentifierUsageMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesReworkIdentifierUsageService;
import com.kitex.mom.mes.support.MesReworkIdentifierScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 正式返工的原动态实例范围、现场识别和永久使用账实现。
 *
 * <p>返工只引用原实例，不继承原区段的BIND/USE/RELEASE动作，也不改变原实例状态。
 * ACTIVE实例必须扫原外码，RELEASED实例必须扫内部规范码；二者都按批准时版本核验。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReworkIdentifierUsageServiceImpl
        extends ServiceImpl<MesReworkIdentifierUsageMapper, MesReworkIdentifierUsageEntity>
        implements MesReworkIdentifierUsageService {

    private static final String RESOLUTION_VERSION = "MES_REWORK_IDENTIFIER_RESOLUTION_V1";
    private static final String USAGE_VERSION = "MES_REWORK_IDENTIFIER_USAGE_V1";
    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskMapper taskMapper;
    private final MesIdentifierBindingMapper bindingMapper;
    private final MesIdentifierBindingGroupMapper groupMapper;

    @Override
    @Transactional(readOnly = true)
    public ScopeContext context(MesTaskEntity reworkTask) {
        String factory = factoryAccess.require("read");
        assertRework(reworkTask, factory);
        var source = sourceTask(reworkTask, factory);
        List<MesReworkIdentifierCandidateDTO> candidates = candidates(reworkTask, source);
        return new ScopeContext(requiresSourceBinding(reworkTask, source), candidates,
                reworkTask.getReworkIdentifierScopeSnapshot(), reworkTask.getReworkIdentifierScopeFingerprint());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public FrozenScope freeze(MesTaskEntity reworkTask, MesReworkMaterialCommand.IdentifierScope requested) {
        String factory = factoryAccess.require("write");
        assertRework(reworkTask, factory);
        require(requested != null && Set.of(MesReworkIdentifierScope.NONE,
                MesReworkIdentifierScope.SOURCE_BINDINGS).contains(requested.getMode()),
                "首轮返工执行方案须明确原动态实例范围：NONE或SOURCE_BINDINGS");
        var source = sourceTask(reworkTask, factory);
        boolean required = requiresSourceBinding(reworkTask, source);
        List<MesReworkIdentifierCandidateDTO> candidates = candidates(reworkTask, source);
        MesReworkIdentifierScope scope = identity(reworkTask, source).setMode(requested.getMode());
        if (MesReworkIdentifierScope.NONE.equals(requested.getMode())) {
            require(!required, "原实际工序属于动态换码区段，返工不能选择NONE绕过原实例");
            require(requested.getSourceBindingIds() == null || requested.getSourceBindingIds().isEmpty(),
                    "NONE范围不能夹带原实例ID");
        } else {
            require(required, "原实际工序不属于动态换码区段，不能虚构原实例范围");
            require(requested.getSourceBindingIds() != null && !requested.getSourceBindingIds().isEmpty()
                    && requested.getSourceBindingIds().size() <= 500, "须选择1至500个原动态实例");
            int expected;
            try {
                expected = reworkTask.getNoticeNum().stripTrailingZeros().intValueExact();
            } catch (Exception exception) {
                throw new CheckedException("动态实例返工数量必须为1至500的整数，且与选中实例逐件对应");
            }
            require(expected > 0 && expected <= 500 && requested.getSourceBindingIds().size() == expected,
                    "原动态实例数量必须与返工工单数量逐件一致，当前应选择" + expected + "个");
            Map<Long, MesReworkIdentifierCandidateDTO> byId = new HashMap<>();
            for (var candidate : candidates) {
                require(byId.put(candidate.getBindingId(), candidate) == null, "原动态实例候选重复，请先治理原节点账");
            }
            Set<Long> selected = new HashSet<>();
            for (String rawId : requested.getSourceBindingIds()) {
                Long id = positiveId(rawId, "原动态实例ID");
                require(selected.add(id), "原动态实例不能重复选择：" + rawId);
            }
            List<Long> ordered = selected.stream().sorted().toList();
            lockCandidateIdentifiers(factory, ordered.stream().map(byId::get).toList());
            for (Long id : ordered) {
                var candidate = byId.get(id);
                require(candidate != null, "原动态实例不属于原实际工序的唯一有效报工：" + id);
                MesIdentifierBindingEntity locked = bindingMapper.selectOne(bindingScope(factory)
                        .eq(MesIdentifierBindingEntity::getId, id).last("FOR UPDATE"));
                require(locked != null && sameBinding(candidate, locked), "原动态实例状态或版本已变化，请刷新后重提");
            }
            // 加锁后重新采集原操作/报告，防止批准依据在候选读取与保存之间被受控冲销。
            Map<Long, MesReworkIdentifierCandidateDTO> lockedCandidates = new HashMap<>();
            for (var candidate : candidates(reworkTask, source)) lockedCandidates.put(candidate.getBindingId(), candidate);
            for (Long id : ordered) {
                var candidate = byId.get(id);
                require(sameCandidate(candidate, lockedCandidates.get(id)),
                        "原动态实例的来源报告、操作或永久区段已变化，请刷新后重提");
                scope.getItems().add(item(candidate));
            }
        }
        String snapshot = MesReworkIdentifierScope.encode(scope);
        return new FrozenScope(snapshot, MesReworkIdentifierScope.fingerprint(snapshot));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void validateApproved(MesTaskEntity reworkTask) {
        validateFrozen(reworkTask, reworkTask == null ? null : reworkTask.getReworkIdentifierScopeSnapshot(),
                reworkTask == null ? null : reworkTask.getReworkIdentifierScopeFingerprint());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void validateFrozen(MesTaskEntity reworkTask, String snapshot, String fingerprint) {
        String factory = factoryAccess.require("write");
        assertRework(reworkTask, factory);
        require(StrUtil.isNotBlank(snapshot) && StrUtil.isNotBlank(fingerprint)
                        && Objects.equals(fingerprint, MesReworkIdentifierScope.fingerprint(snapshot)),
                "返工原动态实例范围未批准或指纹不一致");
        var source = sourceTask(reworkTask, factory);
        MesReworkIdentifierScope scope = MesReworkIdentifierScope.parse(snapshot);
        scope.validateIdentity(tenant(), factory, reworkTask.getId(), reworkTask.getCode(), source.getId(),
                source.getCode(), reworkTask.getSourceProduceTaskCode(), reworkTask.getReworkRouteNodeCode());
        boolean required = requiresSourceBinding(reworkTask, source);
        if (MesReworkIdentifierScope.NONE.equals(scope.getMode())) {
            require(!required, "原实际工序已存在动态换码区段，原NONE返工范围失效，须重新规划");
            return;
        }
        require(required, "批准的原动态实例范围与当前原工单永久区段不一致");
        Map<Long, MesReworkIdentifierCandidateDTO> current = new HashMap<>();
        for (var candidate : candidates(reworkTask, source)) current.put(candidate.getBindingId(), candidate);
        require(current.size() >= scope.getItems().size(), "原动态实例有效来源已减少，批准范围失效");
        for (var frozen : scope.getItems()) {
            var actual = current.get(positiveId(frozen.getSourceBindingId(), "冻结原实例ID"));
            require(matches(frozen, actual), "原动态实例状态、版本或来源报告已变化，须重新提交返工执行方案");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Prepared prepare(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                            ReportModeEnum mode, String outcome) {
        if (task == null || !"REWORK".equals(task.getResourceType())) return null;
        String factory = factoryAccess.require("write");
        assertRework(task, factory);
        require(arrange != null && Objects.equals(arrange.getTenantId(), tenant())
                        && Objects.equals(arrange.getFactoryCode(), factory)
                        && Objects.equals(arrange.getTaskCode(), task.getCode()),
                "返工标识与实际派工不属于当前租户工厂");
        require(Set.of("GOOD", "NG").contains(outcome), "返工标识使用结果类型无效");
        validateApproved(task);
        MesReworkIdentifierScope scope = MesReworkIdentifierScope.parse(task.getReworkIdentifierScopeSnapshot());
        if (MesReworkIdentifierScope.NONE.equals(scope.getMode())) return null;
        require(mode == ReportModeEnum.ONE_CODE && command != null && command.getPassNum() != null
                        && BigDecimal.ONE.compareTo(command.getPassNum()) == 0,
                "原动态实例返工必须按单件码逐件报工，GOOD和NG每次数量均为1");
        require(command.getExternalIdentifier() == null && command.getExpectedBindingId() == null
                        && command.getExpectedBindingVersion() == null,
                "返工只扫描批准范围内的一个现场码，不能再次提交BIND双码或原区段预览版本");
        String submitted = normalizedIdentifier(command.getTransferCode());
        List<MesReworkIdentifierScope.Item> matches = scope.getItems().stream().filter(item ->
                ("ACTIVE_EXTERNAL".equals(item.getInputMode()) && submitted.equals(item.getExternalCode()))
                        || ("CANONICAL_ONLY".equals(item.getInputMode())
                        && submitted.equals(item.getCanonicalIdentifier()))).toList();
        require(matches.size() == 1, "现场码未唯一命中批准的返工原实例；活动实例扫外码，已释放实例扫内部SN");
        var frozen = matches.get(0);
        lockIdentifiers(factory, List.of(frozen.getExternalCode(), frozen.getCanonicalIdentifier()));
        Long bindingId = positiveId(frozen.getSourceBindingId(), "冻结原实例ID");
        MesIdentifierBindingEntity binding = bindingMapper.selectOne(bindingScope(factory)
                .eq(MesIdentifierBindingEntity::getId, bindingId).last("FOR UPDATE"));
        require(binding != null && Objects.equals(binding.getBindingGroupId(), positiveId(frozen.getSourceBindingGroupId(), "冻结原区段ID"))
                        && Objects.equals(binding.getVersionNo(), frozen.getBindingVersion())
                        && Objects.equals(binding.getStatus(), frozen.getBindingStatus())
                        && Objects.equals(binding.getExternalNamespace(), frozen.getExternalNamespace())
                        && Objects.equals(binding.getExternalCode(), frozen.getExternalCode())
                        && Objects.equals(binding.getCanonicalIdentifier(), frozen.getCanonicalIdentifier()),
                "原动态实例已释放、恢复、冲销或复用，禁止按旧批准范围报工");
        MesTaskEntity source = sourceTask(task, factory);
        MesReworkIdentifierCandidateDTO current = candidates(task, source).stream()
                .filter(candidate -> Objects.equals(candidate.getBindingId(), bindingId)).findFirst().orElse(null);
        require(matches(frozen, current), "原动态实例的来源报告或节点操作已被冲销，禁止按旧批准范围报工");
        require(baseMapper.selectCount(usageScope(factory).eq(MesReworkIdentifierUsageEntity::getReworkTaskId, task.getId())
                .eq(MesReworkIdentifierUsageEntity::getSourceBindingId, bindingId)) == 0,
                "该原动态实例已在本返工工单完成GOOD或NG报工，不能重复使用");

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("version", RESOLUTION_VERSION);
        resolution.put("submittedIdentifier", submitted);
        resolution.put("canonicalIdentifier", binding.getCanonicalIdentifier());
        resolution.put("objectType", "UNIT");
        resolution.put("identifierType", "REWORK_SOURCE_BINDING");
        resolution.put("sourceBindingId", frozen.getSourceBindingId());
        resolution.put("sourceBindingGroupId", frozen.getSourceBindingGroupId());
        resolution.put("sourceBindingVersion", frozen.getBindingVersion());
        resolution.put("inputMode", frozen.getInputMode());
        resolution.put("scopeFingerprint", task.getReworkIdentifierScopeFingerprint());
        resolution.put("sourceOperationId", frozen.getSourceOperationId());
        resolution.put("sourceReportId", frozen.getSourceReportId());
        String resolutionSnapshot = JSONUtil.toJsonStr(resolution);
        command.setSubmittedIdentifier(submitted);
        command.setTransferCode(binding.getCanonicalIdentifier());
        command.setIdentifierMappingId(null);
        command.setIdentifierType("REWORK_SOURCE_BINDING");
        command.setIdentifierResolutionSnapshot(resolutionSnapshot);

        var actor = SecurityUtils.getUser();
        require(actor != null && actor.getId() != null && StrUtil.isNotBlank(actor.getUsername()),
                "返工实例报工需要当前实名用户");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("version", USAGE_VERSION);
        evidence.put("tenantId", tenant().toString());
        evidence.put("factoryCode", factory);
        evidence.put("reworkTaskId", task.getId().toString());
        evidence.put("reworkTaskCode", task.getCode());
        evidence.put("planId", task.getReworkMaterialPlanId().toString());
        evidence.put("outcome", outcome);
        evidence.put("sourceTaskId", scope.getSourceTaskId());
        evidence.put("sourceTaskCode", scope.getSourceTaskCode());
        evidence.put("sourceProduceTaskCode", scope.getSourceProduceTaskCode());
        evidence.put("sourceBindingId", frozen.getSourceBindingId());
        evidence.put("sourceBindingGroupId", frozen.getSourceBindingGroupId());
        evidence.put("sourceOperationId", frozen.getSourceOperationId());
        evidence.put("sourceReportId", frozen.getSourceReportId());
        evidence.put("submittedIdentifier", submitted);
        evidence.put("canonicalIdentifier", binding.getCanonicalIdentifier());
        evidence.put("inputMode", frozen.getInputMode());
        evidence.put("sourceBindingVersion", frozen.getBindingVersion());
        evidence.put("scopeFingerprint", task.getReworkIdentifierScopeFingerprint());
        evidence.put("resolutionSnapshot", resolutionSnapshot);
        String evidenceSnapshot = JSONUtil.toJsonStr(evidence);
        String evidenceFingerprint = sha256(USAGE_VERSION + "|" + evidenceSnapshot);
        return new Prepared(outcome, task.getReworkMaterialPlanId(), bindingId, submitted,
                binding.getCanonicalIdentifier(), binding.getVersionNo(), task.getReworkIdentifierScopeFingerprint(),
                evidenceSnapshot, evidenceFingerprint, actor.getId(), actor.getUsername());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void complete(Prepared prepared, MesTaskReportEntity report) {
        if (prepared == null) return;
        require("GOOD".equals(prepared.outcome()) && report != null && report.getId() != null
                        && "EFFECTIVE".equals(report.getStatus())
                        && Objects.equals(report.getSubmittedIdentifier(), prepared.submittedIdentifier())
                        && Objects.equals(report.getTransferCode(), prepared.canonicalIdentifier())
                        && "REWORK_SOURCE_BINDING".equals(report.getIdentifierType()),
                "返工GOOD报告与批准原实例准备结果不一致");
        append(prepared, report.getTenantId(), report.getFactoryCode(), report.getTaskCode(), report.getRequestId(),
                report.getId(), null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void complete(Prepared prepared, MesTaskReportUnqualifiedEntity report) {
        if (prepared == null) return;
        require("NG".equals(prepared.outcome()) && report != null && report.getId() != null
                        && Objects.equals(report.getSubmittedIdentifier(), prepared.canonicalIdentifier())
                        && Objects.equals(report.getTransferCode(), prepared.canonicalIdentifier())
                        && report.getIdentifierMappingId() == null && "CANONICAL".equals(report.getIdentifierType()),
                "返工NG报告与批准原实例准备结果不一致");
        append(prepared, report.getTenantId(), report.getFactoryCode(), report.getTaskCode(), report.getRequestId(),
                null, report.getId());
    }

    private void append(Prepared prepared, Long tenantId, String factory, String taskCode, String requestId,
                        Long goodReportId, Long ngReportId) {
        MesReworkIdentifierScope scope = approvedScope(tenantId, factory, taskCode);
        var frozen = scope.getItems().stream().filter(item -> Objects.equals(item.getSourceBindingId(),
                prepared.sourceBindingId().toString())).findFirst()
                .orElseThrow(() -> new CheckedException("返工报告实例已不在批准范围"));
        var usage = new MesReworkIdentifierUsageEntity().setFactoryCode(factory)
                .setReworkTaskId(Long.valueOf(scope.getReworkTaskId())).setReworkTaskCode(scope.getReworkTaskCode())
                .setPlanId(prepared.planId()).setSourceTaskId(Long.valueOf(scope.getSourceTaskId()))
                .setSourceTaskCode(scope.getSourceTaskCode()).setSourceProduceTaskCode(scope.getSourceProduceTaskCode())
                .setSourceBindingId(prepared.sourceBindingId())
                .setSourceBindingGroupId(Long.valueOf(frozen.getSourceBindingGroupId()))
                .setSourceOperationId(Long.valueOf(frozen.getSourceOperationId()))
                .setSourceReportId(Long.valueOf(frozen.getSourceReportId()))
                .setGoodReportId(goodReportId).setNgReportId(ngReportId).setOutcome(prepared.outcome())
                .setRequestId(requestId).setSubmittedIdentifier(prepared.submittedIdentifier())
                .setCanonicalIdentifier(prepared.canonicalIdentifier()).setInputMode(frozen.getInputMode())
                .setSourceBindingVersion(prepared.sourceBindingVersion()).setScopeFingerprint(prepared.scopeFingerprint())
                .setEvidenceSnapshot(prepared.evidenceSnapshot()).setEvidenceFingerprint(prepared.evidenceFingerprint())
                .setActorId(prepared.actorId()).setActorCode(prepared.actorCode()).setOperationTime(LocalDateTime.now());
        usage.setTenantId(tenantId);
        usage.setCreateBy(prepared.actorCode());
        usage.setCreateTime(usage.getOperationTime());
        usage.setDelFlag("0");
        require(baseMapper.insert(usage) == 1, "返工原动态实例永久使用账保存失败，整笔报工回滚");
    }

    private MesReworkIdentifierScope approvedScope(Long tenantId, String factory, String taskCode) {
        require(Objects.equals(tenantId, tenant()) && Objects.equals(factory, factoryAccess.require("write")),
                "返工使用账不属于当前租户工厂");
        List<MesTaskEntity> rows = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenantId).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
        require(rows.size() == 1, "返工使用账未唯一找到实际工单");
        validateApproved(rows.get(0));
        return MesReworkIdentifierScope.parse(rows.get(0).getReworkIdentifierScopeSnapshot());
    }

    private List<MesReworkIdentifierCandidateDTO> candidates(MesTaskEntity rework, MesTaskEntity source) {
        List<MesReworkIdentifierCandidateDTO> raw = baseMapper.selectCandidates(tenant(), rework.getFactoryCode(),
                source.getId(), source.getCode(), rework.getSourceProduceTaskCode());
        require(raw.size() <= 1000, "原实际工序动态实例超过1000行，禁止截断后规划返工");
        return raw.stream().filter(item -> Integer.valueOf(1).equals(item.getSourceOperationCount()))
                .sorted(Comparator.comparing(MesReworkIdentifierCandidateDTO::getBindingId)).toList();
    }

    private boolean requiresSourceBinding(MesTaskEntity rework, MesTaskEntity source) {
        List<MesIdentifierBindingGroupEntity> groups = groupMapper.selectList(
                new LambdaQueryWrapper<MesIdentifierBindingGroupEntity>()
                        .eq(MesIdentifierBindingGroupEntity::getTenantId, tenant())
                        .eq(MesIdentifierBindingGroupEntity::getFactoryCode, rework.getFactoryCode())
                        .eq(MesIdentifierBindingGroupEntity::getTaskId, source.getId())
                        .eq(MesIdentifierBindingGroupEntity::getTaskCode, source.getCode())
                        .eq(MesIdentifierBindingGroupEntity::getStatus, "FROZEN").last("LIMIT 2001"));
        require(groups.size() <= 2000, "原工单永久换码区段超过完整核验上限");
        for (var group : groups) {
            var nodes = JSONUtil.parseArray(group.getRegionSnapshot());
            require(!nodes.isEmpty() && nodes.size() <= 2000, "原永久换码区段节点快照损坏或超限");
            if (nodes.stream().map(JSONUtil::parseObj).anyMatch(node ->
                    Objects.equals(node.getStr("produceTaskCode"), rework.getSourceProduceTaskCode()))) return true;
        }
        return false;
    }

    private MesTaskEntity sourceTask(MesTaskEntity rework, String factory) {
        require(StrUtil.isNotBlank(rework.getSourceTaskCode()) && StrUtil.isNotBlank(rework.getSourceProduceTaskCode())
                        && StrUtil.isNotBlank(rework.getReworkRouteNodeCode()),
                "返工缺少原工单、原实际工序或稳定返工节点");
        List<MesTaskEntity> rows = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, rework.getSourceTaskCode()).last("LIMIT 2"));
        require(rows.size() == 1 && !Objects.equals(rows.get(0).getId(), rework.getId()),
                "原生产工单未唯一归属当前租户工厂");
        return rows.get(0);
    }

    private MesReworkIdentifierScope identity(MesTaskEntity rework, MesTaskEntity source) {
        return new MesReworkIdentifierScope().setTenantId(tenant().toString())
                .setFactoryCode(rework.getFactoryCode()).setReworkTaskId(rework.getId().toString())
                .setReworkTaskCode(rework.getCode()).setSourceTaskId(source.getId().toString())
                .setSourceTaskCode(source.getCode()).setSourceProduceTaskCode(rework.getSourceProduceTaskCode())
                .setRouteNodeCode(rework.getReworkRouteNodeCode()).setItems(new ArrayList<>());
    }

    private MesReworkIdentifierScope.Item item(MesReworkIdentifierCandidateDTO candidate) {
        return new MesReworkIdentifierScope.Item().setSourceBindingId(candidate.getBindingId().toString())
                .setSourceBindingGroupId(candidate.getBindingGroupId().toString())
                .setSourceOperationId(candidate.getSourceOperationId().toString())
                .setSourceReportId(candidate.getSourceReportId().toString())
                .setCanonicalIdentifier(candidate.getCanonicalIdentifier())
                .setExternalNamespace(candidate.getExternalNamespace()).setExternalCode(candidate.getExternalCode())
                .setBindingStatus(candidate.getBindingStatus()).setBindingVersion(candidate.getBindingVersion())
                .setInputMode(candidate.getInputMode()).setDefinitionFingerprint(candidate.getDefinitionFingerprint())
                .setGraphFingerprint(candidate.getGraphFingerprint())
                .setSourceRouteFingerprint(candidate.getSourceRouteFingerprint());
    }

    private boolean matches(MesReworkIdentifierScope.Item frozen, MesReworkIdentifierCandidateDTO actual) {
        return actual != null && Objects.equals(frozen.getSourceBindingGroupId(), actual.getBindingGroupId().toString())
                && Objects.equals(frozen.getSourceOperationId(), actual.getSourceOperationId().toString())
                && Objects.equals(frozen.getSourceReportId(), actual.getSourceReportId().toString())
                && Objects.equals(frozen.getCanonicalIdentifier(), actual.getCanonicalIdentifier())
                && Objects.equals(frozen.getExternalNamespace(), actual.getExternalNamespace())
                && Objects.equals(frozen.getExternalCode(), actual.getExternalCode())
                && Objects.equals(frozen.getBindingStatus(), actual.getBindingStatus())
                && Objects.equals(frozen.getBindingVersion(), actual.getBindingVersion())
                && Objects.equals(frozen.getInputMode(), actual.getInputMode())
                && Objects.equals(frozen.getDefinitionFingerprint(), actual.getDefinitionFingerprint())
                && Objects.equals(frozen.getGraphFingerprint(), actual.getGraphFingerprint())
                && Objects.equals(frozen.getSourceRouteFingerprint(), actual.getSourceRouteFingerprint());
    }

    private boolean sameCandidate(MesReworkIdentifierCandidateDTO left, MesReworkIdentifierCandidateDTO right) {
        return left != null && matches(item(left), right);
    }

    private boolean sameBinding(MesReworkIdentifierCandidateDTO candidate, MesIdentifierBindingEntity binding) {
        return Objects.equals(candidate.getBindingGroupId(), binding.getBindingGroupId())
                && Objects.equals(candidate.getSourceTaskId(), binding.getTaskId())
                && Objects.equals(candidate.getSourceTaskCode(), binding.getTaskCode())
                && Objects.equals(candidate.getExternalNamespace(), binding.getExternalNamespace())
                && Objects.equals(candidate.getExternalCode(), binding.getExternalCode())
                && Objects.equals(candidate.getCanonicalIdentifier(), binding.getCanonicalIdentifier())
                && Objects.equals(candidate.getBindingStatus(), binding.getStatus())
                && Objects.equals(candidate.getBindingVersion(), binding.getVersionNo());
    }

    private void lockCandidateIdentifiers(String factory, List<MesReworkIdentifierCandidateDTO> candidates) {
        require(candidates.stream().noneMatch(Objects::isNull), "所选原动态实例不在当前有效候选中");
        List<String> identifiers = candidates.stream()
                .flatMap(item -> List.of(item.getExternalCode(), item.getCanonicalIdentifier()).stream()).toList();
        lockIdentifiers(factory, identifiers);
    }

    private void lockIdentifiers(String factory, List<String> identifiers) {
        identifiers.stream().map(this::normalizedIdentifier)
                .map(value -> MesIdentifierBindingLock.key(tenant(), factory, value)).distinct().sorted()
                .forEach(key -> require(Integer.valueOf(1).equals(bindingMapper.lockIdentity(key)),
                        "未取得返工原标识业务锁"));
    }

    private LambdaQueryWrapper<MesIdentifierBindingEntity> bindingScope(String factory) {
        return new LambdaQueryWrapper<MesIdentifierBindingEntity>().eq(MesIdentifierBindingEntity::getTenantId, tenant())
                .eq(MesIdentifierBindingEntity::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<MesReworkIdentifierUsageEntity> usageScope(String factory) {
        return new LambdaQueryWrapper<MesReworkIdentifierUsageEntity>()
                .eq(MesReworkIdentifierUsageEntity::getTenantId, tenant())
                .eq(MesReworkIdentifierUsageEntity::getFactoryCode, factory);
    }

    private void assertRework(MesTaskEntity task, String factory) {
        require(task != null && task.getId() != null && "REWORK".equals(task.getResourceType())
                        && Objects.equals(task.getTenantId(), tenant()) && Objects.equals(task.getFactoryCode(), factory)
                        && task.getNoticeNum() != null && task.getNoticeNum().signum() > 0,
                "返工原实例只能用于当前实际租户工厂的正式返工工单");
    }

    private Long positiveId(String raw, String label) {
        try {
            require(raw != null && raw.matches("[1-9][0-9]{0,18}"), label + "格式无效");
            long value = Long.parseLong(raw);
            require(value > 0, label + "必须为正数");
            return value;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException(label + "格式无效");
        }
    }

    private String normalizedIdentifier(String value) {
        String normalized = StrUtil.trim(value);
        require(StrUtil.isNotBlank(normalized) && normalized.length() <= 200
                        && normalized.chars().noneMatch(Character::isISOControl),
                "返工现场标识不能为空、超过200字符或包含控制字符");
        return normalized;
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("返工原实例证据摘要生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
