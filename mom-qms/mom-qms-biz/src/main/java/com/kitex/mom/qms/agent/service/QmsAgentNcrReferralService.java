package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.agent.dto.QmsAgentNcrReferralDTO;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadOperationEntity;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.InspectionProductTaskService;
import com.kitex.mom.qms.service.QmsBadInfoService;
import com.kitex.mom.qms.service.QmsBadInfoDetailService;
import com.kitex.mom.qms.service.QmsBadOperationService;
import com.kitex.mom.qms.service.QmsCapaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 质量人员在原 QMS 操作账中确认参数异常调查与既有 NCR 的精确来源关联。
 * 仅追加审计回执；正式 NCR 的建单、WMS 隔离、MRB、CAPA 和放行均走原流程。
 */
@Service
@RequiredArgsConstructor
public class QmsAgentNcrReferralService {

    private final QmsAgentFactoryAccessService factoryAccess;
    private final PermissionService permissionService;
    private final QmsBadInfoService badInfoService;
    private final QmsBadOperationService operationService;
    private final QmsBadInfoDetailService detailService;
    private final QmsCapaService capaService;
    private final InspectionProcedureTaskService procedureTaskService;
    private final InspectionProductTaskService productTaskService;

    @Transactional(rollbackFor = Exception.class)
    public QmsAgentNcrReferralDTO.Receipt accept(QmsAgentNcrReferralDTO.Accept command) {
        require(command != null && command.requestId() != null && command.factFingerprint() != null
                        && command.workOrderCode() != null && command.agentRunId() != null && command.agentRunId() > 0
                        && command.ncrId() != null && command.ncrId() > 0,
                "参数异常运行与NCR主键不能为空");
        String factory = factoryAccess.requireRead(command.factoryCode());
        require(permissionService.hasPermission("agent:process-deviation:action")
                        && permissionService.hasPermission("bad:judge")
                        && permissionService.hasPermission("bad:show")
                        && (permissionService.hasPermission("procedureTask:show")
                        || permissionService.hasPermission("productTask:show")),
                "当前岗位缺少质量转交或原案件查看权限");
        require(command.requestId().matches("PDEV-QMS-[1-9][0-9]{0,18}-[0-9a-f]{16}")
                        && command.factFingerprint().matches("[0-9a-f]{64}")
                        && command.workOrderCode().equals(command.workOrderCode().trim()),
                "Agent质量转交的请求号、事实指纹或工单编码不合法");
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && user.getUsername() != null,
                "质量转交须由实名人员确认");

        // 锁定原NCR行，使相同请求号的并发确认按原操作账唯一索引串行落账。
        QmsBadInfoEntity ncr = badInfoService.getOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
                .eq(QmsBadInfoEntity::getId, command.ncrId())
                .eq(QmsBadInfoEntity::getFactoryCode, factory)
                .last("FOR UPDATE"), false);
        require(ncr != null && Objects.equals(ncr.getTenantId(), TenantContextHolder.getTenantId())
                        && ncr.getResourceTaskId() != null,
                "NCR不存在或不属于当前租户工厂");
        Source source = inspectionSource(ncr, factory);
        require(permissionService.hasPermission("PROCEDURE".equals(ncr.getResourceType())
                        ? "procedureTask:show" : "productTask:show"),
                "当前岗位无权查看该NCR的原检验任务");
        require(Objects.equals(source.workOrderCode(), command.workOrderCode())
                        && InspectionTaskResultEnum.UN_QUALIFIED.name().equals(source.result())
                        && Objects.equals(ncr.getMatCode(), source.materialCode())
                        && Objects.equals(ncr.getBatchNo(), source.batchNo()),
                "NCR来源检验任务、工单、判定、物料或批次与本次转交不一致");

        String content = referralContent(command, ncr);
        QmsBadOperationEntity prior = operationService.getOne(new LambdaQueryWrapper<QmsBadOperationEntity>()
                .eq(QmsBadOperationEntity::getRequestId, command.requestId())
                .eq(QmsBadOperationEntity::getFactoryCode, factory), false);
        if (prior != null) {
            require("AGENT_REFER".equals(prior.getOperationType())
                            && Objects.equals(prior.getBadId(), ncr.getId())
                            && Objects.equals(prior.getContent(), content)
                            && Objects.equals(prior.getOperatorCode(), user.getUsername()),
                    "同一质量转交请求号已用于其他案件、证据或确认人");
            return receipt(prior, ncr, source.workOrderCode());
        }

        require(!"CLOSED".equals(ncr.getStatus()) && !"LEGACY_CLOSED".equals(ncr.getStatus()),
                "原NCR已关闭，不能事后登记新的Agent质量转交；请重新核查当前质量风险");

        QmsBadOperationEntity accepted = new QmsBadOperationEntity()
                .setRequestId(command.requestId()).setBadId(ncr.getId()).setNcrNo(ncr.getNcrNo())
                .setOperationType("AGENT_REFER").setBeforeStatus(ncr.getStatus())
                .setAfterStatus(ncr.getStatus()).setContent(content)
                .setOperatorCode(user.getUsername()).setOperatorName(user.getName())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(factory);
        accepted.setTenantId(ncr.getTenantId());
        require(operationService.save(accepted), "QMS质量转交回执保存失败");
        return receipt(accepted, ncr, source.workOrderCode());
    }

    /**
     * 只读核对原关联履历、NCR 全量明细验证和关联 CAPA 的当前终态。
     * 缺失、未关闭或关闭早于转交均作为显式缺口返回，绝不把 Agent 操作账当作关闭凭据。
     */
    @Transactional(readOnly = true)
    public QmsAgentNcrReferralDTO.ClosureReceipt closure(QmsAgentNcrReferralDTO.Accept query) {
        require(query != null && query.agentRunId() != null && query.agentRunId() > 0
                        && query.ncrId() != null && query.ncrId() > 0
                        && query.requestId() != null
                        && query.requestId().matches("PDEV-QMS-[1-9][0-9]{0,18}-[0-9a-f]{16}")
                        && query.factFingerprint() != null && query.factFingerprint().matches("[0-9a-f]{64}")
                        && query.workOrderCode() != null && !query.workOrderCode().isBlank(),
                "质量关联查询缺少可信请求号、运行、NCR或原事实摘要");
        String factory = factoryAccess.requireRead(query.factoryCode());
        require(permissionService.hasPermission("bad:show"), "当前岗位无权查看原NCR");
        QmsBadInfoEntity ncr = badInfoService.getById(query.ncrId());
        require(ncr != null && Objects.equals(ncr.getTenantId(), TenantContextHolder.getTenantId())
                        && factory.equals(ncr.getFactoryCode()), "原NCR不存在或跨租户工厂");
        require(permissionService.hasPermission("PROCEDURE".equals(ncr.getResourceType())
                        ? "procedureTask:show" : "productTask:show"), "当前岗位无权查看原检验任务");
        Source source = inspectionSource(ncr, factory);
        require(query.workOrderCode().equals(source.workOrderCode())
                        && InspectionTaskResultEnum.UN_QUALIFIED.name().equals(source.result())
                        && Objects.equals(ncr.getMatCode(), source.materialCode())
                        && Objects.equals(ncr.getBatchNo(), source.batchNo()),
                "原NCR的检验来源、工单、物料或批次已变化");
        QmsBadOperationEntity referral = operationService.getOne(new LambdaQueryWrapper<QmsBadOperationEntity>()
                .eq(QmsBadOperationEntity::getRequestId, query.requestId())
                .eq(QmsBadOperationEntity::getFactoryCode, factory), false);
        require(referral != null && Objects.equals(referral.getTenantId(), ncr.getTenantId())
                        && Objects.equals(referral.getBadId(), ncr.getId())
                        && "AGENT_REFER".equals(referral.getOperationType())
                        && Objects.equals(referral.getContent(), referralContent(query, ncr))
                        && referral.getOperationTime() != null,
                "原QMS未找到与本次调查一致的实名关联回执");

        List<String> gaps = new ArrayList<>();
        List<String> refs = new ArrayList<>(List.of("QMS:qms_bad_info:" + ncr.getId(),
                "QMS:qms_bad_operation:" + referral.getId()));
        List<QmsBadInfoDetailEntity> details = detailService.list(new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                .eq(QmsBadInfoDetailEntity::getBadId, ncr.getId())
                .eq(QmsBadInfoDetailEntity::getFactoryCode, factory)
                .orderByAsc(QmsBadInfoDetailEntity::getId));
        if (details.isEmpty()) gaps.add("原NCR没有可核验的处置明细");
        LocalDateTime closedAt = null;
        StringBuilder facts = new StringBuilder();
        append(facts, referral.getId(), referral.getOperationTime(), ncr.getId(), ncr.getStatus(),
                ncr.getResourceType(), ncr.getResourceTaskId(), source.workOrderCode(),
                ncr.getMatCode(), ncr.getBatchNo(), ncr.getNum(), ncr.getClosedNum(),
                ncr.getHoldSyncStatus(), ncr.getCapaId(), ncr.getCapaStatus(), ncr.getCapaRequiredReason());
        for (QmsBadInfoDetailEntity detail : details) {
            refs.add("QMS:qms_bad_info_detail:" + detail.getId());
            if (!Objects.equals(detail.getTenantId(), ncr.getTenantId())
                    || !"CLOSED".equals(detail.getStatus())
                    || !"PASS".equals(detail.getVerificationResult())
                    || detail.getVerificationTime() == null) {
                gaps.add("NCR明细" + detail.getId() + "尚无可信的处置验证通过记录");
            } else if (closedAt == null || detail.getVerificationTime().isAfter(closedAt)) {
                closedAt = detail.getVerificationTime();
            }
            append(facts, detail.getId(), detail.getStatus(), detail.getJudgeNum(),
                    detail.getVerificationResult(), detail.getVerificationTime(), detail.getMesReceiptId(),
                    detail.getWmsOperationId(), detail.getWmsSyncStatus());
        }
        if (!"CLOSED".equals(ncr.getStatus()) || !"SUCCESS".equals(ncr.getHoldSyncStatus())
                || ncr.getNum() == null || ncr.getClosedNum() == null
                || ncr.getNum().compareTo(ncr.getClosedNum()) != 0) {
            gaps.add("原NCR未完成全部数量的正式验证关闭或质量隔离状态不完整");
        }
        QmsCapaEntity capa = null;
        if (ncr.getCapaId() == null && ncr.getCapaRequiredReason() != null
                && !ncr.getCapaRequiredReason().isBlank()) {
            gaps.add("原NCR要求CAPA但尚未建立关联CAPA");
        }
        if (ncr.getCapaId() != null) {
            capa = capaService.getById(ncr.getCapaId());
            if (capa == null || !Objects.equals(capa.getTenantId(), ncr.getTenantId())
                    || !factory.equals(capa.getFactoryCode()) || !"NCR".equals(capa.getSourceType())
                    || !Objects.equals(capa.getSourceId(), ncr.getId())) {
                gaps.add("关联CAPA不存在或与原NCR来源不符");
                capa = null;
            } else {
                refs.add("QMS:qms_capa:" + capa.getId());
                append(facts, capa.getId(), capa.getStatus(), capa.getEffectivenessResult(),
                        capa.getClosedTime(), capa.getVerificationTime(), capa.getCycleNo());
                if (!"CLOSED".equals(capa.getStatus())
                        || !"PASS".equals(capa.getEffectivenessResult())
                        || capa.getClosedTime() == null) {
                    gaps.add("关联CAPA未通过有效性验证并正式关闭");
                } else if (closedAt == null || capa.getClosedTime().isAfter(closedAt)) {
                    closedAt = capa.getClosedTime();
                }
            }
        }
        if (closedAt == null || !closedAt.isAfter(referral.getOperationTime())) {
            gaps.add("原质量案件缺少晚于本次转交的可信关闭时点");
        }
        return new QmsAgentNcrReferralDTO.ClosureReceipt(query.requestId(), ncr.getTenantId(),
                factory, source.workOrderCode(), query.agentRunId(), ncr.getId(), ncr.getNcrNo(),
                ncr.getResourceType(), ncr.getResourceTaskId(), ncr.getStatus(), referral.getOperationTime(),
                closedAt, capa == null ? null : capa.getStatus(),
                capa == null ? null : capa.getEffectivenessResult(), gaps.isEmpty(), digest(facts.toString()),
                List.copyOf(gaps), List.copyOf(refs));
    }

    private String referralContent(QmsAgentNcrReferralDTO.Accept command, QmsBadInfoEntity ncr) {
        return "质量岗位确认关联参数异常调查 " + command.agentRunId()
                + "；工单 " + command.workOrderCode()
                + "；原检验 " + ncr.getResourceType() + ":" + ncr.getResourceTaskId()
                + "；调查事实摘要 " + command.factFingerprint()
                + "。仅为关联回执，不改变NCR处置状态。";
    }

    private void append(StringBuilder facts, Object... values) {
        for (Object value : values) {
            String text = Objects.toString(value, "<null>");
            facts.append(text.length()).append(':').append(text);
        }
    }

    private String digest(String facts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成原QMS质量终态摘要", failure);
        }
    }

    private Source inspectionSource(QmsBadInfoEntity ncr, String factory) {
        if ("PROCEDURE".equals(ncr.getResourceType())) {
            QmsInspectionProcedureTaskEntity task = procedureTaskService.getById(ncr.getResourceTaskId());
            require(task != null && factory.equals(task.getFactoryCode())
                            && Objects.equals(ncr.getTenantId(), task.getTenantId()),
                    "NCR来源过程检验任务不存在或跨租户工厂");
            return new Source(task.getOrderNo(), task.getResult(), task.getMatCode(), task.getBatchNo());
        }
        if ("PRODUCT".equals(ncr.getResourceType())) {
            QmsInspectionProductTaskEntity task = productTaskService.getById(ncr.getResourceTaskId());
            require(task != null && factory.equals(task.getFactoryCode())
                            && Objects.equals(ncr.getTenantId(), task.getTenantId()),
                    "NCR来源成品检验任务不存在或跨租户工厂");
            return new Source(task.getOrderNo(), task.getResult(), task.getMatCode(), task.getBatchNo());
        }
        throw new CheckedException("此NCR不是过程/成品检验的原始案件，不能关联参数异常调查");
    }

    private QmsAgentNcrReferralDTO.Receipt receipt(QmsBadOperationEntity operation,
            QmsBadInfoEntity ncr, String workOrderCode) {
        return new QmsAgentNcrReferralDTO.Receipt(operation.getRequestId(), ncr.getTenantId(), operation.getFactoryCode(),
                workOrderCode, ncr.getId(), ncr.getNcrNo(), ncr.getResourceType(),
                ncr.getResourceTaskId(), ncr.getStatus(), operation.getOperatorCode(),
                operation.getOperationTime(), "DOMAIN_REFERRED_EXISTING_CASE");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private record Source(String workOrderCode, String result, String materialCode, String batchNo) {
    }
}
