package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipLossCommand;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWipLossCaseEntity;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.entity.MesWipTransferOperationEntity;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesWipLossCaseMapper;
import com.kitex.mom.mes.mapper.MesWipTransferMapper;
import com.kitex.mom.mes.mapper.MesWipTransferOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 丢失是独立在途数量的受控核销，不是假实收，也不再次扣减已在发出时扣掉的库存。
 */
@Service
@RequiredArgsConstructor
public class MesWipLossService {
    private final MesActualFactoryAccessService access;
    private final MesWipTransferMapper transfers;
    private final MesWipTransferOperationMapper operations;
    private final MesWipLossCaseMapper cases;
    private final MesTaskReportMapper reports;

    public Map<String, Object> context(Long id, long page) {
        String factory = access.require("read");
        require(page > 0 && page <= 100000, "页码无效");
        var transfer = transfer(id, factory, false);
        require(transfer != null, "当前工厂转运不存在");
        return Map.of("transfer", transfer, "active", cases.selectList(scope(factory).eq(MesWipLossCaseEntity::getTransferId, id).in(MesWipLossCaseEntity::getStatus, "PENDING", "APPROVED")), "cases", cases.selectPage(new Page<>(page, 20), scope(factory)
                .eq(MesWipLossCaseEntity::getTransferId, id).orderByDesc(MesWipLossCaseEntity::getId)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesWipLossCommand c) {
        String factory = access.require("write");
        normalize(c);
        var actor = SecurityUtils.getUser();
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(c, actor.getId())));
        // 与正常转运共用请求命名空间及锁顺序：请求锁 → 原报工 → 转运 → 案件。
        transfers.lockRequest("MES-WIP-TRANSFER:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + c.getRequestId());
        var old = operations.selectOne(new LambdaQueryWrapper<MesWipTransferOperationEntity>()
                .eq(MesWipTransferOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWipTransferOperationEntity::getFactoryCode, factory).eq(MesWipTransferOperationEntity::getRequestId, c.getRequestId()));
        if (old != null) {
            require(fingerprint.equals(old.getFingerprint()) && old.getLossCaseId() != null, "请求号已用于不同内容/人员，请核对原回执");
            var prior = cases.selectOne(scope(factory).eq(MesWipLossCaseEntity::getId, old.getLossCaseId()));
            require(prior != null, "原核销回执缺少案件");
            return Map.of("transfer", transfer(old.getTransferId(), factory, false), "case", prior, "operation", old, "replayed", true);
        }
        var observed = transfer(c.getTransferId(), factory, false);
        require(observed != null, "当前工厂转运不存在");
        var report = reports.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getId, observed.getSourceReportId())
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskReportEntity::getFactoryCode, factory).last("FOR UPDATE"));
        require(report != null && "EFFECTIVE".equals(report.getStatus()), "原报工已失效或来源不可核验");
        var d = transfer(c.getTransferId(), factory, true);
        require(d != null && "HOLD".equals(d.getStatus()) && Objects.equals(d.getRevision(), c.getExpectedRevision()), "须先异常暂停且版本未变化；请刷新核对当前在途事实");
        require(d.getRemainingQuantity() != null && d.getRemainingQuantity().signum() > 0 && d.getLostQuantity() != null, "在途数量或历史损失量未知，不能核销");
        var before = JSONUtil.toJsonStr(d);
        var op = new MesWipTransferOperationEntity();
        op.setId(IdWorker.getId());
        op.setTenantId(d.getTenantId());
        op.setFactoryCode(factory);
        op.setTransferId(d.getId());
        op.setOperationType("LOSS_" + c.getOperation());
        op.setRequestId(c.getRequestId());
        op.setFingerprint(fingerprint);
        op.setOperatorId(actor.getId());
        op.setOperatorCode(actor.getUsername());
        op.setReason(c.getReason());
        op.setQuantity(BigDecimal.ZERO);
        op.setBeforeStatus(d.getStatus());
        op.setBeforeRemaining(d.getRemainingQuantity());
        MesWipLossCaseEntity row;
        if ("PROPOSE".equals(c.getOperation())) {
            require(cases.selectCount(scope(factory).eq(MesWipLossCaseEntity::getTransferId, d.getId()).in(MesWipLossCaseEntity::getStatus, "PENDING", "APPROVED")) == 0,
                    "已有待审批/待执行核销，不得重叠申请");
            require(c.getQuantity().compareTo(d.getRemainingQuantity()) <= 0, "丢失数量超过当前在途");
            require(!Boolean.TRUE.equals(d.getIndivisible()) || c.getQuantity().compareTo(d.getRemainingQuantity()) == 0, "标识型转运须整件/整卡/整包核销，不得凭空生成部分丢失子标识");
            row = new MesWipLossCaseEntity();
            row.setId(IdWorker.getId());
            row.setTenantId(d.getTenantId());
            row.setFactoryCode(factory);
            row.setTransferId(d.getId());
            row.setQuantity(c.getQuantity());
            row.setStatus("PENDING");
            row.setReason(c.getReason());
            row.setInvestigationEvidence(c.getInvestigationEvidence());
            row.setResponsibility(c.getResponsibility());
            row.setPrevention(c.getPrevention());
            row.setSourceSnapshot(before);
            row.setBasisFingerprint(basis(d));
            row.setApplicantId(actor.getId());
            row.setApplicantCode(actor.getUsername());
            row.setLastOperationId(op.getId());
            require(cases.insert(row) == 1, "丢失核销申请保存失败");
        } else {
            row = cases.selectOne(scope(factory).eq(MesWipLossCaseEntity::getId, c.getCaseId()).eq(MesWipLossCaseEntity::getTransferId, d.getId()).last("FOR UPDATE"));
            require(row != null, "原核销申请不存在或不属于当前转运");
            if (Set.of("APPROVE", "EXECUTE").contains(c.getOperation()))
                require(basis(d).equals(row.getBasisFingerprint()), "冻结在途依据已变化，不能沿旧证据批准或执行；可有原因驳回/撤回后重新调查");
            switch (c.getOperation()) {
                case "APPROVE", "REJECT" -> {
                    require("PENDING".equals(row.getStatus()), "仅待审申请可复核");
                    require(!Objects.equals(actor.getId(), row.getApplicantId()) && !Objects.equals(actor.getId(), d.getDispatchUserId())
                            && !Objects.equals(actor.getId(), d.getReturnUserId()), "复核人须不同于申请人及本次运输发出/退回经手人");
                    row.setStatus("APPROVE".equals(c.getOperation()) ? "APPROVED" : "REJECTED");
                    row.setReviewerId(actor.getId());
                    row.setReviewerCode(actor.getUsername());
                    row.setReviewTime(LocalDateTime.now());
                }
                case "WITHDRAW" -> {
                    require(Set.of("PENDING", "APPROVED").contains(row.getStatus()) && Objects.equals(row.getApplicantId(), actor.getId()), "仅原申请人可撤回尚未实际核销的申请");
                    row.setStatus("WITHDRAWN");
                }
                case "EXECUTE" -> {
                    require("APPROVED".equals(row.getStatus()) && row.getReviewerId() != null, "丢失核销须先经独立复核批准");
                    require(!Objects.equals(actor.getId(), row.getApplicantId()) && !Objects.equals(actor.getId(), row.getReviewerId())
                                    && !Objects.equals(actor.getId(), d.getDispatchUserId()) && !Objects.equals(actor.getId(), d.getReturnUserId()),
                            "核销执行人须独立于申请、复核及运输发出/退回经手人");
                    require(row.getQuantity().compareTo(d.getRemainingQuantity()) <= 0, "可核销在途数量不足");
                    d.setRemainingQuantity(d.getRemainingQuantity().subtract(row.getQuantity()));
                    d.setLostQuantity(d.getLostQuantity().add(row.getQuantity()));
                    if (d.getRemainingQuantity().signum() == 0) {
                        d.setStatus("CLOSED");
                        d.setClosedTime(LocalDateTime.now());
                    }
                    row.setStatus("EXECUTED");
                    row.setExecutorId(actor.getId());
                    row.setExecutorCode(actor.getUsername());
                    row.setExecuteTime(LocalDateTime.now());
                    op.setQuantity(row.getQuantity());
                }
                default -> throw new CheckedException("不支持的核销操作");
            }
            row.setLastOperationId(op.getId());
            require(cases.updateById(row) == 1, "核销状态保存失败");
        }
        op.setLossCaseId(row.getId());
        d.setRevision(d.getRevision() + 1);
        require(transfers.updateById(d) == 1, "转运核销数量保存失败");
        op.setSequenceNo(d.getRevision());
        op.setAfterStatus(d.getStatus());
        op.setAfterRemaining(d.getRemainingQuantity());
        op.setEvidence(JSONUtil.toJsonStr(Map.of("version", "WIP_LOSS_V1", "before", before, "case", row, "command", c)));
        require(operations.insert(op) == 1, "丢失核销永久回执保存失败");
        var saved = cases.selectById(row.getId());
        var savedTransfer = transfer(d.getId(), factory, false);
        require(saved != null && saved.getQuantity().compareTo(row.getQuantity()) == 0 && savedTransfer.getLostQuantity().compareTo(d.getLostQuantity()) == 0
                && savedTransfer.getRemainingQuantity().compareTo(d.getRemainingQuantity()) == 0, "核销数量写入发生精度变化，已回滚");
        return Map.of("transfer", savedTransfer, "case", saved, "operation", op, "replayed", false);
    }

    private void normalize(MesWipLossCommand c) {
        require(c != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}") && c.getTransferId() != null
                && c.getExpectedRevision() != null && c.getExpectedRevision() > 0, "须提供转运、当前版本和16至64位永久请求号");
        require(Set.of("PROPOSE", "APPROVE", "REJECT", "WITHDRAW", "EXECUTE").contains(Objects.toString(c.getOperation(), "")), "核销操作无效");
        text(c.getReason(), 500, "实际原因");
        c.setReason(c.getReason().trim());
        if ("PROPOSE".equals(c.getOperation())) {
            require(c.getCaseId() == null && c.getQuantity() != null && c.getQuantity().signum() > 0 && c.getQuantity().precision() <= 24 && Math.max(0, c.getQuantity().scale()) <= 8, "申请须明确正丢失量，最多24位且8位小数");
            c.setQuantity(c.getQuantity().stripTrailingZeros());
            text(c.getInvestigationEvidence(), 2000, "调查和寻回证据");
            text(c.getResponsibility(), 1000, "责任认定依据");
            text(c.getPrevention(), 1000, "整改措施及责任跟进");
            c.setInvestigationEvidence(c.getInvestigationEvidence().trim());
            c.setResponsibility(c.getResponsibility().trim());
            c.setPrevention(c.getPrevention().trim());
            require(Boolean.TRUE.equals(c.getSearchedConfirmed()) && Boolean.TRUE.equals(c.getNoReceiptConfirmed()) && c.getDecisionConfirmed() == null,
                    "须确认已调查查找且对应数量确未实收；不能将破损或过期实物作丢失核销");
        } else {
            require(c.getCaseId() != null && c.getQuantity() == null && c.getInvestigationEvidence() == null && c.getResponsibility() == null && c.getPrevention() == null
                    && c.getSearchedConfirmed() == null && c.getNoReceiptConfirmed() == null, "复核、撤回或执行只能引用原申请，不能修改数量和调查事实");
            if (Set.of("APPROVE", "EXECUTE").contains(c.getOperation()))
                require(Boolean.TRUE.equals(c.getDecisionConfirmed()), "须明确确认依据及核销后果，不自动批准或执行");
            else require(c.getDecisionConfirmed() == null, "驳回/撤回不附带核销确认");
        }
    }

    private String basis(MesWipTransferEntity d) {
        // 审批自身增加版本/更新时间，不改变实物基线；任何数量、状态或方向变化均失效。
        var json = new TreeMap<String, Object>(JSONUtil.parseObj(d));
        json.remove("revision");
        json.remove("updateBy");
        json.remove("updateTime");
        return hash(JSONUtil.toJsonStr(json));
    }

    private MesWipTransferEntity transfer(Long id, String factory, boolean lock) {
        var q = new LambdaQueryWrapper<MesWipTransferEntity>().eq(MesWipTransferEntity::getId, id).eq(MesWipTransferEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWipTransferEntity::getFactoryCode, factory);
        if (lock) q.last("FOR UPDATE");
        return transfers.selectOne(q);
    }

    private LambdaQueryWrapper<MesWipLossCaseEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesWipLossCaseEntity>().eq(MesWipLossCaseEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesWipLossCaseEntity::getFactoryCode, factory);
    }

    private void text(String s, int max, String label) {
        require(StrUtil.isNotBlank(s) && s.length() <= max, "须填写" + max + "字以内" + label);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    private String hash(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
