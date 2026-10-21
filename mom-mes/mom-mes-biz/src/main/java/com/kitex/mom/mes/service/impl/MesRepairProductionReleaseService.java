package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmDeviceRecoveryEventEvidence;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmDeviceRecoveryEventService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.dto.MesDeviceGateDecision;
import com.kitex.mom.mes.dto.MesRepairProductionReleaseCommand;
import com.kitex.mom.mes.entity.MesRepairArrangeStop;
import com.kitex.mom.mes.entity.MesRepairProductionRelease;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesRepairArrangeStopMapper;
import com.kitex.mom.mes.mapper.MesRepairProductionReleaseMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesDeviceGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 维修报修停工后的独立生产放行；只解除逻辑门，不开机或自动复工。
 */
@Service
@RequiredArgsConstructor
public class MesRepairProductionReleaseService {
    private final MesRepairArrangeStopMapper stops;
    private final MesRepairProductionReleaseMapper releases;
    private final MesTaskArrangeMapper arranges;
    private final RemoteMdmDeviceRecoveryEventService recoveryService;
    private final RemoteMdmFactoryAccessService factories;
    private final MesDeviceGateService deviceGate;
    private final MesRepairResumeEventService resumeEvents;

    private static void require(boolean okay, String message) {
        if (!okay) throw new CheckedException(message);
    }

    /**
     * 仅用于“已锁原派工、确认原号无成功回执、尚未执行INSERT”的确定业务拒绝。
     * 异常必须越过@Transactional代理完成回滚，再由本控制器编码；不用于依赖异常、写入失败或回执序列化失败。
     */
    public static final class CommandNotAcceptedException extends CheckedException {
        private final Map<String, String> outcome;

        private CommandNotAcceptedException(String message, MesRepairProductionReleaseCommand command) {
            super(message);
            outcome = Map.of("contract", "MES_REPAIR_RELEASE_COMMAND_RESULT_V1", "outcome", "REJECTED_BEFORE_INSERT",
                    "requestId", command.getRequestId(), "stopId", command.getStopId().toString(),
                    "tenantId", TenantContextHolder.getTenantId().toString(), "factoryCode", FactoryContextHolder.getFactoryCode(),
                    "operatorId", SecurityUtils.getUser().getId().toString(), "action", command.getAction(),
                    "expectedFingerprint", command.getExpectedFingerprint());
        }

        public Map<String, String> getOutcome() {
            return outcome;
        }
    }

    /** 调用位置严格位于去重之后、首次写入之前；其他require仍是结果未知的一般错误。 */
    private static void requireBeforeInsert(boolean okay, MesRepairProductionReleaseCommand command, String message) {
        if (!okay) throw new CommandNotAcceptedException(message, command);
    }

    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    @Transactional(readOnly = true)
    public com.baomidou.mybatisplus.core.metadata.IPage<MesRepairArrangeStop> page(long current, long size, String arrangeCode, String deviceCode, boolean exactArrange) {
        String factory = access("read");
        require(current >= 1 && size >= 1 && size <= 100, "停工账页码或页大小无效");
        require(!exactArrange || StrUtil.isNotBlank(arrangeCode), "精确派工查看必须提供原派工号");
        var query = new LambdaQueryWrapper<MesRepairArrangeStop>()
                .eq(MesRepairArrangeStop::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairArrangeStop::getFactoryCode, factory)
                .like(!exactArrange && StrUtil.isNotBlank(arrangeCode), MesRepairArrangeStop::getArrangeCode, StrUtil.trim(arrangeCode))
                .eq(exactArrange && StrUtil.isNotBlank(arrangeCode), MesRepairArrangeStop::getArrangeCode, StrUtil.trim(arrangeCode))
                .eq(StrUtil.isNotBlank(deviceCode), MesRepairArrangeStop::getDeviceCode, StrUtil.trim(deviceCode))
                .orderByDesc(MesRepairArrangeStop::getId);
        return stops.selectPage(new Page<>(current, size), query);
    }

    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    @Transactional(readOnly = true)
    public Map<String, Object> view(Long stopId, long current) {
        String factory = access("read");
        require(current >= 1 && current <= 100000, "页码无效");
        MesRepairArrangeStop stop = stop(stopId, factory, false);
        MesTaskArrangeEntity arrange = arrange(stop, false);
        boolean currentStop = Objects.equals(latestStop(arrange).getId(), stopId);
        MesRepairProductionRelease latest = latest(stop.getId());
        MdmDeviceRecoveryEventEvidence recovery = latestRecovery(stop);
        boolean qualified = qualified(stop, recovery);
        MesDeviceGateDecision readiness = qualified ? deviceGate.checkDeviceStart(stop.getDeviceCode(), worksite(arrange), factory) : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stop", stop);
        result.put("arrange", arrange);
        result.put("recovery", recovery);
        result.put("currentStop", currentStop);
        result.put("recoveryQualified", qualified);
        result.put("deviceReadiness", readiness);
        result.put("latest", latest);
        result.put("fingerprint", fingerprint(evidence(stop, arrange, recovery)));
        var writeback = resumeEvents.byStop(stopId, factory);
        result.put("planWriteback", writeback);
        result.put("planWritebackHistory", resumeEvents.history(writeback == null ? null : writeback.getId(), factory));
        result.put("allowed", currentStop && qualified && latest != null && "APPROVE".equals(latest.getAction())
                && Objects.equals(latest.getEvidenceFingerprint(), result.get("fingerprint"))
                && readiness != null && Boolean.TRUE.equals(readiness.getAllowed()));
        result.put("history", releases.selectPage(new Page<>(current, 20), new LambdaQueryWrapper<MesRepairProductionRelease>()
                .eq(MesRepairProductionRelease::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairProductionRelease::getFactoryCode, factory).eq(MesRepairProductionRelease::getStopId, stopId)
                .orderByDesc(MesRepairProductionRelease::getId)));
        return result;
    }

    @PreAuthorize("@pms.hasPermission('mesRepairRelease:request')")
    @Transactional(rollbackFor = Exception.class)
    public MesRepairProductionRelease request(Long stopId, MesRepairProductionReleaseCommand command) {
        require(command != null && "REQUEST".equals(command.getAction()), "此入口只接受复产申请");
        return execute(stopId, command);
    }

    @PreAuthorize("@pms.hasPermission('mesRepairRelease:approve')")
    @Transactional(rollbackFor = Exception.class)
    public MesRepairProductionRelease decide(Long stopId, MesRepairProductionReleaseCommand command) {
        require(command != null && Set.of("APPROVE", "REJECT", "REVOKE").contains(command.getAction()), "复产决策无效");
        return execute(stopId, command);
    }

    private MesRepairProductionRelease execute(Long stopId, MesRepairProductionReleaseCommand command) {
        String factory = access("write");
        require(command.getStopId() != null && Objects.equals(command.getStopId(), stopId)
                        && command.getRequestId() != null && command.getRequestId().matches("[A-Za-z0-9_-]{16,64}")
                        && text(command.getReason(), 1000) && command.getExpectedFingerprint() != null
                        && command.getExpectedFingerprint().matches("[a-f0-9]{64}"),
                "须提交本次停工号、永久请求号、现场依据及当前事实指纹");
        MesRepairArrangeStop stop = stop(stopId, factory, false);
        MesTaskArrangeEntity arrange = arrange(stop, true); // 与开工/报工共用派工锁。
        stop = stop(stopId, factory, true);
        var user = SecurityUtils.getUser();
        String commandHash = fingerprint(Arrays.asList(stopId, user.getId(), command));
        var duplicate = releases.selectOne(new LambdaQueryWrapper<MesRepairProductionRelease>()
                .eq(MesRepairProductionRelease::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairProductionRelease::getRequestId, command.getRequestId()));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getStopId(), stopId)
                    && Objects.equals(duplicate.getCommandFingerprint(), commandHash), "请求号已用于其他复产命令");
            return duplicate;
        }
        // 永久原号回执优先于可变现场门；未查原号前绝不能声明“未落账”。
        MesRepairArrangeStop currentStop = latestStop(arrange);
        requireBeforeInsert(currentStop != null && Objects.equals(currentStop.getId(), stopId), command,
                "已有更新的维修停工，旧停工不可再办理复产");
        MesRepairProductionRelease previous = latest(stopId);
        requireBeforeInsert(Objects.equals(command.getExpectedLastId(), previous == null ? null : previous.getId()), command, "复产决策已变化，请刷新");
        MdmDeviceRecoveryEventEvidence recovery = latestRecovery(stop);
        String evidenceHash = fingerprint(evidence(stop, arrange, recovery));
        requireBeforeInsert(Objects.equals(command.getExpectedFingerprint(), evidenceHash), command, "维修恢复回执或派工事实已变化，请刷新并重新核验");
        String action = command.getAction();
        if ("REQUEST".equals(action)) {
            requireBeforeInsert("PAUSE".equals(arrange.getStatus()), command, "仅维修停工后仍暂停的派工可申请复产");
            requireBeforeInsert(qualified(stop, recovery), command, "须先完成晚于本次停工的MDM维修资格恢复，且设备当前仍可用");
            requireBeforeInsert(ready(stop, arrange, factory), command, "设备或TPM实时生产门仍阻断复产申请");
            requireBeforeInsert(previous == null || !Set.of("REQUEST", "APPROVE").contains(previous.getAction())
                    || !Objects.equals(previous.getEvidenceFingerprint(), evidenceHash), command, "当前事实已有申请或有效批准");
            requireBeforeInsert(checked(command), command, "申请人须确认现场、设备、物料质量和人员四项条件");
        } else if ("APPROVE".equals(action) || "REJECT".equals(action)) {
            requireBeforeInsert(previous != null && "REQUEST".equals(previous.getAction()), command, "仅待审批申请可批准或驳回");
            requireBeforeInsert(!Objects.equals(previous.getOperatorId(), user.getId()), command, "申请人不能审批自己的复产申请");
            if ("APPROVE".equals(action)) {
                requireBeforeInsert("PAUSE".equals(arrange.getStatus()) && qualified(stop, recovery)
                        && Objects.equals(previous.getEvidenceFingerprint(), evidenceHash), command, "派工或MDM恢复事实已变化，旧申请不可批准");
                requireBeforeInsert(!Objects.equals(recovery.getActorId(), user.getId()), command, "MDM设备资格恢复人不能自行批准MES复产");
                requireBeforeInsert(ready(stop, arrange, factory), command, "设备或TPM实时生产门仍阻断独立审批");
                requireBeforeInsert(checked(command), command, "审批人须独立确认全部四项现场条件");
            }
        } else {
            requireBeforeInsert(previous != null && "APPROVE".equals(previous.getAction()), command, "仅有效批准可撤回");
            requireBeforeInsert("PAUSE".equals(arrange.getStatus()), command, "运行中须先走现场停机和MES暂停，再撤回复产批准");
        }
        var result = new MesRepairProductionRelease();
        result.setTenantId(stop.getTenantId());
        result.setFactoryCode(factory);
        result.setStopId(stopId);
        result.setArrangeId(arrange.getId());
        result.setPreviousId(previous == null ? null : previous.getId());
        result.setAction(action);
        result.setRequestId(command.getRequestId());
        result.setCommandFingerprint(commandHash);
        result.setEvidenceFingerprint(evidenceHash);
        result.setEvidenceJson(JSONUtil.toJsonStr(evidence(stop, arrange, recovery)));
        result.setChecksJson(JSONUtil.toJsonStr(Arrays.asList(command.getSiteChecked(), command.getEquipmentChecked(),
                command.getMaterialQualityChecked(), command.getPersonnelChecked())));
        result.setOperatorId(user.getId());
        result.setOperatorName(user.getName());
        result.setReason(command.getReason().trim());
        result.setOperationTime(LocalDateTime.now());
        require(releases.insert(result) == 1, "维修复产履历保存失败");
        return result;
    }

    /**
     * 开工与人工/自动报工共用；调用方已锁定派工并取得实际租户工厂上下文。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAllowed(MesTaskArrangeEntity arrange, String stage) {
        previewAllowed(arrange, stage);
    }

    /** 只读核对真实停工、恢复回执和原复产批准；不发起恢复或批准命令。 */
    public void previewAllowed(MesTaskArrangeEntity arrange, String stage) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && Objects.equals(TenantContextHolder.getTenantId(), arrange.getTenantId())
                && Objects.equals(FactoryContextHolder.getFactoryCode(), arrange.getFactoryCode()), "维修复产门缺少实际租户/工厂上下文");
        var stop = latestStop(arrange);
        if (stop == null) return; // 没有第135节真实停工事实的旧派工不能倒填审批。
        var latest = latest(stop.getId());
        var recovery = latestRecovery(stop);
        require(qualified(stop, recovery) && latest != null && "APPROVE".equals(latest.getAction())
                        && Objects.equals(latest.getArrangeId(), arrange.getId())
                        && Objects.equals(latest.getEvidenceFingerprint(), fingerprint(evidence(stop, arrange, recovery))),
                "维修停工派工未取得当前MDM恢复回执对应的异岗复产批准，阻断" + stage);
    }

    /** 纯读保留停工、MDM恢复回执及当前复产批准的精确身份/指纹。 */
    public Map<String, Object> previewStartEvidence(MesTaskArrangeEntity arrange) {
        require(Objects.equals(TenantContextHolder.getTenantId(), arrange.getTenantId())
                && Objects.equals(FactoryContextHolder.getFactoryCode(), arrange.getFactoryCode())
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "维修观察缺少实际范围");
        var stop = latestStop(arrange);
        if (stop == null) return Map.of("applicable", false, "approved", true, "reason", "无真实维修停工关联，不倒填审批");
        var release = latest(stop.getId());
        var recovery = latestRecovery(stop);
        boolean approved = qualified(stop, recovery) && release != null && "APPROVE".equals(release.getAction())
                && Objects.equals(release.getArrangeId(), arrange.getId())
                && Objects.equals(release.getEvidenceFingerprint(), fingerprint(evidence(stop, arrange, recovery)));
        return Map.of("applicable", true, "approved", approved, "stopId", stop.getId().toString(),
                "releaseId", release == null ? "" : release.getId().toString(),
                "evidenceFingerprint", release == null ? "" : Objects.toString(release.getEvidenceFingerprint(), ""),
                "source", evidence(stop, arrange, recovery));
    }

    private String access(String permission) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(factory), "维修复产须实名选择实际工厂");
        var grant = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission(permission));
        require(grant != null && grant.isOk() && Boolean.TRUE.equals(grant.getData()), "维修复产实际工厂权限不足或MDM不可核验");
        return factory;
    }

    private MesRepairArrangeStop stop(Long id, String factory, boolean lock) {
        require(id != null, "请选择报修停工记录");
        var row = stops.selectOne(new LambdaQueryWrapper<MesRepairArrangeStop>()
                .eq(MesRepairArrangeStop::getId, id).eq(MesRepairArrangeStop::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairArrangeStop::getFactoryCode, factory).last(lock ? "FOR UPDATE" : ""));
        require(row != null, "当前工厂报修停工记录不存在");
        return row;
    }

    private MesTaskArrangeEntity arrange(MesRepairArrangeStop stop, boolean lock) {
        var row = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, stop.getArrangeId()).eq(MesTaskArrangeEntity::getTenantId, stop.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, stop.getFactoryCode()).last(lock ? "FOR UPDATE" : ""));
        require(row != null && Objects.equals(row.getCode(), stop.getArrangeCode())
                && Objects.equals(row.getPlannedDeviceCode(), stop.getDeviceCode()), "停工派工已不存在或设备归属改变");
        return row;
    }

    private MesRepairArrangeStop latestStop(MesTaskArrangeEntity arrange) {
        return stops.selectOne(new LambdaQueryWrapper<MesRepairArrangeStop>()
                .eq(MesRepairArrangeStop::getTenantId, arrange.getTenantId()).eq(MesRepairArrangeStop::getFactoryCode, arrange.getFactoryCode())
                .eq(MesRepairArrangeStop::getArrangeId, arrange.getId()).orderByDesc(MesRepairArrangeStop::getId).last("LIMIT 1"));
    }

    private MesRepairProductionRelease latest(Long stopId) {
        return releases.selectOne(new LambdaQueryWrapper<MesRepairProductionRelease>()
                .eq(MesRepairProductionRelease::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairProductionRelease::getStopId, stopId).orderByDesc(MesRepairProductionRelease::getId).last("LIMIT 1"));
    }

    private MdmDeviceRecoveryEventEvidence latestRecovery(MesRepairArrangeStop stop) {
        var response = recoveryService.latest(stop.getDeviceCode(), stop.getFactoryCode());
        require(response != null && response.isOk(), "MDM最新恢复回执不可核验，维修复产按阻断处理");
        return response.getData();
    }

    private boolean qualified(MesRepairArrangeStop stop, MdmDeviceRecoveryEventEvidence recovery) {
        return recovery != null && Objects.equals(recovery.getTenantId(), stop.getTenantId())
                && Objects.equals(recovery.getFactoryCode(), stop.getFactoryCode())
                && Objects.equals(recovery.getDeviceCode(), stop.getDeviceCode())
                && recovery.getReceiptId() != null && Objects.equals(recovery.getReceiptId(), recovery.getLatestRecoveryReceiptId())
                && recovery.getRecoveryTime() != null && recovery.getRecoveryTime().isAfter(stop.getStopTime())
                && recovery.getActorId() != null && Boolean.TRUE.equals(recovery.getDeviceStatus())
                && "1".equals(recovery.getDeviceUsable())
                && Set.of("0", "4", "5").contains(Objects.toString(recovery.getDeviceState(), ""));
    }

    private boolean ready(MesRepairArrangeStop stop, MesTaskArrangeEntity arrange, String factory) {
        var decision = deviceGate.checkDeviceStart(stop.getDeviceCode(), worksite(arrange), factory);
        return decision != null && Boolean.TRUE.equals(decision.getAllowed());
    }

    private String worksite(MesTaskArrangeEntity arrange) {
        return StrUtil.blankToDefault(arrange.getActualWorksiteCode(), arrange.getPlannedWorksiteCode());
    }

    private Map<String, Object> evidence(MesRepairArrangeStop stop, MesTaskArrangeEntity arrange, MdmDeviceRecoveryEventEvidence recovery) {
        var result = new LinkedHashMap<String, Object>();
        result.put("version", "MES_REPAIR_PRODUCTION_RELEASE_V1");
        result.put("stopId", stop.getId());
        result.put("repairTaskId", stop.getRepairTaskId());
        result.put("stopTime", stop.getStopTime());
        result.put("segmentId", stop.getSegmentId());
        result.put("segmentMode", stop.getSegmentMode());
        result.put("tenantId", stop.getTenantId());
        result.put("factoryCode", stop.getFactoryCode());
        result.put("arrangeId", arrange.getId());
        result.put("arrangeCode", arrange.getCode());
        result.put("taskCode", arrange.getTaskCode());
        result.put("produceTaskCode", arrange.getProduceTaskCode());
        result.put("workerCode", arrange.getWorkerCode());
        result.put("actualWorksiteCode", arrange.getActualWorksiteCode());
        result.put("worksiteCodes", arrange.getWorksiteCodes());
        result.put("plannedWorksiteCode", arrange.getPlannedWorksiteCode());
        result.put("plannedDeviceCode", arrange.getPlannedDeviceCode());
        result.put("plannedToolingCodes", arrange.getPlannedToolingCodes());
        result.put("apsOperationFingerprint", fingerprint(Objects.toString(arrange.getApsOperationSnapshot(), "")));
        result.put("recoveryReceiptId", recovery == null ? null : recovery.getReceiptId());
        result.put("recoveryTime", recovery == null ? null : recovery.getRecoveryTime());
        result.put("recoverySourceFingerprint", recovery == null ? null : recovery.getSourceFingerprint());
        result.put("recoveryActorId", recovery == null ? null : recovery.getActorId());
        return result;
    }

    private boolean checked(MesRepairProductionReleaseCommand c) {
        return Boolean.TRUE.equals(c.getSiteChecked()) && Boolean.TRUE.equals(c.getEquipmentChecked())
                && Boolean.TRUE.equals(c.getMaterialQualityChecked()) && Boolean.TRUE.equals(c.getPersonnelChecked());
    }

    private boolean text(String value, int max) {
        return StrUtil.isNotBlank(value) && value.length() <= max;
    }

    private String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
