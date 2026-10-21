package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesErpPostingOutboxEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTaskReportFeedEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.mapper.MesErpPostingOutboxMapper;
import com.kitex.mom.mes.service.MesErpPostingOutboxService;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 在MES业务事务内捕获已成立执行事实，不同步调用PLAN或ERP。 */
@Service
@RequiredArgsConstructor
public class MesErpPostingOutboxServiceImpl
        extends ServiceImpl<MesErpPostingOutboxMapper, MesErpPostingOutboxEntity>
        implements MesErpPostingOutboxService {
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void captureCompletion(MesTaskEntity task, MesTaskReportEntity report) {
        Map<String, Object> payload = basePayload(task);
        payload.put("reportId", text(report.getId()));
        payload.put("reportRequestId", report.getRequestId());
        payload.put("arrangeCode", report.getArrangeCode());
        payload.put("materialCode", report.getMatCode());
        payload.put("quantity", report.getReportNum());
        payload.put("effectiveQuantity", report.getEffectiveReportNum());
        payload.put("unitCode", report.getReportUnit());
        payload.put("reportMode", report.getReportMode());
        payload.put("reportTime", report.getReportTime());
        capture("COMPLETION_POSTING", task, "GOOD-" + report.getRequestId(), "1", payload,
                report.getReportTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void captureScrap(MesTaskEntity task, MesTaskReportUnqualifiedEntity report) {
        Map<String, Object> payload = basePayload(task);
        payload.put("unqualifiedReportId", text(report.getId()));
        payload.put("reportRequestId", report.getRequestId());
        payload.put("arrangeCode", report.getArrangeCode());
        payload.put("materialCode", report.getMatCode());
        payload.put("quantity", report.getUnqualifiedNum());
        payload.put("unqualifiedType", report.getType());
        payload.put("reportTime", report.getReportTime());
        capture("SCRAP_POSTING", task, "NG-" + report.getRequestId(), "1", payload,
                report.getReportTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void captureMaterial(MesTaskEntity task, MesTaskReportFeedEntity feed) {
        Map<String, Object> payload = basePayload(task);
        payload.put("feedId", text(feed.getId()));
        payload.put("arrangeCode", feed.getArrangeCode());
        payload.put("materialCode", feed.getMatCode());
        payload.put("quantity", feed.getFeedNum());
        payload.put("reportTime", feed.getReportTime());
        capture("MATERIAL_POSTING", task, "FEED-" + feed.getId(), "1", payload, feed.getReportTime());
    }

    private void capture(String eventType, MesTaskEntity task, String sourceNo, String sourceVersion,
                         Map<String, Object> payload, LocalDateTime occurredTime) {
        if (task == null || StrUtil.isBlank(task.getFormalPlanContractNo())) return;
        require(task.getPlanningPolicyId() != null && task.getPlanningPolicyVersion() != null
                        && isFingerprint(task.getPlanningPolicyFingerprint())
                        && StrUtil.isNotBlank(task.getMrpAuthority())
                        && task.getFormalPlanContractVersion() != null
                        && isFingerprint(task.getFormalPlanContractFingerprint()),
                "MES执行事实缺少接单时冻结的策略或正式计划合同，禁止形成ERP凭证");
        PlnErpPostingEnvelope envelope = new PlnErpPostingEnvelope()
                .setContractVersion("MOM_ERP_POSTING_V1").setRequestId("MES-ERP-" + sourceNo)
                .setEventType(eventType).setSourceModule("MES").setSourceDocumentNo(sourceNo)
                .setSourceDocumentVersion(sourceVersion).setFactoryCode(task.getFactoryCode())
                .setMrpAuthority(task.getMrpAuthority())
                .setProductionOrderSyncMode(task.getProductionOrderSyncMode())
                .setPlanningPolicyId(task.getPlanningPolicyId().toString())
                .setPlanningPolicyVersion(task.getPlanningPolicyVersion())
                .setPlanningPolicyFingerprint(task.getPlanningPolicyFingerprint())
                .setFormalPlanContractNo(task.getFormalPlanContractNo())
                .setFormalPlanContractVersion(task.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(task.getFormalPlanContractFingerprint())
                .setPayloadJson(json(payload)).setOccurredTime(Objects.requireNonNullElse(occurredTime, LocalDateTime.now()));
        String immutablePayload = json(envelope);
        String fingerprint = sha256(immutablePayload);
        Long tenantId = TenantContextHolder.getTenantId();
        require(tenantId != null && tenantId > 0, "MES ERP凭证入账缺少租户上下文");
        baseMapper.lock("MES_ERP_POSTING:" + tenantId + ':' + task.getFactoryCode() + ':' + envelope.getRequestId());
        MesErpPostingOutboxEntity existing = getOne(new LambdaQueryWrapper<MesErpPostingOutboxEntity>()
                .eq(MesErpPostingOutboxEntity::getTenantId, tenantId)
                .eq(MesErpPostingOutboxEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesErpPostingOutboxEntity::getRequestId, envelope.getRequestId()).last("LIMIT 1"), false);
        if (existing != null) {
            require(Objects.equals(existing.getRequestFingerprint(), fingerprint),
                    "MES ERP凭证永久请求号已存在但内容不同");
            return;
        }
        MesErpPostingOutboxEntity row = new MesErpPostingOutboxEntity()
                .setRequestId(envelope.getRequestId()).setRequestFingerprint(fingerprint)
                .setEventType(eventType).setSourceDocumentNo(sourceNo).setSourceDocumentVersion(sourceVersion)
                .setFormalPlanContractNo(task.getFormalPlanContractNo())
                .setFormalPlanContractVersion(task.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(task.getFormalPlanContractFingerprint())
                .setPlanningPolicyId(task.getPlanningPolicyId()).setPlanningPolicyVersion(task.getPlanningPolicyVersion())
                .setPlanningPolicyFingerprint(task.getPlanningPolicyFingerprint())
                .setPayloadJson(immutablePayload).setStatus("PENDING").setAttemptCount(0)
                .setQueuedTime(LocalDateTime.now()).setFactoryCode(task.getFactoryCode());
        row.setId(IdUtil.getSnowflakeNextId());
        row.setTenantId(tenantId);
        row.setCreateBy("MES");
        require(save(row), "MES ERP凭证Outbox保存失败");
    }

    private Map<String, Object> basePayload(MesTaskEntity task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", text(task.getId()));
        payload.put("taskCode", task.getCode());
        payload.put("productionOrder", task.getProductionOrder());
        payload.put("erpProductionOrderNo", task.getErpProductionOrderNo());
        payload.put("planNo", task.getPlanNo());
        payload.put("productCode", task.getMatCode());
        return payload;
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new CheckedException("MES ERP凭证无法规范序列化"); } }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException("SHA-256不可用", ex); } }
    private static boolean isFingerprint(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String text(Long value) { return value == null ? null : value.toString(); }
    private static void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }
}
