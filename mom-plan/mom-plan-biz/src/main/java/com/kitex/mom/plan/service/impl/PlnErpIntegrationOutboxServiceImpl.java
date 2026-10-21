package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import com.kitex.mom.plan.dto.PlnErpReconciliationDTO;
import com.kitex.mom.plan.entity.PlnFormalPlanContractEntity;
import com.kitex.mom.plan.entity.PlnErpIntegrationOperationEntity;
import com.kitex.mom.plan.entity.PlnErpIntegrationOutboxEntity;
import com.kitex.mom.plan.mapper.PlnErpIntegrationOperationMapper;
import com.kitex.mom.plan.mapper.PlnErpIntegrationOutboxMapper;
import com.kitex.mom.plan.mapper.PlnFormalPlanContractMapper;
import com.kitex.mom.plan.service.PlnErpIntegrationOutboxService;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** ERP 投递账入账、永久幂等和人工补偿实现。 */
@Service
@RequiredArgsConstructor
public class PlnErpIntegrationOutboxServiceImpl
        extends ServiceImpl<PlnErpIntegrationOutboxMapper, PlnErpIntegrationOutboxEntity>
        implements PlnErpIntegrationOutboxService {

    private static final Set<String> EVENT_TYPES = Set.of("PRODUCTION_ORDER_REQUEST", "COMMITMENT_UPDATE",
            "SUBSTITUTE_DECISION", "MATERIAL_POSTING", "COMPLETION_POSTING", "SCRAP_POSTING");
    private static final Set<String> MOM_PLAN_EVENTS = Set.of("PRODUCTION_ORDER_REQUEST", "SUBSTITUTE_DECISION");

    private final PlnFactoryPlanningAuthorityService authorityService;
    private final PlnFormalPlanContractMapper contractMapper;
    private final PlnErpIntegrationOperationMapper operationMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnErpIntegrationOutboxEntity enqueue(PlnErpPostingEnvelope envelope) {
        validate(envelope);
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(envelope.getFactoryCode());
        require(Objects.equals(policy.getId(), envelope.getPlanningPolicyId())
                        && Objects.equals(policy.getVersionNo(), envelope.getPlanningPolicyVersion())
                        && Objects.equals(policy.getContentFingerprint(), envelope.getPlanningPolicyFingerprint())
                        && Objects.equals(policy.getMrpAuthority(), envelope.getMrpAuthority()),
                "ERP回传信封引用的工厂计划权威已变化，必须重新形成正式业务事件");
        if (MOM_PLAN_EVENTS.contains(envelope.getEventType())) {
            require("MOM_MRP".equals(policy.getMrpAuthority()),
                    "ERP主导工厂不能由MOM生成第二套生产订单或替代正式决策");
        }
        PlnFormalPlanContractEntity contract = contractMapper.selectOne(
                new LambdaQueryWrapper<PlnFormalPlanContractEntity>()
                        .eq(PlnFormalPlanContractEntity::getFactoryCode, envelope.getFactoryCode())
                        .eq(PlnFormalPlanContractEntity::getContractNo, envelope.getFormalPlanContractNo())
                        .eq(PlnFormalPlanContractEntity::getContractVersion, envelope.getFormalPlanContractVersion())
                        .eq(PlnFormalPlanContractEntity::getVerificationStatus, "VERIFIED")
                        .eq(PlnFormalPlanContractEntity::getContractStatus, "PUBLISHED")
                        .orderByAsc(PlnFormalPlanContractEntity::getLineNo).last("LIMIT 1"));
        require(contract != null
                        && Objects.equals(contract.getSourceContentFingerprint(), envelope.getFormalPlanContractFingerprint())
                        && Objects.equals(String.valueOf(contract.getPlanningPolicyId()), policy.getId()),
                "ERP回传信封引用的正式计划合同无效");
        authorityService.revalidate(policy);
        String canonical = json(envelope);
        String fingerprint = sha256(canonical);
        Long tenantId = TenantContextHolder.getTenantId();
        require(tenantId != null && tenantId > 0, "ERP回传入账缺少租户上下文");
        baseMapper.lock("ERP_POSTING:" + tenantId + ':' + envelope.getFactoryCode() + ':' + envelope.getRequestId());
        PlnErpIntegrationOutboxEntity existing = getOne(new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                .eq(PlnErpIntegrationOutboxEntity::getTenantId, tenantId)
                .eq(PlnErpIntegrationOutboxEntity::getFactoryCode, envelope.getFactoryCode())
                .eq(PlnErpIntegrationOutboxEntity::getRequestId, envelope.getRequestId()).last("LIMIT 1"), false);
        if (existing != null) {
            require(Objects.equals(existing.getRequestFingerprint(), fingerprint),
                    "ERP回传永久请求号已存在但内容不同，已故障关闭");
            return existing;
        }
        PlnErpIntegrationOutboxEntity row = new PlnErpIntegrationOutboxEntity()
                .setRequestId(envelope.getRequestId()).setRequestFingerprint(fingerprint)
                .setEventType(envelope.getEventType()).setSourceModule(envelope.getSourceModule())
                .setSourceDocumentNo(envelope.getSourceDocumentNo())
                .setSourceDocumentVersion(envelope.getSourceDocumentVersion())
                .setErpSystemCode(policy.getErpSystemCode())
                .setPlanningPolicyId(parseId(policy.getId())).setPlanningPolicyVersion(policy.getVersionNo())
                .setPlanningPolicyFingerprint(policy.getContentFingerprint())
                .setFormalPlanContractNo(envelope.getFormalPlanContractNo())
                .setFormalPlanContractVersion(envelope.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(envelope.getFormalPlanContractFingerprint())
                .setPayloadJson(canonical).setStatus("PENDING").setAttemptCount(0)
                .setQueuedTime(LocalDateTime.now()).setFactoryCode(envelope.getFactoryCode());
        row.setId(IdUtil.getSnowflakeNextId());
        row.setTenantId(tenantId);
        row.setCreateBy(operator());
        require(save(row), "ERP回传Outbox入账失败");
        operation(row, "ENQUEUE", null, "PENDING", "领域事务已形成待投递事实", "SYSTEM");
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnErpIntegrationOutboxEntity retry(Long id, String reason) {
        require(id != null && StrUtil.isNotBlank(reason) && reason.trim().length() <= 500,
                "ERP投递人工重试必须提供任务和500字符内原因");
        String factoryCode = requireFactory();
        PlnErpIntegrationOutboxEntity row = getOne(new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                .eq(PlnErpIntegrationOutboxEntity::getId, id)
                .eq(PlnErpIntegrationOutboxEntity::getFactoryCode, factoryCode).last("FOR UPDATE"), false);
        require(row != null && List.of("BLOCKED_CONNECTOR", "BUSINESS_REJECTED", "DEAD_LETTER")
                .contains(row.getStatus()), "当前ERP投递状态不允许人工重试");
        String before = row.getStatus();
        row.setStatus("PENDING").setNextRetryTime(LocalDateTime.now()).setLeaseToken(null).setLeaseUntil(null)
                .setManualOperator(operator()).setManualReason(reason.trim()).setLastError(null)
                .setDeadLetterReason(null).setUpdateBy(operator());
        require(updateById(row), "ERP投递人工重试入队失败");
        operation(row, "MANUAL_RETRY", before, "PENDING", reason.trim(), operator());
        return row;
    }

    @Override
    public List<PlnErpIntegrationOutboxEntity> recent(int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        String factoryCode = requireFactory();
        return list(new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                .eq(PlnErpIntegrationOutboxEntity::getFactoryCode, factoryCode)
                .orderByDesc(PlnErpIntegrationOutboxEntity::getQueuedTime)
                .orderByDesc(PlnErpIntegrationOutboxEntity::getId).last("LIMIT " + size));
    }

    @Override
    public PlnErpReconciliationDTO reconcile(int limit) {
        String factoryCode = requireFactory();
        List<PlnErpIntegrationOutboxEntity> rows = recent(limit);
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        rows.forEach(row -> {
            statusCounts.merge(row.getStatus(), 1, Integer::sum);
            eventCounts.merge(row.getEventType(), 1, Integer::sum);
        });
        List<String> unfinished = List.of("PENDING", "SENDING", "RETRY_WAIT", "BLOCKED_CONNECTOR",
                "BUSINESS_REJECTED", "DEAD_LETTER");
        LocalDateTime oldest = rows.stream().filter(row -> unfinished.contains(row.getStatus()))
                .map(PlnErpIntegrationOutboxEntity::getQueuedTime).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
        List<String> attention = rows.stream()
                .filter(row -> List.of("BLOCKED_CONNECTOR", "BUSINESS_REJECTED", "DEAD_LETTER")
                        .contains(row.getStatus()))
                .map(PlnErpIntegrationOutboxEntity::getRequestId).limit(50).toList();
        return new PlnErpReconciliationDTO().setFactoryCode(factoryCode).setSampledCount(rows.size())
                .setPendingCount(count(statusCounts, "PENDING") + count(statusCounts, "SENDING")
                        + count(statusCounts, "RETRY_WAIT"))
                .setBlockedCount(count(statusCounts, "BLOCKED_CONNECTOR"))
                .setFailedCount(count(statusCounts, "BUSINESS_REJECTED") + count(statusCounts, "DEAD_LETTER"))
                .setSucceededCount(count(statusCounts, "SUCCEEDED")).setOldestUnfinishedTime(oldest)
                .setStatusCounts(Map.copyOf(statusCounts)).setEventCounts(Map.copyOf(eventCounts))
                .setAttentionRequestIds(attention).setReconciledTime(LocalDateTime.now());
    }

    private void validate(PlnErpPostingEnvelope e) {
        require(e != null && "MOM_ERP_POSTING_V1".equals(e.getContractVersion())
                        && StrUtil.isNotBlank(e.getRequestId()) && e.getRequestId().length() <= 100
                        && EVENT_TYPES.contains(e.getEventType()) && StrUtil.isNotBlank(e.getSourceModule())
                        && StrUtil.isNotBlank(e.getSourceDocumentNo()) && StrUtil.isNotBlank(e.getFactoryCode())
                        && StrUtil.isNotBlank(e.getPlanningPolicyId()) && e.getPlanningPolicyVersion() != null
                        && isFingerprint(e.getPlanningPolicyFingerprint())
                        && StrUtil.isNotBlank(e.getFormalPlanContractNo())
                        && e.getFormalPlanContractVersion() != null
                        && isFingerprint(e.getFormalPlanContractFingerprint())
                        && StrUtil.isNotBlank(e.getPayloadJson()) && e.getOccurredTime() != null,
                "ERP回传信封缺少正式合同、策略、来源或领域载荷");
    }

    private void operation(PlnErpIntegrationOutboxEntity row, String type, String before,
                           String after, String reason, String operator) {
        PlnErpIntegrationOperationEntity op = new PlnErpIntegrationOperationEntity()
                .setOutboxId(row.getId()).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setRequestFingerprint(row.getRequestFingerprint()).setReason(reason)
                .setOperatorCode(operator).setOperatorName(operator).setOperationTime(LocalDateTime.now())
                .setFactoryCode(row.getFactoryCode());
        op.setId(IdUtil.getSnowflakeNextId());
        op.setTenantId(row.getTenantId());
        op.setCreateBy(operator);
        require(operationMapper.insert(op) == 1, "ERP投递操作履历保存失败");
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new CheckedException("ERP回传信封无法规范序列化"); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }

    private static boolean isFingerprint(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static int count(Map<String, Integer> counts, String key) { return counts.getOrDefault(key, 0); }
    private static String requireFactory() {
        String factoryCode = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        require(!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factoryCode),
                "ERP投递操作必须选择实际工厂");
        return factoryCode;
    }
    private static Long parseId(String value) { try { return Long.valueOf(value); } catch (Exception ex) { throw new CheckedException("计划权威主键格式错误"); } }
    private static void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }
    private static String operator() {
        try { return SecurityUtils.getUser() == null ? "SYSTEM" : SecurityUtils.getUser().getUsername(); }
        catch (Exception ex) { return "SYSTEM"; }
    }
}
