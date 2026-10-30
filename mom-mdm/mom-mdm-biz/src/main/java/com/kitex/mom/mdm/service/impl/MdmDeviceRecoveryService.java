package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmDeviceRecoveryCommand;
import com.kitex.mom.mdm.dto.MdmDeviceRecoveryEventEvidence;
import com.kitex.mom.mdm.dto.MdmRepairAgentEvidence;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.entity.MdmDeviceRecoveryReceipt;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.mapper.MdmDeviceMapper;
import com.kitex.mom.mdm.mapper.MdmDeviceRecoveryReceiptMapper;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmUserFactoryModelBindMapper;
import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmRepairRecoveryEvidence;
import com.kitex.mom.tpm.feign.RemoteTpmDeviceAvailabilityService;
import com.kitex.mom.tpm.feign.RemoteTpmRepairRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 维修验证后另行恢复MDM设备资格；仅改变维修中/不可用为闲置/可用，不下发开机指令。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MdmDeviceRecoveryService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final MdmDeviceMapper devices;
    private final MdmDeviceRecoveryReceiptMapper receipts;
    private final MdmFactoryModelMapper factories;
    private final MdmUserFactoryModelBindMapper bindings;
    private final RemoteTpmRepairRecoveryService repairEvidence;
    private final RemoteTpmDeviceAvailabilityService availability;
    private final MdmPlanResourceEventOutboxService resourceEvents;
    private final SqlSessionTemplate session;

    @Transactional(readOnly = true)
    public Object preview(Long verificationId) {
        String f = access("read");
        var proof = proof(verificationId, f);
        var d = device(proof.getVersion().getDeviceCode(), f, false);
        var blockers = blockers(d);
        return Map.of("device", d, "recoveryEvidence", proof, "blockers", blockers,
                "fingerprint", hash(json(facts(d))), "canRelease", eligible(d, blockers));
    }

    @Transactional(rollbackFor = Exception.class)
    public MdmDeviceRecoveryReceipt release(MdmDeviceRecoveryCommand c) {
        String f = access("write");
        var actor = SecurityUtils.getUser();
        require(c != null && c.getVerificationId() != null && text(c.getRequestId(), 64) && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}")
                && c.getExpectedFingerprint() != null && c.getExpectedFingerprint().matches("[a-f0-9]{64}") && text(c.getReason(), 1000)
                && Boolean.TRUE.equals(c.getTrialConfirmed()) && Boolean.TRUE.equals(c.getSafetyConfirmed())
                && Boolean.TRUE.equals(c.getProductionHandoverConfirmed()), "恢复须指定已验证维修版本、原始设备指纹和现场试车/防护/生产交接三项依据");
        c.setReason(c.getReason().trim());
        String requestHash = hash(json(Arrays.asList(c, actor.getId())));
        receipts.lockKey("MDM-REPAIR-RECOVERY:" + TenantContextHolder.getTenantId() + ":" + f + ":" + c.getRequestId());
        session.clearCache();
        var previous = receipts.selectOne(scope(f).eq(MdmDeviceRecoveryReceipt::getRequestId, c.getRequestId()));
        if (previous != null) {
            require(requestHash.equals(previous.getRequestFingerprint()), "恢复请求号已用于其他内容或人员");
            return previous;
        }
        var proof = proof(c.getVerificationId(), f);
        require(!Objects.equals(proof.getVersion().getApplicantId(), actor.getId()), "维修提交人不能恢复设备资格");
        var d = device(proof.getVersion().getDeviceCode(), f, true);
        String before = json(facts(d));
        require(c.getExpectedFingerprint().equals(hash(before)), "MDM设备状态已变化，请重新预览并现场核验");
        var windows = blockers(d);
        require(eligible(d, windows), "设备已非维修中/不可用，或存在其他TPM阻断：" + String.join("；", windows));
        var afterFacts = new LinkedHashMap<>(facts(d));
        afterFacts.put("deviceState", "0");
        afterFacts.put("deviceUsable", "1");
        String source = json(Map.of("version", proof.getVersion(), "verificationOperation", proof.getVerificationOperation(), "blockers", proof.getBlockers(), "availabilityBlockers", windows));
        var r = new MdmDeviceRecoveryReceipt();
        r.setTenantId(TenantContextHolder.getTenantId());
        r.setFactoryCode(f);
        r.setVerificationId(c.getVerificationId());
        r.setDeviceId(d.getId());
        r.setDeviceCode(d.getDeviceCode());
        r.setRequestId(c.getRequestId());
        r.setRequestFingerprint(requestHash);
        r.setBeforeJson(before);
        r.setBeforeFingerprint(hash(before));
        r.setAfterJson(json(afterFacts));
        r.setSourceJson(source);
        r.setSourceFingerprint(hash(source));
        r.setActorId(actor.getId());
        r.setActorCode(actor.getUsername());
        r.setReason(c.getReason());
        r.setTrialConfirmed(true);
        r.setSafetyConfirmed(true);
        r.setProductionHandoverConfirmed(true);
        r.setCreateTime(LocalDateTime.now());
        require(receipts.insert(r) == 1, "设备资格恢复回执保存失败");
        require(devices.update(null, new LambdaUpdateWrapper<MdmDeviceEntity>().eq(MdmDeviceEntity::getId, d.getId()).eq(MdmDeviceEntity::getTenantId, d.getTenantId())
                .eq(MdmDeviceEntity::getFactoryCode, f).eq(MdmDeviceEntity::getDeviceState, "2").eq(MdmDeviceEntity::getDeviceUsable, "0")
                .eq(MdmDeviceEntity::getStatus, true).set(MdmDeviceEntity::getDeviceState, "0").set(MdmDeviceEntity::getDeviceUsable, "1")) == 1, "设备状态竞争变化，资格恢复未执行");
        session.clearCache();
        var after = devices.selectById(d.getId());
        require(after != null && facts(after).equals(afterFacts), "MDM设备资格恢复回读不一致");
        resourceEvents.enqueueDeviceRecovery(r, after, proof.getVersion().getTaskId(), proof.getVersion().getTaskCode());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    DynamicRefreshUtils.refreshMdm(after);
                } catch (RuntimeException ex) {
                    log.warn("设备资格恢复已提交但缓存通知失败, deviceId={}", after.getId(), ex);
                }
            }
        });
        return r;
    }

    @Transactional(readOnly = true)
    public MdmDeviceRecoveryEventEvidence eventEvidence(Long receiptId, String factory) {
        Long tenant = TenantContextHolder.getTenantId();
        require(receiptId != null && tenant != null && !TenantContextHolder.getTenantSkip() && text(factory, 100), "恢复事件须指定实际租户、工厂及回执");
        var r = receipts.selectOne(new LambdaQueryWrapper<MdmDeviceRecoveryReceipt>().eq(MdmDeviceRecoveryReceipt::getId, receiptId)
                .eq(MdmDeviceRecoveryReceipt::getTenantId, tenant).eq(MdmDeviceRecoveryReceipt::getFactoryCode, factory));
        require(r != null, "当前工厂设备恢复回执不存在");
        var d = devices.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>().eq(MdmDeviceEntity::getId, r.getDeviceId())
                .eq(MdmDeviceEntity::getTenantId, tenant).eq(MdmDeviceEntity::getFactoryCode, factory));
        require(d != null && Objects.equals(d.getDeviceCode(), r.getDeviceCode()), "设备恢复回执对应的当前MDM设备不可核验");
        var latest = receipts.selectOne(new LambdaQueryWrapper<MdmDeviceRecoveryReceipt>().eq(MdmDeviceRecoveryReceipt::getTenantId, tenant)
                .eq(MdmDeviceRecoveryReceipt::getFactoryCode, factory).eq(MdmDeviceRecoveryReceipt::getDeviceId, d.getId())
                .orderByDesc(MdmDeviceRecoveryReceipt::getId).last("LIMIT 1"));
        try {
            var version = JSON.readTree(r.getSourceJson()).path("version");
            Long taskId;
            try {
                taskId = Long.valueOf(version.path("taskId").asText());
            } catch (NumberFormatException ex) {
                throw new CheckedException("恢复回执缺少有效维修任务ID");
            }
            require(taskId > 0 && StrUtil.isNotBlank(version.path("taskCode").asText()), "恢复回执缺少维修任务来源");
            var result = new MdmDeviceRecoveryEventEvidence();
            result.setTenantId(tenant);
            result.setFactoryCode(factory);
            result.setReceiptId(r.getId());
            result.setLatestRecoveryReceiptId(latest == null ? null : latest.getId());
            result.setVerificationId(r.getVerificationId());
            result.setDeviceId(d.getId());
            result.setDeviceCode(d.getDeviceCode());
            result.setTaskId(taskId);
            result.setTaskCode(version.path("taskCode").asText());
            result.setActorId(r.getActorId());
            result.setRecoveryTime(r.getCreateTime());
            result.setRequestFingerprint(r.getRequestFingerprint());
            result.setSourceFingerprint(r.getSourceFingerprint());
            result.setDeviceStatus(d.getStatus());
            result.setDeviceState(d.getDeviceState());
            result.setDeviceUsable(d.getDeviceUsable());
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("设备恢复回执来源无法完整读取");
        }
    }

    @Transactional(readOnly = true)
    public MdmDeviceRecoveryEventEvidence latestEvidence(String deviceCode, String factory) {
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && !TenantContextHolder.getTenantSkip() && text(factory, 100) && text(deviceCode, 100),
                "最近设备恢复事实须指定实际租户、工厂和设备");
        var latest = receipts.selectOne(new LambdaQueryWrapper<MdmDeviceRecoveryReceipt>()
                .eq(MdmDeviceRecoveryReceipt::getTenantId, tenant).eq(MdmDeviceRecoveryReceipt::getFactoryCode, factory)
                .eq(MdmDeviceRecoveryReceipt::getDeviceCode, deviceCode).orderByDesc(MdmDeviceRecoveryReceipt::getId).last("LIMIT 1"));
        return latest == null ? null : eventEvidence(latest.getId(), factory);
    }

    public MdmDeviceRecoveryReceipt receipt(Long id) {
        String f = access("read");
        var r = receipts.selectOne(scope(f).eq(MdmDeviceRecoveryReceipt::getId, id));
        require(r != null, "当前工厂恢复回执不存在");
        return r;
    }

    public MdmDeviceRecoveryReceipt byVerification(Long id) {
        String f = access("read");
        return receipts.selectOne(scope(f).eq(MdmDeviceRecoveryReceipt::getVerificationId, id));
    }

    /**
     * 面向维修Agent的最小只读投影：任务、验证版本、设备编码和当前工厂必须全部吻合。
     * 不创建恢复回执，也不把MDM资格误当作MES派工复产批准。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MdmRepairAgentEvidence.Result agentEvidence(MdmRepairAgentEvidence.Query query) {
        String factory = access("read");
        require(query != null && factory.equals(query.getFactoryCode())
                && text(query.getDeviceCode(), 100), "维修设备资格查询须使用当前实际工厂和精确设备编码");
        Long taskId = positiveId(query.getRepairTaskId(), "维修任务主键");
        Long verificationId = positiveId(query.getVerificationId(), "维修验证版本主键");
        MdmDeviceEntity current = device(query.getDeviceCode(), factory, false);
        var result = new MdmRepairAgentEvidence.Result()
                .setFactoryCode(factory).setRepairTaskId(taskId.toString())
                .setVerificationId(verificationId.toString()).setDeviceCode(current.getDeviceCode())
                .setDeviceId(current.getId().toString()).setDeviceState(current.getDeviceState())
                .setDeviceUsable(current.getDeviceUsable()).setDeviceEnabled(current.getStatus())
                .setQualificationVerified(false).setObservedAt(LocalDateTime.now());
        result.getEvidenceRefs().add("MDM:mdm_device:" + current.getId());
        MdmDeviceRecoveryReceipt receipt = receipts.selectOne(scope(factory)
                .eq(MdmDeviceRecoveryReceipt::getDeviceCode, current.getDeviceCode())
                .orderByDesc(MdmDeviceRecoveryReceipt::getId).last("LIMIT 1"));
        if (receipt == null) {
            result.getDataGaps().add("当前设备尚无MDM正式资格恢复回执");
        } else {
            result.setRecoveryReceiptId(receipt.getId().toString()).setRecoveredAt(receipt.getCreateTime());
            result.getEvidenceRefs().add("MDM:mdm_device_recovery_receipt:" + receipt.getId());
            boolean sameSource = false;
            try {
                var version = JSON.readTree(receipt.getSourceJson()).path("version");
                sameSource = Objects.equals(receipt.getDeviceId(), current.getId())
                        && Objects.equals(receipt.getVerificationId(), verificationId)
                        && taskId.toString().equals(version.path("taskId").asText())
                        && current.getDeviceCode().equals(version.path("deviceCode").asText())
                        && hash(receipt.getSourceJson()).equals(receipt.getSourceFingerprint())
                        && Boolean.TRUE.equals(receipt.getTrialConfirmed())
                        && Boolean.TRUE.equals(receipt.getSafetyConfirmed())
                        && Boolean.TRUE.equals(receipt.getProductionHandoverConfirmed())
                        && receipt.getActorId() != null && receipt.getCreateTime() != null;
            } catch (RuntimeException malformed) {
                result.getDataGaps().add("MDM恢复回执来源无法完整核验");
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                result.getDataGaps().add("MDM恢复回执来源无法完整解析");
            }
            if (!sameSource) result.getDataGaps().add("最新MDM恢复回执不属于本维修任务及验证版本，或回执不完整");
            boolean currentEligible = Boolean.TRUE.equals(current.getStatus())
                    && "1".equals(current.getDeviceUsable())
                    && Set.of("0", "4", "5").contains(Objects.toString(current.getDeviceState(), ""));
            if (!currentEligible) result.getDataGaps().add("MDM当前设备状态不满足可用资格");
            result.setQualificationVerified(sameSource && currentEligible);
        }
        result.setFactFingerprint(hash(json(Arrays.asList(result.getFactoryCode(), result.getRepairTaskId(),
                result.getVerificationId(), result.getDeviceCode(), result.getDeviceId(),
                result.getDeviceState(), result.getDeviceUsable(), result.getDeviceEnabled(),
                result.getRecoveryReceiptId(), result.getRecoveredAt(), result.getQualificationVerified(),
                result.getDataGaps()))));
        return result;
    }

    private Long positiveId(String value, String label) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), label + "无效");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException overflow) {
            throw new CheckedException(label + "超出数据库范围");
        }
    }

    private TpmRepairRecoveryEvidence proof(Long id, String f) {
        require(id != null, "维修验证版本号不能为空");
        var response = repairEvidence.evidence(id);
        require(response != null && response.isOk() && response.getData() != null, "TPM维修验证依据不可读取");
        var e = response.getData();
        var v = e.getVersion();
        var op = e.getVerificationOperation();
        require(v != null && op != null && "VERIFIED".equals(v.getStatus()) && Objects.equals(v.getId(), id)
                && Objects.equals(v.getTenantId(), TenantContextHolder.getTenantId()) && f.equals(v.getFactoryCode()) && text(v.getDeviceCode(), 100)
                && "VERIFY".equals(op.getAction()) && Objects.equals(op.getVerificationId(), id) && Objects.equals(op.getOperatorId(), v.getVerifierId())
                && Boolean.TRUE.equals(op.getFaultResolved()) && Boolean.TRUE.equals(op.getTrialVerified()) && Boolean.TRUE.equals(op.getProtectionVerified())
                && e.getBlockers() != null, "维修验证结果或现场确认回执不完整、工厂不匹配");
        require(e.getBlockers().isEmpty(), "TPM仍有未消除故障/保养/点检阻断：" + String.join("；", e.getBlockers()));
        return e;
    }

    private List<String> blockers(MdmDeviceEntity d) {
        var q = new TpmDeviceAvailabilityQueryDTO();
        q.setDeviceCodes(List.of(d.getDeviceCode()));
        var now = LocalDateTime.now();
        q.setStartTime(now.minusSeconds(1));
        q.setEndTime(now.plusSeconds(1));
        var response = availability.unavailableWindows(q);
        require(response != null && response.isOk() && response.getData() != null, "TPM实时设备窗口不可核验");
        return response.getData().stream().filter(w -> !"DEVICE_STATE".equals(w.getSourceType())).map(w -> w.getSourceType() + ":" + w.getReason()).distinct().toList();
    }

    private boolean eligible(MdmDeviceEntity d, List<String> blockers) {
        return Boolean.TRUE.equals(d.getStatus()) && "2".equals(d.getDeviceState()) && "0".equals(d.getDeviceUsable()) && blockers.isEmpty();
    }

    private MdmDeviceEntity device(String code, String f, boolean lock) {
        var q = new LambdaQueryWrapper<MdmDeviceEntity>().eq(MdmDeviceEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MdmDeviceEntity::getFactoryCode, f).eq(MdmDeviceEntity::getDeviceCode, code);
        if (lock) q.last("FOR UPDATE");
        var rows = devices.selectList(q);
        require(rows.size() == 1, "当前工厂设备编码不存在或不唯一");
        return rows.get(0);
    }

    private Map<String, Object> facts(MdmDeviceEntity d) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", d.getId().toString());
        m.put("tenantId", d.getTenantId().toString());
        m.put("factoryCode", d.getFactoryCode());
        m.put("deviceCode", d.getDeviceCode());
        m.put("deviceState", d.getDeviceState());
        m.put("deviceUsable", d.getDeviceUsable());
        m.put("status", d.getStatus());
        return m;
    }

    private LambdaQueryWrapper<MdmDeviceRecoveryReceipt> scope(String f) {
        return new LambdaQueryWrapper<MdmDeviceRecoveryReceipt>().eq(MdmDeviceRecoveryReceipt::getTenantId, TenantContextHolder.getTenantId()).eq(MdmDeviceRecoveryReceipt::getFactoryCode, f);
    }

    private String access(String required) {
        var actor = SecurityUtils.getUser();
        String f = FactoryContextHolder.getFactoryCode();
        require(actor != null && actor.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(f), "设备恢复须实名选择实际工厂");
        var fs = factories.selectList(new LambdaQueryWrapper<MdmFactoryModelEntity>().eq(MdmFactoryModelEntity::getCode, f).eq(MdmFactoryModelEntity::getType, "FACTORY").eq(MdmFactoryModelEntity::getStatus, true));
        require(fs.size() == 1, "实际工厂无效或未启用");
        var grants = bindings.selectList(new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>().eq(MdmUserFactoryModelBindEntity::getUserId, actor.getId()).eq(MdmUserFactoryModelBindEntity::getFactoryModelId, fs.get(0).getId()).eq(MdmUserFactoryModelBindEntity::getStatus, true));
        var permission = FactoryModelPermissionEnum.getByCode(required);
        require(grants.stream().anyMatch(b -> {
            var p = FactoryModelPermissionEnum.getByCode(Objects.toString(b.getPermissionType(), "").toLowerCase(Locale.ROOT));
            return p != null && p.hasPermission(permission);
        }), "设备恢复实际工厂权限不足");
        return f;
    }

    private boolean text(String s, int n) {
        return StrUtil.isNotBlank(s) && s.length() <= n;
    }

    private String json(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception ex) {
            throw new CheckedException("设备恢复来源无法完整序列化");
        }
    }

    private String hash(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
