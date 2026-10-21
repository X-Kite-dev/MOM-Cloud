package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.tpm.dto.TpmRepairRecoveryEvidence;
import com.kitex.mom.tpm.dto.TpmRepairVerificationCommand;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.SparePartsApplicationSourceEnum;
import com.kitex.mom.tpm.mapper.*;
import com.kitex.mom.tpm.service.TpmRepairTaskService;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TpmRepairVerificationService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance).addSerializer(BigDecimal.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final TpmRepairTaskMapper tasks;
    private final TpmRepairVerificationMapper versions;
    private final TpmRepairVerificationOperationMapper operations;
    private final TpmRepairTaskService completion;
    private final TpmSparePartsApplicationService applications;
    private final TpmMaintenanceTaskMapper maintenanceTasks;
    private final TpmCheckTaskMapper checkTasks;
    private final RemoteMdmFactoryAccessService factories;
    private final org.mybatis.spring.SqlSessionTemplate session;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Object context(Long id) {
        String f = access("read");
        var t = task(id, f, false);
        var source = source(t);
        var hold = versions.deviceHold(TenantContextHolder.getTenantId(), f, id);
        return Map.of("task", t, "source", source, "fingerprint", hash(json(source)),
                "deviceHold", hold == null ? Map.of() : hold,
                "arrangeStops", versions.arrangeStops(TenantContextHolder.getTenantId(), f, id),
                "versions", versions.selectList(scope(f).eq(TpmRepairVerification::getTaskId, id).orderByDesc(TpmRepairVerification::getId)),
                "notice", "提交维修结果不结束故障窗口。独立验证通过才关闭本维修任务；MDM设备资格、其他维修/保养/点检和MES复产门仍独立，不自动恢复可用或开机。");
    }

    public Object history(Long id) {
        String f = access("read");
        var v = version(id, f, false);
        return Map.of("version", v, "operations", operations.selectList(opScope(f).eq(TpmRepairVerificationOperation::getVerificationId, id).orderByAsc(TpmRepairVerificationOperation::getId)));
    }

    @Transactional(readOnly = true)
    public TpmRepairRecoveryEvidence recoveryEvidence(Long id) {
        String f = access("read");
        var v = version(id, f, false);
        require("VERIFIED".equals(v.getStatus()), "维修版本尚未独立验证通过");
        var latest = tasks.selectOne(new LambdaQueryWrapper<TpmRepairTaskEntity>().eq(TpmRepairTaskEntity::getTenantId, v.getTenantId())
                .eq(TpmRepairTaskEntity::getFactoryCode, f).eq(TpmRepairTaskEntity::getDeviceCode, v.getDeviceCode())
                .last("ORDER BY COALESCE(submit_time,create_time) DESC NULLS LAST,id DESC LIMIT 1"));
        require(latest != null && Objects.equals(latest.getId(), v.getTaskId()), "此验证不是该设备最近一次维修，不能用于当前设备资格恢复");
        var op = operations.selectOne(opScope(f).eq(TpmRepairVerificationOperation::getVerificationId, id).eq(TpmRepairVerificationOperation::getAction, "VERIFY"));
        require(op != null && Objects.equals(op.getOperatorId(), v.getVerifierId()) && Boolean.TRUE.equals(op.getFaultResolved())
                && Boolean.TRUE.equals(op.getTrialVerified()) && Boolean.TRUE.equals(op.getProtectionVerified()), "维修恢复验证永久回执缺失或不完整");
        var blockers = new ArrayList<String>();
        if (tasks.selectCount(new LambdaQueryWrapper<TpmRepairTaskEntity>().eq(TpmRepairTaskEntity::getTenantId, v.getTenantId()).eq(TpmRepairTaskEntity::getFactoryCode, f)
                .eq(TpmRepairTaskEntity::getDeviceCode, v.getDeviceCode()).eq(TpmRepairTaskEntity::getState, "0")) > 0)
            blockers.add("仍有未完成维修任务");
        if (maintenanceTasks.selectCount(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>().eq(TpmMaintenanceTaskEntity::getTenantId, v.getTenantId()).eq(TpmMaintenanceTaskEntity::getFactoryCode, f)
                .eq(TpmMaintenanceTaskEntity::getDeviceCode, v.getDeviceCode()).eq(TpmMaintenanceTaskEntity::getState, "1")) > 0)
            blockers.add("仍有进行中保养任务");
        if (checkTasks.selectCount(new LambdaQueryWrapper<TpmCheckTaskEntity>().eq(TpmCheckTaskEntity::getTenantId, v.getTenantId()).eq(TpmCheckTaskEntity::getFactoryCode, f)
                .eq(TpmCheckTaskEntity::getDeviceCode, v.getDeviceCode()).eq(TpmCheckTaskEntity::getBlockStatus, "BLOCKED")) > 0)
            blockers.add("仍有点检生产封锁");
        // 跨服务资格恢复只传不可变证明摘要，不转运完整维修/备件原始快照。
        var signed = new TpmRepairVerification();
        signed.setId(v.getId());
        signed.setTenantId(v.getTenantId());
        signed.setFactoryCode(v.getFactoryCode());
        signed.setTaskId(v.getTaskId());
        signed.setTaskCode(v.getTaskCode());
        signed.setDeviceCode(v.getDeviceCode());
        signed.setStatus(v.getStatus());
        signed.setApplicantId(v.getApplicantId());
        signed.setVerifierId(v.getVerifierId());
        signed.setSourceFingerprint(v.getSourceFingerprint());
        var verified = new TpmRepairVerificationOperation();
        verified.setId(op.getId());
        verified.setTenantId(op.getTenantId());
        verified.setFactoryCode(op.getFactoryCode());
        verified.setVerificationId(op.getVerificationId());
        verified.setRequestId(op.getRequestId());
        verified.setFingerprint(op.getFingerprint());
        verified.setAction(op.getAction());
        verified.setResultStatus(op.getResultStatus());
        verified.setOperatorId(op.getOperatorId());
        verified.setReason(op.getReason());
        verified.setFaultResolved(op.getFaultResolved());
        verified.setTrialVerified(op.getTrialVerified());
        verified.setProtectionVerified(op.getProtectionVerified());
        var result = new TpmRepairRecoveryEvidence();
        result.setVersion(signed);
        result.setVerificationOperation(verified);
        result.setBlockers(blockers);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Object execute(String action, TpmRepairVerificationCommand c) {
        String f = access("write");
        var user = SecurityUtils.getUser();
        require(Set.of("SUBMIT", "VERIFY", "REJECT", "WITHDRAW").contains(action), "维修验证动作无效");
        require(c != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}") && text(c.getReason(), 1000), "填写稳定请求号及实际处理依据");
        c.setReason(c.getReason().trim());
        if ("SUBMIT".equals(action)) {
            require(c.getId() == null && c.getTaskId() != null && c.getExpectedFingerprint() != null && c.getExpectedFingerprint().matches("[a-f0-9]{64}")
                    && text(c.getFaultReason(), 200) && text(c.getRepairMethod(), 200) && text(c.getRepairResult(), 4) && c.getParts() != null && c.getParts().size() <= 100
                    && c.getFaultResolved() == null && c.getTrialVerified() == null && c.getProtectionVerified() == null, "先预览原维修任务，完整填写故障原因、维修方法、结果及实际备件（无使用传空列表）");
            Set<String> codes = new HashSet<>();
            for (var p : c.getParts()) {
                require(p != null && text(p.getSparePartsCode(), 100) && codes.add(p.getSparePartsCode()) && p.getUseNum() != null && p.getUseNum().signum() > 0 && p.getUseNum().stripTrailingZeros().scale() <= 2, "备件不可重复、使用量须为正且最多2位小数");
                p.setUseNum(p.getUseNum().stripTrailingZeros());
            }
            c.getParts().sort(Comparator.comparing(TpmRepairVerificationCommand.Part::getSparePartsCode));
        } else {
            require(c.getId() != null && c.getTaskId() == null && c.getExpectedFingerprint() == null && c.getParts() == null && c.getFaultReason() == null && c.getRepairMethod() == null && c.getRepairResult() == null, "处理原版本不能夹带内容变更");
            if ("VERIFY".equals(action))
                require(Boolean.TRUE.equals(c.getFaultResolved()) && Boolean.TRUE.equals(c.getTrialVerified()) && Boolean.TRUE.equals(c.getProtectionVerified()), "须确认故障消除、试运行结果及防护条件三项现场验证");
            else
                require(c.getFaultResolved() == null && c.getTrialVerified() == null && c.getProtectionVerified() == null, "驳回/撤回不能伪造验证通过标记");
        }
        String fingerprint = hash(json(Arrays.asList(action, c, user.getId())));
        versions.lockKey("TPM-REPAIR-VERIFY:" + TenantContextHolder.getTenantId() + ":" + f + ":" + c.getRequestId());
        session.clearCache();
        var replay = operations.selectOne(opScope(f).eq(TpmRepairVerificationOperation::getRequestId, c.getRequestId()));
        if (replay != null) {
            require(fingerprint.equals(replay.getFingerprint()), "请求号已用于不同内容、动作或人员");
            return Map.of("version", version(replay.getVerificationId(), f, false), "operation", replay, "replayed", true);
        }
        TpmRepairVerification v;
        if ("SUBMIT".equals(action)) {
            var t = task(c.getTaskId(), f, true);
            require("0".equals(t.getState()), "已完成维修不能覆盖或重报验证");
            require(versions.selectCount(scope(f).eq(TpmRepairVerification::getTaskId, t.getId()).eq(TpmRepairVerification::getStatus, "PENDING")) == 0, "已有待验证结果，请先处理");
            var source = source(t);
            String raw = json(source);
            require(c.getExpectedFingerprint().equals(hash(raw)), "维修任务或备件申请已变化，请重新预览");
            var work = work(t, c, parts(t));
            v = new TpmRepairVerification();
            v.setTenantId(TenantContextHolder.getTenantId());
            v.setFactoryCode(f);
            v.setTaskId(t.getId());
            v.setTaskCode(t.getTaskCode());
            v.setDeviceCode(t.getDeviceCode());
            v.setSourceJson(raw);
            v.setSourceFingerprint(hash(raw));
            v.setWorkJson(json(work));
            v.setApplicantId(user.getId());
            v.setApplicantCode(user.getUsername());
            v.setApplicantName(user.getName());
            v.setReason(c.getReason());
            v.setStatus("PENDING");
            require(versions.insert(v) == 1, "维修结果提案保存失败");
        } else {
            var found = version(c.getId(), f, false);
            var t = task(found.getTaskId(), f, true);
            v = version(c.getId(), f, true);
            require("PENDING".equals(v.getStatus()), "仅待验证版本可处理");
            if ("WITHDRAW".equals(action)) require(Objects.equals(v.getApplicantId(), user.getId()), "仅提交人可撤回");
            else require(!Objects.equals(v.getApplicantId(), user.getId()), "须由另一名授权人员独立验证或驳回");
            if ("VERIFY".equals(action)) {
                require("0".equals(t.getState()) && v.getSourceFingerprint().equals(hash(json(source(t)))), "维修或备件来源已变化，请驳回后重提，不能按旧依据结束维修");
                v.setStatus("VERIFIED");
                v.setVerifierId(user.getId());
                v.setVerificationReason(c.getReason());
                require(versions.updateById(v) == 1, "验证结果保存失败");
                require(Boolean.TRUE.equals(completion.completeVerifiedTask(v.getId())), "维修完成提交失败");
            } else {
                v.setStatus("WITHDRAW".equals(action) ? "WITHDRAWN" : "REJECTED");
                v.setVerifierId(user.getId());
                v.setVerificationReason(c.getReason());
                require(versions.updateById(v) == 1, "维修验证处理失败");
            }
        }
        var op = new TpmRepairVerificationOperation();
        op.setTenantId(v.getTenantId());
        op.setFactoryCode(f);
        op.setVerificationId(v.getId());
        op.setRequestId(c.getRequestId());
        op.setFingerprint(fingerprint);
        op.setAction(action);
        op.setResultStatus(v.getStatus());
        op.setOperatorId(user.getId());
        op.setReason(c.getReason());
        op.setFaultResolved(c.getFaultResolved());
        op.setTrialVerified(c.getTrialVerified());
        op.setProtectionVerified(c.getProtectionVerified());
        require(operations.insert(op) == 1, "维修永久回执保存失败");
        return Map.of("version", v, "operation", op, "replayed", false);
    }

    private Map<String, Object> source(TpmRepairTaskEntity t) {
        var result = Map.<String, Object>of("task", t, "parts", parts(t));
        require(json(result).length() <= 1000000, "维修证据超过容量，不能截断");
        return result;
    }

    private List<TpmSparePartsApplicationDetailsEntity> parts(TpmRepairTaskEntity t) {
        var rows = applications.getApplyBySourceCode(t.getTaskCode(), SparePartsApplicationSourceEnum.FAULT.getCode());
        require(rows != null && rows.size() <= 1000, "备件来源不可读取或超限");
        return rows.stream().sorted(Comparator.comparing(TpmSparePartsApplicationDetailsEntity::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    private TpmRepairTaskEntity work(TpmRepairTaskEntity t, TpmRepairVerificationCommand c, List<TpmSparePartsApplicationDetailsEntity> parts) {
        Map<String, TpmSparePartsApplicationDetailsEntity> reference = new HashMap<>();
        Map<String, BigDecimal> available = new HashMap<>();
        for (var p : parts)
            if ("1".equals(p.getApprovalStatus())) {
                require(text(p.getSparePartCode(), 100) && text(p.getUnit(), 100) && p.getQuantityRequested() != null && p.getQuantityRequested().signum() >= 0, "已通过备件来源缺少单位/数量");
                var previous = reference.putIfAbsent(p.getSparePartCode(), p);
                require(previous == null || Objects.equals(previous.getUnit(), p.getUnit()), "同备件存在不同单位，不能混加或自动退回");
                available.merge(p.getSparePartCode(), p.getQuantityRequested(), BigDecimal::add);
            }
        var details = new ArrayList<TpmRepairTaskDetailsEntity>();
        for (var p : c.getParts()) {
            var a = reference.get(p.getSparePartsCode());
            require(a != null && p.getUseNum().compareTo(available.get(p.getSparePartsCode())) <= 0, "实际使用量超过该任务已通过备件数量，或来源未通过");
            var d = new TpmRepairTaskDetailsEntity();
            d.setSparePartsCode(a.getSparePartCode());
            d.setSparePartsName(a.getSparePartName());
            d.setUnit(a.getUnit());
            d.setSpecification(a.getSpecification());
            d.setUseNum(p.getUseNum());
            details.add(d);
        }
        var result = new TpmRepairTaskEntity();
        org.springframework.beans.BeanUtils.copyProperties(t, result);
        result.setFaultReason(c.getFaultReason());
        result.setRepairMethod(c.getRepairMethod());
        result.setRepairResult(c.getRepairResult());
        result.setDetailsList(details);
        return result;
    }

    private TpmRepairTaskEntity task(Long id, String f, boolean lock) {
        var q = new LambdaQueryWrapper<TpmRepairTaskEntity>().eq(TpmRepairTaskEntity::getTenantId, TenantContextHolder.getTenantId()).eq(TpmRepairTaskEntity::getFactoryCode, f).eq(TpmRepairTaskEntity::getId, id);
        if (lock) q.last("FOR UPDATE");
        var t = tasks.selectOne(q);
        require(t != null, "当前工厂维修任务不存在");
        return t;
    }

    private TpmRepairVerification version(Long id, String f, boolean lock) {
        var q = scope(f).eq(TpmRepairVerification::getId, id);
        if (lock) q.last("FOR UPDATE");
        var v = versions.selectOne(q);
        require(v != null, "当前工厂维修验证版本不存在");
        return v;
    }

    private LambdaQueryWrapper<TpmRepairVerification> scope(String f) {
        return new LambdaQueryWrapper<TpmRepairVerification>().eq(TpmRepairVerification::getTenantId, TenantContextHolder.getTenantId()).eq(TpmRepairVerification::getFactoryCode, f);
    }

    private LambdaQueryWrapper<TpmRepairVerificationOperation> opScope(String f) {
        return new LambdaQueryWrapper<TpmRepairVerificationOperation>().eq(TpmRepairVerificationOperation::getTenantId, TenantContextHolder.getTenantId()).eq(TpmRepairVerificationOperation::getFactoryCode, f);
    }

    private String access(String permission) {
        var u = SecurityUtils.getUser();
        String f = FactoryContextHolder.getFactoryCode();
        require(u != null && u.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(f), "须实名选择实际工厂");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(u.getId()).setFactoryCode(f).setRequiredPermission(permission));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "实际工厂权限不足或MDM不可核验");
        return f;
    }

    private boolean text(String v, int max) {
        return StrUtil.isNotBlank(v) && v.length() <= max;
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("维修证据无法完整序列化");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
