package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MesChangePointHandleServiceImpl extends ServiceImpl<MesChangePointHandleMapper, MesChangePointHandleEntity>
        implements MesChangePointHandleService {

    private static final String WAIT_ACCEPT = "0";
    private static final String HANDLING = "A";
    private static final String WAIT_VERIFY = "V";
    private static final String WAIT_RELEASE = "R";
    private static final String MONITORING = "M";
    private static final String CLOSED = "1";
    private static final String CANCELLED = "9";
    private static final String PASS = "PASS";
    private static final String REJECT = "REJECT";
    private static final Set<String> SOURCES = Set.of("MANUAL", "IOT", "MES", "MDM", "QMS", "TPM", "INTEGRATION");
    private static final Set<String> LOSS_CATEGORIES = Set.of(
            "SCRAP", "REWORK", "DOWNTIME", "MATERIAL", "QUALITY", "OTHER");

    private final MesChangePointConfigService configService;
    private final MesChangePointLevelConfigService levelConfigService;
    private final MesChangePointOperationService operationService;
    private final MesChangePointNotifyService notifyService;
    private final MesQualityGateService qualityGateService;
    private final MesDeviceGateService deviceGateService;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesProduceTaskService produceTaskService;
    private final MesActualFactoryAccessService factoryAccess;
    private final PlatformTransactionManager transactionManager;

    @Override
    public IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> page,
                                                       MesChangePointHandleEntity query) {
        require(query != null, "变化点查询条件不能为空");
        boolean hasStart = query.getQueryStartTime() != null;
        boolean hasEnd = query.getQueryEndTime() != null;
        require(hasStart == hasEnd, "损失明细追溯必须同时提供开始和结束时间");
        if (hasStart) {
            require(query.getQueryStartTime().isBefore(query.getQueryEndTime()), "损失明细追溯开始时间必须早于结束时间");
            require(Duration.between(query.getQueryStartTime(), query.getQueryEndTime()).toDays() <= 366,
                    "单次损失明细追溯不能超过366天");
        }
        if (StrUtil.isNotBlank(query.getLossCategory())) {
            require("NONE".equals(query.getLossCategory()) || LOSS_CATEGORIES.contains(query.getLossCategory()),
                    "损失类型查询条件无效");
        }
        query.setFactoryCode(factoryAccess.require("read"));
        return baseMapper.queryPage(page, query);
    }

    @Override
    public MesChangePointHandleEntity getDetail(Long id) {
        require(id != null, "变化点事件不能为空");
        String factory = factoryAccess.require("read");
        MesChangePointHandleEntity record = getOne(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getId, id)
                .eq(MesChangePointHandleEntity::getFactoryCode, factory).last("LIMIT 1"), false);
        require(record != null, "当前实际工厂内未找到变化点事件");
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesChangePointHandleEntity trigger(ChangePointTriggerCommand command) {
        require(command != null, "变化点触发参数不能为空");
        require(StrUtil.isNotBlank(command.getConfigCode()), "变化点配置编码不能为空");
        require(StrUtil.isNotBlank(command.getRequestId()), "来源请求号不能为空");
        String source = StrUtil.blankToDefault(command.getTriggerSource(), "MANUAL").toUpperCase();
        require(SOURCES.contains(source), "变化点触发来源无效");
        String factory = factoryAccess.require("write");
        MesChangePointHandleEntity existing = getOne(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getRequestId, command.getRequestId())
                .eq(MesChangePointHandleEntity::getFactoryCode, factory).last("LIMIT 1"), false);
        if (existing != null) return existing;

        MesChangePointConfigEntity config = configService.getOne(
                new LambdaQueryWrapper<MesChangePointConfigEntity>()
                        .eq(MesChangePointConfigEntity::getConfigCode, command.getConfigCode())
                        .eq(MesChangePointConfigEntity::getFactoryCode, factory)
                        .eq(MesChangePointConfigEntity::getEnabled, Boolean.TRUE).last("LIMIT 1"), false);
        require(config != null, "变化点配置不存在或未启用");
        MesTaskArrangeEntity arrange = resolveArrange(command, factory);
        TriggerContext context = buildContext(command, arrange, factory);
        validateScope(config, context);
        boolean releaseRequired = !"NOTICE".equals(config.getControlMode());
        require(!releaseRequired || StrUtil.isNotBlank(context.arrangeCode())
                        || StrUtil.isNotBlank(context.produceTaskCode()) || StrUtil.isNotBlank(context.taskCode()),
                "生产阻断变化点必须关联派工、工序任务或生产工单，不能创建无执行对象的阻断事件");
        require(!releaseRequired || StrUtil.isNotBlank(config.getProductionReleaseRole()),
                "生产控制配置缺少恢复生产审批角色，不能触发阻断事件");
        require(!Boolean.TRUE.equals(config.getAutoPauseRequired()) || arrange != null,
                "配置要求联动暂停时必须传入唯一派工编码，不能根据工单或工序任务猜测现场执行对象");
        MesChangePointLevelConfigEntity level = levelConfigService.getOne(
                new LambdaQueryWrapper<MesChangePointLevelConfigEntity>()
                        .eq(MesChangePointLevelConfigEntity::getConfigCode, config.getConfigCode())
                        .eq(MesChangePointLevelConfigEntity::getFactoryCode, factory)
                        .eq(MesChangePointLevelConfigEntity::getEnabled, Boolean.TRUE).last("LIMIT 1"), false);
        BigDecimal effectiveUpperLimit = level != null && level.getUpperLimit() != null
                ? level.getUpperLimit() : config.getUpperLimit();
        BigDecimal effectiveLowerLimit = level != null && level.getLowerLimit() != null
                ? level.getLowerLimit() : config.getLowerLimit();
        validateTriggerValue(effectiveLowerLimit, effectiveUpperLimit, command.getActualValue(), source);

        LocalDateTime now = LocalDateTime.now();
        MesChangePointHandleEntity record = new MesChangePointHandleEntity()
                .setEventCode("CP-EVT-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(command.getRequestId()).setConfigCode(config.getConfigCode())
                .setConfigName(config.getName()).setConfigContent(config.getContent())
                .setConfigLevel(level == null ? StrUtil.blankToDefault(config.getLevel(), "GENERAL")
                        : StrUtil.blankToDefault(level.getLevel(), "GENERAL"))
                .setUpperLimit(effectiveUpperLimit).setLowerLimit(effectiveLowerLimit)
                .setTransferCode(command.getTransferCode()).setTaskCode(context.taskCode())
                .setProduceTaskCode(context.produceTaskCode()).setArrangeCode(context.arrangeCode())
                .setLineCode(context.lineCode()).setRouteCode(context.routeCode())
                .setProductCode(context.productCode()).setDeviceCode(command.getDeviceCode())
                .setWorksiteCode(context.worksiteCode()).setCategory(config.getCategory())
                .setMonitorField(config.getMonitorField()).setActualValue(command.getActualValue())
                .setActualSituation(command.getActualSituation()).setTriggerSource(source)
                .setControlMode(config.getControlMode()).setInspectionRequired(config.getInspectionRequired())
                .setVerificationRequired(config.getIsVerify())
                .setProductionReleaseRequired(releaseRequired)
                .setProductionReleaseRole(config.getProductionReleaseRole())
                .setAutoPauseRequired(config.getAutoPauseRequired())
                .setProductionPaused(false).setPausedByChangePoint(false)
                .setWipExposureQty(wipExposure(arrange))
                .setIntensifiedInspectionRequired(config.getIntensifiedInspectionRequired())
                .setIntensifiedInspectionIntervalMin(config.getIntensifiedInspectionIntervalMin())
                .setIntensifiedInspectionPassCount(config.getIntensifiedInspectionPassCount())
                .setIntensifiedInspectionPassedCount(0).setIntensifiedInspectionGeneratedCount(0)
                .setHandleRole(level != null && StrUtil.isNotBlank(level.getVerifyRole())
                        ? level.getVerifyRole() : config.getVerifyRole())
                .setAlertTime(now).setTriggerTime(now)
                .setAlertLevel(level == null ? StrUtil.blankToDefault(config.getLevel(), "GENERAL")
                        : StrUtil.blankToDefault(level.getLevel(), "GENERAL"))
                .setHandled("0").setStatus(WAIT_ACCEPT)
                .setResponseDeadline(now.plusMinutes(config.getResponseSlaMin()))
                .setHandleDeadline(now.plusMinutes(config.getHandleSlaMin()))
                .setEscalationCount(0).setNextEscalationTime(now.plusMinutes(config.getResponseSlaMin()))
                .setFactoryCode(factory);
        save(record);
        saveOperation(record, command.getRequestId(), "TRIGGER", null, WAIT_ACCEPT,
                "来源=" + source + "；实际情况=" + StrUtil.nullToEmpty(command.getActualSituation()));

        ChangePointNotifyResult notified = notifyService.notifyHandlers(config, record);
        record.setAssignedUserCodes(notified.getRecipientCodes()).setAssignedUserNames(notified.getRecipientNames())
                .setNoticePersonCode(notified.getRecipientCodes()).setNoticePersonName(notified.getRecipientNames())
                .setLastNotifyChannels(notified.getChannels()).setLastNotifyResult(notified.getResult());
        saveOperation(record, IdUtil.fastSimpleUUID(), "NOTIFY", WAIT_ACCEPT, WAIT_ACCEPT,
                "通道=" + notified.getChannels() + "；结果=" + notified.getResult() + "；说明=" + notified.getDetail());

        if (Boolean.TRUE.equals(config.getInspectionRequired())) {
            require(StrUtil.isNotBlank(record.getProduceTaskCode()), "要求首检的变化点必须关联工序任务");
            MesQualityGateDecision decision = qualityGateService.createChangePointFirstInspection(
                    record.getProduceTaskCode(), "CHANGE_POINT:" + record.getEventCode(),
                    config.getConfigCode(), config.getName());
            applyInspection(record, decision);
            saveOperation(record, IdUtil.fastSimpleUUID(), "CREATE_INSPECTION", WAIT_ACCEPT, WAIT_ACCEPT,
                    decision.getMessage());
        }
        updateById(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void accept(ChangePointAcceptCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        MesChangePointHandleEntity record = scopedLock(command.getId(), "write");
        String requestId = requestId(command.getRequestId());
        if (hasOperation(record.getId(), requestId)) return;
        require(WAIT_ACCEPT.equals(record.getStatus()), "仅待接收事件可以接收");
        MomUser user = currentUser();
        requireAuthorized(record.getAssignedUserCodes(), user, "当前用户不在本事件处置责任人中");
        record.setAcceptPersonCode(user.getUsername()).setAcceptPersonName(user.getName())
                .setAcceptTime(LocalDateTime.now()).setStatus(HANDLING)
                .setNextEscalationTime(record.getHandleDeadline());
        updateById(record);
        saveOperation(record, requestId, "ACCEPT", WAIT_ACCEPT, HANDLING, command.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(ChangePointHandleCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        require(StrUtil.isNotBlank(command.getRootCause()), "根本原因不能为空");
        require(StrUtil.isNotBlank(command.getCorrectiveAction()), "纠正措施不能为空");
        require(StrUtil.isNotBlank(command.getHandleDesc()), "处置说明不能为空");
        require(command.getExpectedCloseTime() != null && command.getExpectedCloseTime().isAfter(LocalDateTime.now()),
                "预计关闭时间必须晚于当前时间");
        validateWipDisposition(command);
        MesChangePointHandleEntity record = scopedLock(command.getId(), "write");
        String requestId = requestId(command.getRequestId());
        if (hasOperation(record.getId(), requestId)) return;
        require(WAIT_ACCEPT.equals(record.getStatus()) || HANDLING.equals(record.getStatus()),
                "仅待接收或处理中事件可以提交处置");
        MomUser user = currentUser();
        requireAuthorized(record.getAssignedUserCodes(), user, "当前用户不在本事件处置责任人中");
        String before = record.getStatus();
        if (record.getAcceptTime() == null) {
            record.setAcceptPersonCode(user.getUsername()).setAcceptPersonName(user.getName())
                    .setAcceptTime(LocalDateTime.now());
        }
        record.setRootCause(command.getRootCause()).setCorrectiveAction(command.getCorrectiveAction())
                .setPreventiveAction(command.getPreventiveAction()).setExpectedCloseTime(command.getExpectedCloseTime())
                .setHandleDesc(command.getHandleDesc()).setActualSituation(
                        StrUtil.blankToDefault(record.getActualSituation(), command.getHandleDesc()))
                .setAffectedWipQty(command.getAffectedWipQty())
                .setReleasedWipQty(command.getReleasedWipQty())
                .setReworkWipQty(command.getReworkWipQty())
                .setScrapWipQty(command.getScrapWipQty())
                .setLossAmount(command.getLossAmount())
                .setLossCategory(StrUtil.isBlank(command.getLossCategory()) ? null
                        : command.getLossCategory().trim().toUpperCase())
                .setLossRemark(StrUtil.trim(command.getLossRemark()))
                .setHandlePersonCode(user.getUsername()).setHandlePersonName(user.getName())
                .setHandleBy(user.getUsername()).setHandleTime(LocalDateTime.now())
                .setVerificationResult(null).setVerificationRemark(null).setVerifierCode(null)
                .setVerifierName(null).setVerificationTime(null)
                .setReleaseDecision(null).setReleaseRemark(null).setReleaseApproverCode(null)
                .setReleaseApproverName(null).setReleaseTime(null).setReleaseChecks(null)
                .setReleaseEvidenceSnapshot(null).setReleaseEvidenceFingerprint(null)
                .setReleaseDeadline(null).setReleaseApproverUserCodes(null).setReleaseApproverUserNames(null)
                .setIntensifiedInspectionPassedCount(0).setIntensifiedInspectionGeneratedCount(0)
                .setIntensifiedInspectionRecordId(null).setIntensifiedInspectionId(null)
                .setIntensifiedInspectionStatus(null).setIntensifiedInspectionNextTime(null)
                .setIntensifiedInspectionLastTime(null).setMonitoringStartTime(null).setMonitoringEndTime(null)
                .setCloseTime(null).setCloseReason(null).setHandled("0");

        if (Boolean.TRUE.equals(record.getVerificationRequired())) {
            record.setStatus(WAIT_VERIFY);
            MesChangePointConfigEntity config = requireConfig(record);
            record.setNextEscalationTime(LocalDateTime.now().plusMinutes(config.getResponseSlaMin()));
            ChangePointNotifyResult notified = notifyService.notifyVerifiers(config, record);
            record.setVerifierUserCodes(notified.getRecipientCodes()).setVerifierUserNames(notified.getRecipientNames())
                    .setLastNotifyChannels(notified.getChannels()).setLastNotifyResult(notified.getResult());
            saveOperation(record, IdUtil.fastSimpleUUID(), "NOTIFY_VERIFY", WAIT_VERIFY, WAIT_VERIFY,
                    "通道=" + notified.getChannels() + "；结果=" + notified.getResult() + "；说明=" + notified.getDetail());
        } else {
            record.setStatus(CLOSED).setHandled("1").setCloseTime(LocalDateTime.now())
                    .setCloseReason("配置无需独立验证，处置提交后闭环").setNextEscalationTime(null);
        }
        updateById(record);
        saveOperation(record, requestId, "HANDLE", before, record.getStatus(),
                "根因=" + command.getRootCause() + "；纠正措施=" + command.getCorrectiveAction()
                        + "；预防措施=" + StrUtil.nullToEmpty(command.getPreventiveAction())
                        + "；预计关闭=" + command.getExpectedCloseTime()
                        + "；在制分配=" + command.getAffectedWipQty() + "=" + command.getReleasedWipQty()
                        + "+" + command.getReworkWipQty() + "+" + command.getScrapWipQty()
                        + "；损失=" + command.getLossAmount() + "/"
                        + StrUtil.blankToDefault(command.getLossCategory(), "NONE")
                        + "；说明=" + command.getHandleDesc());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verify(ChangePointVerifyCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        require(PASS.equals(command.getVerificationResult()) || REJECT.equals(command.getVerificationResult()),
                "验证结果必须为PASS或REJECT");
        require(StrUtil.isNotBlank(command.getVerificationRemark()), "验证说明不能为空");
        MesChangePointHandleEntity record = scopedLock(command.getId(), "write");
        String requestId = requestId(command.getRequestId());
        if (hasOperation(record.getId(), requestId)) return;
        require(WAIT_VERIFY.equals(record.getStatus()), "仅待验证事件可以验证");
        MomUser user = currentUser();
        requireAuthorized(record.getVerifierUserCodes(), user, "当前用户不在本事件验证责任人中");
        if (PASS.equals(command.getVerificationResult()) && Boolean.TRUE.equals(record.getInspectionRequired())) {
            MesQualityGateDecision decision = qualityGateService.syncChangePointInspection(record.getInspectionRecordId());
            applyInspection(record, decision);
            require(Boolean.TRUE.equals(decision.getPassed()), "变化点首检未放行：" + decision.getMessage());
        }
        if (PASS.equals(command.getVerificationResult())) {
            assertWipDisposition(record);
        }
        record.setVerificationResult(command.getVerificationResult())
                .setVerificationRemark(command.getVerificationRemark()).setVerifierCode(user.getUsername())
                .setVerifierName(user.getName()).setVerificationTime(LocalDateTime.now());
        if (PASS.equals(command.getVerificationResult())) {
            if (Boolean.TRUE.equals(record.getProductionReleaseRequired())) {
                MesChangePointConfigEntity config = requireConfig(record);
                require(StrUtil.isNotBlank(record.getProductionReleaseRole()),
                        "变化点缺少冻结的恢复生产审批角色，不能解除生产门");
                LocalDateTime now = LocalDateTime.now();
                MesTaskArrangeEntity arrange = linkedArrange(record, false);
                Map<String, Object> evidence = releaseEvidence(record, arrange);
                record.setStatus(WAIT_RELEASE).setHandled("0").setCloseTime(null).setCloseReason(null)
                        .setReleaseDeadline(now.plusMinutes(config.getReleaseSlaMin()))
                        .setReleaseEvidenceSnapshot(JSONUtil.toJsonStr(evidence))
                        .setReleaseEvidenceFingerprint(fingerprint(evidence))
                        .setNextEscalationTime(now.plusMinutes(config.getReleaseSlaMin()));
                ChangePointNotifyResult notified = notifyService.notifyReleaseApprovers(config, record);
                require(StrUtil.isNotBlank(notified.getRecipientCodes()),
                        "恢复生产审批角色未解析到有效用户，验证结果未提交");
                record.setReleaseApproverUserCodes(notified.getRecipientCodes())
                        .setReleaseApproverUserNames(notified.getRecipientNames())
                        .setLastNotifyChannels(notified.getChannels()).setLastNotifyResult(notified.getResult());
                saveOperation(record, IdUtil.fastSimpleUUID(), "NOTIFY_RELEASE", WAIT_RELEASE, WAIT_RELEASE,
                        "审批期限=" + record.getReleaseDeadline() + "；通道=" + notified.getChannels()
                                + "；结果=" + notified.getResult() + "；说明=" + notified.getDetail());
            } else {
                record.setStatus(CLOSED).setHandled("1").setCloseTime(LocalDateTime.now())
                        .setCloseReason("处置验证通过").setNextEscalationTime(null);
            }
        } else {
            LocalDateTime now = LocalDateTime.now();
            record.setStatus(HANDLING).setHandled("0").setHandlePersonCode(null).setHandlePersonName(null)
                    .setHandleBy(null).setHandleTime(null).setHandleDesc(null).setRootCause(null)
                    .setCorrectiveAction(null).setPreventiveAction(null).setExpectedCloseTime(null)
                    .setReleaseDeadline(null).setReleaseApproverUserCodes(null).setReleaseApproverUserNames(null)
                    .setReleaseDecision(null).setReleaseRemark(null).setReleaseApproverCode(null)
                    .setReleaseApproverName(null).setReleaseTime(null).setReleaseChecks(null)
                    .setReleaseEvidenceSnapshot(null).setReleaseEvidenceFingerprint(null)
                    .setCloseTime(null).setCloseReason(null).setNextEscalationTime(
                            record.getHandleDeadline() != null && now.isBefore(record.getHandleDeadline())
                                    ? record.getHandleDeadline() : now.plusMinutes(30));
        }
        updateById(record);
        saveOperation(record, requestId, PASS.equals(command.getVerificationResult()) ? "VERIFY_PASS" : "VERIFY_REJECT",
                WAIT_VERIFY, record.getStatus(), command.getVerificationRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(ChangePointProductionReleaseCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        require("APPROVE".equals(command.getDecision()) || "REJECT".equals(command.getDecision()),
                "恢复生产审批结论必须为APPROVE或REJECT");
        require(StrUtil.isNotBlank(command.getRequestId()), "恢复生产审批请求号不能为空");
        require(StrUtil.isNotBlank(command.getRemark()), "恢复生产审批说明不能为空");
        require(StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()), "恢复生产审批事实指纹不能为空");

        // 先锁派工、后锁变化点，和开工/报工主链保持一致的锁顺序。
        MesChangePointHandleEntity snapshot = scopedRead(command.getId(), "write");
        if (hasOperation(snapshot.getId(), command.getRequestId())) return;
        MesTaskArrangeEntity arrange = linkedArrange(snapshot, true);
        MesChangePointHandleEntity record = scopedLock(command.getId(), "write");
        String requestId = requestId(command.getRequestId());
        if (hasOperation(record.getId(), requestId)) return;
        require(WAIT_RELEASE.equals(record.getStatus()), "仅待恢复生产审批事件可以审批");
        require(Boolean.TRUE.equals(record.getProductionReleaseRequired()), "当前事件不需要恢复生产审批");
        require(Objects.equals(command.getExpectedEvidenceFingerprint(), record.getReleaseEvidenceFingerprint()),
                "恢复生产事实已变化，请刷新后重新核对");

        MomUser user = currentUser();
        requireAuthorized(record.getReleaseApproverUserCodes(), user, "当前用户不在恢复生产审批责任人中");
        require(!Objects.equals(user.getUsername(), record.getAcceptPersonCode())
                        && !Objects.equals(user.getUsername(), record.getHandlePersonCode())
                        && !Objects.equals(user.getUsername(), record.getVerifierCode())
                        && !participated(record.getId(), user.getUsername()),
                "变化点触发、接收、处置或验证参与人不能批准本次恢复生产");

        String liveFingerprint = fingerprint(releaseEvidence(record, arrange));
        require(Objects.equals(liveFingerprint, record.getReleaseEvidenceFingerprint()),
                "验证后的派工、首检或现场关联事实已变化，须重新处置并验证");

        LocalDateTime now = LocalDateTime.now();
        String checks = "现场=" + yes(command.getSiteConditionChecked())
                + ";设备参数=" + yes(command.getEquipmentParameterChecked())
                + ";物料质量=" + yes(command.getMaterialQualityChecked())
                + ";人员标准=" + yes(command.getPersonnelStandardChecked());
        record.setReleaseDecision(command.getDecision()).setReleaseRemark(command.getRemark())
                .setReleaseApproverCode(user.getUsername()).setReleaseApproverName(user.getName())
                .setReleaseTime(now).setReleaseChecks(checks);

        if ("APPROVE".equals(command.getDecision())) {
            require(Boolean.TRUE.equals(command.getSiteConditionChecked())
                            && Boolean.TRUE.equals(command.getEquipmentParameterChecked())
                            && Boolean.TRUE.equals(command.getMaterialQualityChecked())
                            && Boolean.TRUE.equals(command.getPersonnelStandardChecked()),
                    "批准恢复生产前必须独立核对现场、设备/参数、物料/质量和人员/标准四项条件");
            if (Boolean.TRUE.equals(record.getInspectionRequired())) {
                MesQualityGateDecision decision = qualityGateService.syncChangePointInspection(
                        record.getInspectionRecordId());
                applyInspection(record, decision);
                require(Boolean.TRUE.equals(decision.getPassed()), "变化点首检已失效或未放行：" + decision.getMessage());
            }
            if (arrange != null) {
                require(Set.of("RECEIVE", "PRODUCT", "PAUSE").contains(arrange.getStatus()),
                        "关联派工已终止、退派或完工，不允许按旧事实批准恢复生产");
                require(!Boolean.TRUE.equals(record.getAutoPauseRequired()) || !"PRODUCT".equals(arrange.getStatus()),
                        "配置要求变化点触发后暂停，但关联派工仍在生产中，请先重试暂停并现场确认");
            }
            if (StrUtil.isNotBlank(record.getDeviceCode())) {
                String worksite = first(record.getWorksiteCode(),
                        arrange == null ? null : arrange.getActualWorksiteCode(),
                        arrange == null ? null : arrange.getPlannedWorksiteCode());
                MesDeviceGateDecision decision = deviceGateService.checkDeviceStart(
                        record.getDeviceCode(), worksite, record.getFactoryCode());
                require(decision != null && Boolean.TRUE.equals(decision.getAllowed()),
                        "设备实时生产门仍未放行：" + (decision == null ? "无判定结果" : decision.getMessage()));
            }
            if (Boolean.TRUE.equals(record.getIntensifiedInspectionRequired())) {
                require(StrUtil.isNotBlank(record.getProduceTaskCode()),
                        "加严巡检监控缺少关联工序任务，不能批准恢复生产");
                record.setStatus(MONITORING).setHandled("0").setCloseTime(null).setCloseReason(null)
                        .setMonitoringStartTime(now).setMonitoringEndTime(null)
                        .setIntensifiedInspectionPassedCount(0).setIntensifiedInspectionGeneratedCount(0)
                        .setIntensifiedInspectionRecordId(null).setIntensifiedInspectionId(null)
                        .setIntensifiedInspectionStatus(null).setIntensifiedInspectionLastTime(null)
                        .setIntensifiedInspectionNextTime(now).setNextEscalationTime(null);
            } else {
                record.setStatus(CLOSED).setHandled("1").setCloseTime(now)
                        .setCloseReason("独立恢复生产审批通过").setNextEscalationTime(null);
            }
        } else {
            record.setStatus(HANDLING).setHandled("0")
                    .setVerificationResult(null).setVerificationRemark(null).setVerifierCode(null)
                    .setVerifierName(null).setVerificationTime(null)
                    .setReleaseDeadline(null).setReleaseApproverUserCodes(null).setReleaseApproverUserNames(null)
                    .setReleaseEvidenceSnapshot(null).setReleaseEvidenceFingerprint(null)
                    .setIntensifiedInspectionRecordId(null).setIntensifiedInspectionId(null)
                    .setIntensifiedInspectionStatus(null).setIntensifiedInspectionNextTime(null)
                    .setIntensifiedInspectionLastTime(null).setMonitoringStartTime(null).setMonitoringEndTime(null)
                    .setCloseTime(null).setCloseReason(null)
                    .setNextEscalationTime(record.getHandleDeadline() != null && now.isBefore(record.getHandleDeadline())
                            ? record.getHandleDeadline() : now.plusMinutes(30));
        }
        updateById(record);
        saveOperation(record, requestId,
                "APPROVE".equals(command.getDecision()) ? "RELEASE_APPROVE" : "RELEASE_REJECT",
                WAIT_RELEASE, record.getStatus(), command.getRemark() + "；" + checks
                        + "；证据指纹=" + command.getExpectedEvidenceFingerprint());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(ChangePointCancelCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        require(StrUtil.isNotBlank(command.getReason()), "取消原因不能为空");
        MesChangePointHandleEntity record = scopedLock(command.getId(), "write");
        String requestId = requestId(command.getRequestId());
        if (hasOperation(record.getId(), requestId)) return;
        require(!CLOSED.equals(record.getStatus()) && !CANCELLED.equals(record.getStatus()), "终态事件不能取消");
        String before = record.getStatus();
        record.setStatus(CANCELLED).setHandled("1").setCloseTime(LocalDateTime.now())
                .setCloseReason(command.getReason()).setNextEscalationTime(null)
                .setMonitoringEndTime(MONITORING.equals(before) ? LocalDateTime.now() : record.getMonitoringEndTime())
                .setIntensifiedInspectionNextTime(null);
        updateById(record);
        saveOperation(record, requestId, "CANCEL", before, CANCELLED, command.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryInspection(Long id) {
        MesChangePointHandleEntity record = scopedLock(id, "write");
        require(Boolean.TRUE.equals(record.getInspectionRequired()) && record.getInspectionRecordId() != null,
                "当前事件没有可重试的变化点首检");
        require(!CLOSED.equals(record.getStatus()) && !CANCELLED.equals(record.getStatus()), "终态事件不能重试首检");
        MesQualityGateDecision decision = qualityGateService.retryChangePointInspection(record.getInspectionRecordId());
        applyInspection(record, decision);
        updateById(record);
        saveOperation(record, IdUtil.fastSimpleUUID(), "RETRY_INSPECTION", record.getStatus(), record.getStatus(),
                decision.getMessage());
    }

    @Override
    public List<MesChangePointOperationEntity> operations(Long id) {
        require(id != null, "变化点事件不能为空");
        getDetail(id);
        return operationService.list(new LambdaQueryWrapper<MesChangePointOperationEntity>()
                .eq(MesChangePointOperationEntity::getHandleId, id)
                .orderByAsc(MesChangePointOperationEntity::getOperationTime)
                .orderByAsc(MesChangePointOperationEntity::getId));
    }

    @Override
    public MesChangePointLossAnalysisDTO lossAnalysis(LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime end = endTime == null ? LocalDateTime.now() : endTime;
        LocalDateTime start = startTime == null ? end.minusDays(30) : startTime;
        require(start.isBefore(end), "损失分析开始时间必须早于结束时间");
        require(Duration.between(start, end).toDays() <= 366, "单次损失分析时间范围不能超过366天");
        String factory = factoryAccess.require("read");
        List<MesChangePointLossGroupDTO> totalRows = baseMapper.lossGroups(factory, start, end, "TOTAL");
        MesChangePointLossGroupDTO total = totalRows.isEmpty()
                ? new MesChangePointLossGroupDTO().setGroupCode("TOTAL").setGroupName("合计")
                : totalRows.get(0);
        return new MesChangePointLossAnalysisDTO().setStartTime(start).setEndTime(end).setTotal(total)
                .setByCategory(baseMapper.lossGroups(factory, start, end, "CATEGORY"))
                .setByChangePoint(baseMapper.lossGroups(factory, start, end, "CONFIG"))
                .setByLossCategory(baseMapper.lossGroups(factory, start, end, "LOSS"));
    }

    @Override
    public void assertStartAllowed(MesTaskArrangeEntity arrange) {
        assertAllowed(arrange, true);
    }

    @Override
    public void assertReportAllowed(MesTaskArrangeEntity arrange) {
        assertAllowed(arrange, false);
    }

    @Override
    public void scanSla() {
        LocalDateTime now = LocalDateTime.now();
        List<MesChangePointHandleEntity> candidates = list(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .in(MesChangePointHandleEntity::getStatus, WAIT_ACCEPT, HANDLING, WAIT_VERIFY, WAIT_RELEASE)
                .isNotNull(MesChangePointHandleEntity::getNextEscalationTime)
                .le(MesChangePointHandleEntity::getNextEscalationTime, now));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int escalated = 0;
        int failed = 0;
        for (MesChangePointHandleEntity candidate : candidates) {
            try {
                Boolean changed = transaction.execute(status -> escalateDue(candidate.getId(), now));
                if (Boolean.TRUE.equals(changed)) escalated++;
            } catch (Exception exception) {
                failed++;
                log.error("变化点SLA单事件升级失败, eventId={}", candidate.getId(), exception);
            }
        }
        log.info("变化点SLA扫描完成, 到期数={}, 升级数={}, 失败数={}", candidates.size(), escalated, failed);
    }

    @Override
    public void scanIntensifiedInspections() {
        LocalDateTime observedTime = LocalDateTime.now();
        List<MesChangePointHandleEntity> candidates = baseMapper.selectPage(new Page<>(1, 200),
                new LambdaQueryWrapper<MesChangePointHandleEntity>()
                        .eq(MesChangePointHandleEntity::getStatus, MONITORING)
                        .isNotNull(MesChangePointHandleEntity::getIntensifiedInspectionNextTime)
                        .le(MesChangePointHandleEntity::getIntensifiedInspectionNextTime, observedTime)
                        .orderByAsc(MesChangePointHandleEntity::getIntensifiedInspectionNextTime)
                        .orderByAsc(MesChangePointHandleEntity::getId)).getRecords();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int advanced = 0;
        int failed = 0;
        for (MesChangePointHandleEntity candidate : candidates) {
            try {
                Boolean changed = candidate.getTenantId() == null
                        ? transaction.execute(status -> advanceIntensifiedInspection(candidate.getId(), observedTime))
                        : TenantBroker.applyAs(candidate.getTenantId(), ignored -> transaction.execute(
                                status -> advanceIntensifiedInspection(candidate.getId(), observedTime)));
                if (Boolean.TRUE.equals(changed)) advanced++;
            } catch (Exception exception) {
                failed++;
                log.error("变化点加严巡检推进失败, eventId={}", candidate.getId(), exception);
            }
        }
        log.info("变化点加严巡检扫描完成, 到期数={}, 推进数={}, 失败数={}", candidates.size(), advanced, failed);
    }

    private boolean advanceIntensifiedInspection(Long id, LocalDateTime observedTime) {
        MesChangePointHandleEntity record = lock(id);
        if (!MONITORING.equals(record.getStatus())
                || record.getIntensifiedInspectionNextTime() == null
                || record.getIntensifiedInspectionNextTime().isAfter(observedTime)) {
            return false;
        }
        MesProduceTaskEntity produceTask = produceTaskService.getOne(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getCode, record.getProduceTaskCode())
                        .eq(MesProduceTaskEntity::getFactoryCode, record.getFactoryCode()).last("LIMIT 1"), false);
        if (produceTask == null) {
            record.setStatus(HANDLING).setHandled("0").setMonitoringEndTime(observedTime)
                    .setIntensifiedInspectionStatus("ERROR")
                    .setIntensifiedInspectionNextTime(null).setNextEscalationTime(observedTime.plusMinutes(30));
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "MONITOR_FAIL", MONITORING, HANDLING,
                    "关联工序任务不存在，加严监控自动重新抱闸");
            return true;
        }
        if (Set.of("CLOSE", "FINISH").contains(produceTask.getStatus())) {
            record.setStatus(CLOSED).setHandled("1").setMonitoringEndTime(observedTime)
                    .setIntensifiedInspectionNextTime(null).setCloseTime(observedTime)
                    .setCloseReason("工序已" + ("FINISH".equals(produceTask.getStatus()) ? "完工" : "关闭")
                            + "，终止剩余加严巡检");
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "MONITOR_STOP", MONITORING, CLOSED,
                    record.getCloseReason());
            return true;
        }

        MesQualityGateDecision decision;
        if (record.getIntensifiedInspectionRecordId() == null) {
            int sequence = Objects.requireNonNullElse(record.getIntensifiedInspectionGeneratedCount(), 0) + 1;
            decision = qualityGateService.createChangePointIntensifiedInspection(record.getProduceTaskCode(),
                    "CP_MONITOR:" + record.getEventCode() + ":"
                            + Objects.requireNonNull(record.getReleaseTime()).toString().replaceAll("[^0-9]", "")
                            + ":" + sequence,
                    record.getConfigCode(), record.getConfigName() + "-复产加严巡检");
            record.setIntensifiedInspectionGeneratedCount(sequence);
            applyIntensifiedInspection(record, decision);
            record.setIntensifiedInspectionLastTime(observedTime)
                    .setIntensifiedInspectionNextTime(observedTime.plusMinutes(monitorPollMinutes(record)));
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "CREATE_MONITOR_INSPECTION", MONITORING, MONITORING,
                    "第" + sequence + "次加严巡检；" + decision.getMessage());
            return true;
        }

        decision = qualityGateService.syncChangePointInspection(record.getIntensifiedInspectionRecordId());
        applyIntensifiedInspection(record, decision);
        String gateStatus = record.getIntensifiedInspectionStatus();
        if (Boolean.TRUE.equals(decision.getPassed())) {
            int passed = Objects.requireNonNullElse(record.getIntensifiedInspectionPassedCount(), 0) + 1;
            int required = Objects.requireNonNullElse(record.getIntensifiedInspectionPassCount(), 1);
            record.setIntensifiedInspectionPassedCount(passed).setIntensifiedInspectionLastTime(observedTime);
            if (passed >= required) {
                record.setStatus(CLOSED).setHandled("1").setMonitoringEndTime(observedTime)
                        .setIntensifiedInspectionNextTime(null).setCloseTime(observedTime)
                        .setCloseReason("复产后连续" + passed + "次加严巡检合格");
                updateById(record);
                saveOperation(record, IdUtil.fastSimpleUUID(), "MONITOR_COMPLETE", MONITORING, CLOSED,
                        record.getCloseReason());
            } else {
                record.setIntensifiedInspectionRecordId(null).setIntensifiedInspectionId(null)
                        .setIntensifiedInspectionStatus(null)
                        .setIntensifiedInspectionNextTime(observedTime.plusMinutes(
                                Objects.requireNonNullElse(record.getIntensifiedInspectionIntervalMin(), 60)));
                updateById(record);
                saveOperation(record, IdUtil.fastSimpleUUID(), "MONITOR_PASS", MONITORING, MONITORING,
                        "第" + passed + "次连续合格，尚需" + (required - passed) + "次");
            }
            return true;
        }
        if (Set.of("FAILED", "CANCELED").contains(gateStatus)) {
            record.setStatus(HANDLING).setHandled("0").setMonitoringEndTime(observedTime)
                    .setIntensifiedInspectionNextTime(null).setCloseTime(null).setCloseReason(null)
                    .setNextEscalationTime(observedTime.plusMinutes(30));
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "MONITOR_FAIL", MONITORING, HANDLING,
                    "加严巡检未放行，自动重新抱闸：" + decision.getMessage());
            return true;
        }
        if ("ERROR".equals(gateStatus)) {
            decision = qualityGateService.retryChangePointInspection(record.getIntensifiedInspectionRecordId());
            applyIntensifiedInspection(record, decision);
        }
        record.setIntensifiedInspectionLastTime(observedTime)
                .setIntensifiedInspectionNextTime(observedTime.plusMinutes(monitorPollMinutes(record)));
        updateById(record);
        return true;
    }

    private void applyIntensifiedInspection(MesChangePointHandleEntity record, MesQualityGateDecision decision) {
        MesQualityGateItemDTO item = decision == null || decision.getItems().isEmpty()
                ? null : decision.getItems().get(0);
        record.setIntensifiedInspectionRecordId(item == null ? record.getIntensifiedInspectionRecordId()
                        : item.getRecordId())
                .setIntensifiedInspectionId(item == null ? record.getIntensifiedInspectionId()
                        : item.getInspectionId())
                .setIntensifiedInspectionStatus(item == null ? "ERROR" : item.getGateStatus());
    }

    private int monitorPollMinutes(MesChangePointHandleEntity record) {
        return Math.min(5, Math.max(1,
                Objects.requireNonNullElse(record.getIntensifiedInspectionIntervalMin(), 60)));
    }

    private boolean escalateDue(Long id, LocalDateTime now) {
        MesChangePointHandleEntity record = lock(id);
        if (!Set.of(WAIT_ACCEPT, HANDLING, WAIT_VERIFY, WAIT_RELEASE).contains(record.getStatus())
                || record.getNextEscalationTime() == null || record.getNextEscalationTime().isAfter(now)) {
            return false;
        }
        MesChangePointConfigEntity config = configService.getOne(
                new LambdaQueryWrapper<MesChangePointConfigEntity>()
                        .eq(MesChangePointConfigEntity::getConfigCode, record.getConfigCode())
                        .eq(MesChangePointConfigEntity::getFactoryCode, record.getFactoryCode()).last("LIMIT 1"), false);
        if (config == null) {
            record.setNextEscalationTime(null).setLastNotifyResult("FAILED:变化点配置不存在");
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "ESCALATE_STOP", record.getStatus(), record.getStatus(),
                    "变化点配置不存在，已停止自动升级，请人工核查");
            return false;
        }
        ChangePointNotifyResult result = notifyService.notifyEscalation(config, record);
        int count = record.getEscalationCount() == null ? 1 : record.getEscalationCount() + 1;
        int configuredMinutes = WAIT_RELEASE.equals(record.getStatus())
                ? config.getReleaseSlaMin() : config.getResponseSlaMin();
        int repeatMinutes = Math.min(1440, Math.max(5, configuredMinutes));
        record.setEscalationCount(count).setLastEscalationTime(now)
                .setNextEscalationTime(now.plusMinutes(repeatMinutes))
                .setLastNotifyChannels(result.getChannels()).setLastNotifyResult(result.getResult());
        updateById(record);
        saveOperation(record, IdUtil.fastSimpleUUID(), "ESCALATE", record.getStatus(), record.getStatus(),
                "第" + count + "次SLA升级；通道=" + result.getChannels() + "；结果=" + result.getResult()
                        + "；说明=" + result.getDetail());
        return true;
    }

    private void assertAllowed(MesTaskArrangeEntity arrange, boolean start) {
        if (arrange == null) return;
        List<MesChangePointHandleEntity> active = list(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .in(MesChangePointHandleEntity::getStatus, WAIT_ACCEPT, HANDLING, WAIT_VERIFY, WAIT_RELEASE)
                .eq(MesChangePointHandleEntity::getFactoryCode, arrange.getFactoryCode())
                .and(wrapper -> wrapper.eq(MesChangePointHandleEntity::getArrangeCode, arrange.getCode())
                        .or().eq(MesChangePointHandleEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                        .or().eq(MesChangePointHandleEntity::getTaskCode, arrange.getTaskCode())));
        List<String> blocking = active.stream().filter(record -> scopeEventMatches(record, arrange))
                .filter(record -> start
                        ? Boolean.TRUE.equals(record.getAutoPauseRequired())
                        || Set.of("HOLD_START", "HOLD_ALL").contains(record.getControlMode())
                        : Boolean.TRUE.equals(record.getInspectionRequired())
                        || Set.of("HOLD_REPORT", "HOLD_ALL").contains(record.getControlMode()))
                .map(record -> record.getEventCode() + "[" + record.getConfigName() + "," + record.getStatus() + "]")
                .toList();
        require(blocking.isEmpty(), "存在未闭环变化点，禁止" + (start ? "开工" : "报工") + "：" + String.join("、", blocking));
    }

    private boolean scopeEventMatches(MesChangePointHandleEntity record, MesTaskArrangeEntity arrange) {
        return (StrUtil.isBlank(record.getRouteCode()) || Objects.equals(record.getRouteCode(), arrange.getRouteCode()))
                && (StrUtil.isBlank(record.getLineCode()) || Objects.equals(record.getLineCode(), arrange.getRouteWorkCenter()))
                && (StrUtil.isBlank(record.getProductCode()) || Objects.equals(record.getProductCode(), arrange.getMatCode()));
    }

    private MesTaskArrangeEntity resolveArrange(ChangePointTriggerCommand command, String factory) {
        if (StrUtil.isBlank(command.getArrangeCode())) return null;
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, command.getArrangeCode())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).last("LIMIT 1"));
        require(arrange != null, "关联派工任务不存在");
        requireBlankOrEqual(command.getTaskCode(), arrange.getTaskCode(), "生产工单与派工任务不一致");
        requireBlankOrEqual(command.getProduceTaskCode(), arrange.getProduceTaskCode(), "工序任务与派工任务不一致");
        return arrange;
    }

    private TriggerContext buildContext(ChangePointTriggerCommand command, MesTaskArrangeEntity arrange,
                                        String factory) {
        String produceTaskCode = first(command.getProduceTaskCode(), arrange == null ? null : arrange.getProduceTaskCode());
        MesProduceTaskEntity produceTask = StrUtil.isBlank(produceTaskCode) ? null : produceTaskService.getOne(
                new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getCode, produceTaskCode)
                        .eq(MesProduceTaskEntity::getFactoryCode, factory)
                        .last("LIMIT 1"), false);
        require(StrUtil.isBlank(produceTaskCode) || produceTask != null, "关联工序任务不存在");
        return new TriggerContext(first(command.getTaskCode(), arrange == null ? null : arrange.getTaskCode(),
                produceTask == null ? null : produceTask.getTaskCode()), produceTaskCode,
                first(command.getArrangeCode(), arrange == null ? null : arrange.getCode()),
                first(command.getLineCode(), arrange == null ? null : arrange.getRouteWorkCenter(),
                        produceTask == null ? null : produceTask.getRouteWorkCenter()),
                first(command.getRouteCode(), arrange == null ? null : arrange.getRouteCode(),
                        produceTask == null ? null : produceTask.getRouteCode()),
                first(command.getProductCode(), arrange == null ? null : arrange.getMatCode(),
                        produceTask == null ? null : produceTask.getMatCode()),
                first(command.getWorksiteCode(), arrange == null ? null : arrange.getActualWorksiteCode()));
    }

    private void validateScope(MesChangePointConfigEntity config, TriggerContext context) {
        require(scopeMatches(config.getLineCode(), context.lineCode()), "触发产线/工作中心不在配置范围内");
        require(scopeMatches(config.getRouteCode(), context.routeCode()), "触发工序不在配置范围内");
        require(scopeMatches(config.getProductCode(), context.productCode()), "触发产品不在配置范围内");
    }

    private void validateTriggerValue(BigDecimal lowerLimit, BigDecimal upperLimit, BigDecimal value, String source) {
        boolean numeric = lowerLimit != null || upperLimit != null;
        if (!numeric) return;
        require(lowerLimit != null && upperLimit != null && upperLimit.compareTo(lowerLimit) > 0,
                "变化点生效阈值不完整或上下限无效");
        require(value != null, "数值型变化点必须提供实际采集值");
        boolean out = value.compareTo(lowerLimit) < 0 || value.compareTo(upperLimit) > 0;
        require("MANUAL".equals(source) || out, "自动触发值仍在允许范围内，不生成变化点事件");
    }

    private void applyInspection(MesChangePointHandleEntity record, MesQualityGateDecision decision) {
        if (decision != null && !decision.getItems().isEmpty()) {
            record.setInspectionRecordId(decision.getItems().get(0).getRecordId())
                    .setInspectionId(decision.getItems().get(0).getInspectionId())
                    .setInspectionStatus(decision.getItems().get(0).getGateStatus());
        }
    }

    private MesChangePointConfigEntity requireConfig(MesChangePointHandleEntity record) {
        MesChangePointConfigEntity config = configService.getOne(new LambdaQueryWrapper<MesChangePointConfigEntity>()
                .eq(MesChangePointConfigEntity::getConfigCode, record.getConfigCode())
                .eq(MesChangePointConfigEntity::getFactoryCode, record.getFactoryCode()).last("LIMIT 1"), false);
        require(config != null, "变化点配置不存在");
        return config;
    }

    private MesTaskArrangeEntity linkedArrange(MesChangePointHandleEntity record, boolean lock) {
        if (StrUtil.isBlank(record.getArrangeCode())) return null;
        List<MesTaskArrangeEntity> rows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, record.getArrangeCode())
                .eq(MesTaskArrangeEntity::getFactoryCode, record.getFactoryCode())
                .last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2"));
        require(rows.size() == 1, "关联派工不存在或编码不唯一，不能形成恢复生产证据");
        MesTaskArrangeEntity arrange = rows.get(0);
        require(Objects.equals(record.getTaskCode(), arrange.getTaskCode())
                        && Objects.equals(record.getProduceTaskCode(), arrange.getProduceTaskCode()),
                "变化点与关联派工的工单或工序任务归属已变化");
        return arrange;
    }

    private Map<String, Object> releaseEvidence(MesChangePointHandleEntity record, MesTaskArrangeEntity arrange) {
        if (arrange != null) {
            require(Objects.equals(record.getArrangeCode(), arrange.getCode())
                            && Objects.equals(record.getTaskCode(), arrange.getTaskCode())
                            && Objects.equals(record.getProduceTaskCode(), arrange.getProduceTaskCode())
                            && Objects.equals(record.getFactoryCode(), arrange.getFactoryCode()),
                    "变化点与锁定派工的工厂、工单或工序任务归属已变化");
        }
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("version", "MES_CHANGE_POINT_RELEASE_V3");
        evidence.put("eventId", record.getId());
        evidence.put("eventCode", record.getEventCode());
        evidence.put("tenantId", record.getTenantId());
        evidence.put("factoryCode", record.getFactoryCode());
        evidence.put("configCode", record.getConfigCode());
        evidence.put("configLevel", record.getConfigLevel());
        evidence.put("controlMode", record.getControlMode());
        evidence.put("inspectionRequired", record.getInspectionRequired());
        evidence.put("intensifiedInspectionRequired", record.getIntensifiedInspectionRequired());
        evidence.put("intensifiedInspectionIntervalMin", record.getIntensifiedInspectionIntervalMin());
        evidence.put("intensifiedInspectionPassCount", record.getIntensifiedInspectionPassCount());
        evidence.put("autoPauseRequired", record.getAutoPauseRequired());
        evidence.put("productionPaused", record.getProductionPaused());
        evidence.put("pausedByChangePoint", record.getPausedByChangePoint());
        evidence.put("pauseAttemptId", record.getPauseAttemptId());
        evidence.put("pauseOutcome", record.getPauseOutcome());
        evidence.put("affectedWipQty", record.getAffectedWipQty());
        evidence.put("releasedWipQty", record.getReleasedWipQty());
        evidence.put("reworkWipQty", record.getReworkWipQty());
        evidence.put("scrapWipQty", record.getScrapWipQty());
        evidence.put("lossAmount", record.getLossAmount());
        evidence.put("lossCategory", record.getLossCategory());
        evidence.put("inspectionRecordId", record.getInspectionRecordId());
        evidence.put("inspectionId", record.getInspectionId());
        evidence.put("inspectionStatus", record.getInspectionStatus());
        evidence.put("taskCode", record.getTaskCode());
        evidence.put("produceTaskCode", record.getProduceTaskCode());
        evidence.put("arrangeCode", record.getArrangeCode());
        evidence.put("lineCode", record.getLineCode());
        evidence.put("routeCode", record.getRouteCode());
        evidence.put("productCode", record.getProductCode());
        evidence.put("deviceCode", record.getDeviceCode());
        evidence.put("worksiteCode", record.getWorksiteCode());
        evidence.put("rootCause", record.getRootCause());
        evidence.put("correctiveAction", record.getCorrectiveAction());
        evidence.put("preventiveAction", record.getPreventiveAction());
        evidence.put("handlePersonCode", record.getHandlePersonCode());
        evidence.put("handleTime", record.getHandleTime());
        evidence.put("verificationResult", record.getVerificationResult());
        evidence.put("verificationRemark", record.getVerificationRemark());
        evidence.put("verifierCode", record.getVerifierCode());
        evidence.put("verificationTime", record.getVerificationTime());
        if (arrange != null) {
            evidence.put("arrangeId", arrange.getId());
            evidence.put("arrangeStatus", arrange.getStatus());
            evidence.put("arrangeWorkerCode", arrange.getWorkerCode());
            evidence.put("actualWorksiteCode", arrange.getActualWorksiteCode());
            evidence.put("plannedWorksiteCode", arrange.getPlannedWorksiteCode());
            evidence.put("plannedDeviceCode", arrange.getPlannedDeviceCode());
            evidence.put("plannedToolingCodes", arrange.getPlannedToolingCodes());
        }
        if (StrUtil.isNotBlank(record.getProduceTaskCode())) {
            MesProduceTaskEntity task = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                    .eq(MesProduceTaskEntity::getCode, record.getProduceTaskCode())
                    .eq(MesProduceTaskEntity::getFactoryCode, record.getFactoryCode()).last("LIMIT 1"), false);
            require(task != null, "关联工序任务不存在，不能形成恢复生产证据");
            evidence.put("produceTaskId", task.getId());
            evidence.put("produceTaskStatus", task.getStatus());
            evidence.put("produceTaskRouteCode", task.getRouteCode());
            evidence.put("produceTaskProductCode", task.getMatCode());
        }
        return evidence;
    }

    private boolean participated(Long id, String operatorCode) {
        return operationService.count(new LambdaQueryWrapper<MesChangePointOperationEntity>()
                .eq(MesChangePointOperationEntity::getHandleId, id)
                .eq(MesChangePointOperationEntity::getOperatorCode, operatorCode)
                .in(MesChangePointOperationEntity::getOperationType,
                        "TRIGGER", "ACCEPT", "HANDLE", "VERIFY_PASS")) > 0;
    }

    private String fingerprint(Object value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("变化点恢复生产证据指纹生成失败", exception);
        }
    }

    private BigDecimal wipExposure(MesTaskArrangeEntity arrange) {
        if (arrange == null) return BigDecimal.ZERO;
        BigDecimal target = arrange.getPlannedNum() == null || arrange.getPlannedNum().compareTo(BigDecimal.ZERO) <= 0
                ? Objects.requireNonNullElse(arrange.getNoticeNum(), BigDecimal.ZERO) : arrange.getPlannedNum();
        BigDecimal accounted = Objects.requireNonNullElse(arrange.getCompleteNum(), BigDecimal.ZERO)
                .add(Objects.requireNonNullElse(arrange.getUnPassNum(), BigDecimal.ZERO));
        return target.subtract(accounted).max(BigDecimal.ZERO);
    }

    private void validateWipDisposition(ChangePointHandleCommand command) {
        require(command.getAffectedWipQty() != null, "必须确认受影响在制数量，未受影响时填写0");
        require(command.getReleasedWipQty() != null && command.getReworkWipQty() != null
                        && command.getScrapWipQty() != null,
                "必须完整填写在制放行、返工和报废数量");
        require(command.getLossAmount() != null, "必须确认损失金额，暂无直接损失时填写0");
        require(nonNegative(command.getAffectedWipQty()) && nonNegative(command.getReleasedWipQty())
                        && nonNegative(command.getReworkWipQty()) && nonNegative(command.getScrapWipQty())
                        && nonNegative(command.getLossAmount()),
                "在制处置数量和损失金额不能为负数");
        require(command.getReleasedWipQty().add(command.getReworkWipQty()).add(command.getScrapWipQty())
                        .compareTo(command.getAffectedWipQty()) == 0,
                "放行、返工和报废数量之和必须等于受影响在制数量");
        boolean hasLoss = command.getLossAmount().compareTo(BigDecimal.ZERO) > 0
                || command.getReworkWipQty().compareTo(BigDecimal.ZERO) > 0
                || command.getScrapWipQty().compareTo(BigDecimal.ZERO) > 0;
        String category = StrUtil.isBlank(command.getLossCategory()) ? null
                : command.getLossCategory().trim().toUpperCase();
        require(!hasLoss || LOSS_CATEGORIES.contains(category), "存在返工、报废或损失金额时必须选择有效损失类型");
        require(!hasLoss || StrUtil.isNotBlank(command.getLossRemark()), "存在损失时必须填写处置和测算说明");
        require(command.getLossRemark() == null || command.getLossRemark().length() <= 1000,
                "损失说明不能超过1000字");
    }

    private void assertWipDisposition(MesChangePointHandleEntity record) {
        require(record.getAffectedWipQty() != null && record.getReleasedWipQty() != null
                        && record.getReworkWipQty() != null && record.getScrapWipQty() != null,
                "受影响在制品尚未完成数量核清");
        require(record.getReleasedWipQty().add(record.getReworkWipQty()).add(record.getScrapWipQty())
                        .compareTo(record.getAffectedWipQty()) == 0,
                "在制放行、返工和报废分配与受影响数量不一致");
    }

    private boolean nonNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    private String yes(Boolean value) {
        return Boolean.TRUE.equals(value) ? "是" : "否";
    }

    private MesChangePointHandleEntity scopedRead(Long id, String accessLevel) {
        String factory = factoryAccess.require(accessLevel);
        MesChangePointHandleEntity record = getOne(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getId, id)
                .eq(MesChangePointHandleEntity::getFactoryCode, factory).last("LIMIT 1"), false);
        require(record != null, "当前实际工厂内未找到变化点事件");
        return record;
    }

    private MesChangePointHandleEntity scopedLock(Long id, String accessLevel) {
        String factory = factoryAccess.require(accessLevel);
        MesChangePointHandleEntity record = getOne(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getId, id)
                .eq(MesChangePointHandleEntity::getFactoryCode, factory).last("FOR UPDATE"), false);
        require(record != null, "当前实际工厂内未找到变化点事件");
        return record;
    }

    private MesChangePointHandleEntity lock(Long id) {
        MesChangePointHandleEntity record = getOne(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getId, id).last("FOR UPDATE"), false);
        if (record == null) throw new CheckedException("变化点事件不存在");
        return record;
    }

    private boolean hasOperation(Long id, String requestId) {
        return operationService.count(new LambdaQueryWrapper<MesChangePointOperationEntity>()
                .eq(MesChangePointOperationEntity::getHandleId, id)
                .eq(MesChangePointOperationEntity::getRequestId, requestId)) > 0;
    }

    private void saveOperation(MesChangePointHandleEntity record, String requestId, String type,
                               String before, String after, String content) {
        MomUser user = SecurityUtils.getUser();
        operationService.save(new MesChangePointOperationEntity().setRequestId(requestId)
                .setHandleId(record.getId()).setEventCode(record.getEventCode()).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after)
                .setContent(StrUtil.subPre(StrUtil.nullToEmpty(content), 2000))
                .setOperatorCode(user == null ? "SYSTEM" : user.getUsername())
                .setOperatorName(user == null ? "系统" : user.getName()).setOperationTime(LocalDateTime.now())
                .setFactoryCode(record.getFactoryCode()));
    }

    private void requireAuthorized(String userIds, MomUser user, String message) {
        require(StrUtil.isBlank(userIds) || Arrays.stream(userIds.split(",")).map(String::trim)
                .anyMatch(value -> Objects.equals(value, String.valueOf(user.getId()))), message);
    }

    private void requireBlankOrEqual(String requested, String actual, String message) {
        require(StrUtil.isBlank(requested) || Objects.equals(requested, actual), message);
    }

    private boolean scopeMatches(String configured, String actual) {
        return StrUtil.isBlank(configured) || Objects.equals(configured, actual);
    }

    private String first(String... values) {
        return Arrays.stream(values).filter(StrUtil::isNotBlank).findFirst().orElse(null);
    }

    private String requestId(String value) {
        return StrUtil.blankToDefault(value, IdUtil.fastSimpleUUID());
    }

    private MomUser currentUser() {
        MomUser user = SecurityUtils.getUser();
        if (user == null) throw new CheckedException("未获取到当前用户");
        return user;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record TriggerContext(String taskCode, String produceTaskCode, String arrangeCode,
                                  String lineCode, String routeCode, String productCode, String worksiteCode) {
    }
}
