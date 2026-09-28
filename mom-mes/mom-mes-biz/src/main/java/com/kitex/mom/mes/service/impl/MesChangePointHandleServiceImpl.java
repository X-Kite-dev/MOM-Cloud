package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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
    private static final String CLOSED = "1";
    private static final String CANCELLED = "9";
    private static final String PASS = "PASS";
    private static final String REJECT = "REJECT";
    private static final Set<String> SOURCES = Set.of("MANUAL", "IOT", "MES", "MDM", "QMS", "TPM", "INTEGRATION");

    private final MesChangePointConfigService configService;
    private final MesChangePointLevelConfigService levelConfigService;
    private final MesChangePointOperationService operationService;
    private final MesChangePointNotifyService notifyService;
    private final MesQualityGateService qualityGateService;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesProduceTaskService produceTaskService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> page,
                                                       MesChangePointHandleEntity query) {
        return baseMapper.queryPage(page, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesChangePointHandleEntity trigger(ChangePointTriggerCommand command) {
        require(command != null, "变化点触发参数不能为空");
        require(StrUtil.isNotBlank(command.getConfigCode()), "变化点配置编码不能为空");
        require(StrUtil.isNotBlank(command.getRequestId()), "来源请求号不能为空");
        String source = StrUtil.blankToDefault(command.getTriggerSource(), "MANUAL").toUpperCase();
        require(SOURCES.contains(source), "变化点触发来源无效");
        MesChangePointHandleEntity existing = getOne(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getRequestId, command.getRequestId()).last("LIMIT 1"), false);
        if (existing != null) return existing;

        MesChangePointConfigEntity config = configService.getOne(
                new LambdaQueryWrapper<MesChangePointConfigEntity>()
                        .eq(MesChangePointConfigEntity::getConfigCode, command.getConfigCode())
                        .eq(MesChangePointConfigEntity::getEnabled, Boolean.TRUE).last("LIMIT 1"), false);
        require(config != null, "变化点配置不存在或未启用");
        MesTaskArrangeEntity arrange = resolveArrange(command);
        TriggerContext context = buildContext(command, arrange);
        validateScope(config, context);
        MesChangePointLevelConfigEntity level = levelConfigService.getOne(
                new LambdaQueryWrapper<MesChangePointLevelConfigEntity>()
                        .eq(MesChangePointLevelConfigEntity::getConfigCode, config.getConfigCode())
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
                .setHandleRole(level != null && StrUtil.isNotBlank(level.getVerifyRole())
                        ? level.getVerifyRole() : config.getVerifyRole())
                .setAlertTime(now).setTriggerTime(now)
                .setAlertLevel(level == null ? StrUtil.blankToDefault(config.getLevel(), "GENERAL")
                        : StrUtil.blankToDefault(level.getLevel(), "GENERAL"))
                .setHandled("0").setStatus(WAIT_ACCEPT)
                .setResponseDeadline(now.plusMinutes(config.getResponseSlaMin()))
                .setHandleDeadline(now.plusMinutes(config.getHandleSlaMin()))
                .setEscalationCount(0).setNextEscalationTime(now.plusMinutes(config.getResponseSlaMin()))
                .setFactoryCode(arrange == null ? config.getFactoryCode() : arrange.getFactoryCode());
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
        MesChangePointHandleEntity record = lock(command.getId());
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
        MesChangePointHandleEntity record = lock(command.getId());
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
                .setHandlePersonCode(user.getUsername()).setHandlePersonName(user.getName())
                .setHandleBy(user.getUsername()).setHandleTime(LocalDateTime.now());

        if (Boolean.TRUE.equals(record.getVerificationRequired())) {
            record.setStatus(WAIT_VERIFY);
            MesChangePointConfigEntity config = requireConfig(record.getConfigCode());
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
                        + "；预计关闭=" + command.getExpectedCloseTime() + "；说明=" + command.getHandleDesc());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verify(ChangePointVerifyCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        require(PASS.equals(command.getVerificationResult()) || REJECT.equals(command.getVerificationResult()),
                "验证结果必须为PASS或REJECT");
        require(StrUtil.isNotBlank(command.getVerificationRemark()), "验证说明不能为空");
        MesChangePointHandleEntity record = lock(command.getId());
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
        record.setVerificationResult(command.getVerificationResult())
                .setVerificationRemark(command.getVerificationRemark()).setVerifierCode(user.getUsername())
                .setVerifierName(user.getName()).setVerificationTime(LocalDateTime.now());
        if (PASS.equals(command.getVerificationResult())) {
            record.setStatus(CLOSED).setHandled("1").setCloseTime(LocalDateTime.now())
                    .setCloseReason("处置验证通过").setNextEscalationTime(null);
        } else {
            LocalDateTime now = LocalDateTime.now();
            record.setStatus(HANDLING).setHandled("0").setHandlePersonCode(null).setHandlePersonName(null)
                    .setHandleBy(null).setHandleTime(null).setHandleDesc(null).setRootCause(null)
                    .setCorrectiveAction(null).setPreventiveAction(null).setExpectedCloseTime(null)
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
    public void cancel(ChangePointCancelCommand command) {
        require(command != null && command.getId() != null, "变化点事件不能为空");
        require(StrUtil.isNotBlank(command.getReason()), "取消原因不能为空");
        MesChangePointHandleEntity record = lock(command.getId());
        String requestId = requestId(command.getRequestId());
        if (hasOperation(record.getId(), requestId)) return;
        require(!CLOSED.equals(record.getStatus()) && !CANCELLED.equals(record.getStatus()), "终态事件不能取消");
        String before = record.getStatus();
        record.setStatus(CANCELLED).setHandled("1").setCloseTime(LocalDateTime.now())
                .setCloseReason(command.getReason()).setNextEscalationTime(null);
        updateById(record);
        saveOperation(record, requestId, "CANCEL", before, CANCELLED, command.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryInspection(Long id) {
        MesChangePointHandleEntity record = lock(id);
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
        return operationService.list(new LambdaQueryWrapper<MesChangePointOperationEntity>()
                .eq(MesChangePointOperationEntity::getHandleId, id)
                .orderByAsc(MesChangePointOperationEntity::getOperationTime)
                .orderByAsc(MesChangePointOperationEntity::getId));
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
                .in(MesChangePointHandleEntity::getStatus, WAIT_ACCEPT, HANDLING, WAIT_VERIFY)
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

    private boolean escalateDue(Long id, LocalDateTime now) {
        MesChangePointHandleEntity record = lock(id);
        if (!Set.of(WAIT_ACCEPT, HANDLING, WAIT_VERIFY).contains(record.getStatus())
                || record.getNextEscalationTime() == null || record.getNextEscalationTime().isAfter(now)) {
            return false;
        }
        MesChangePointConfigEntity config = configService.getOne(
                new LambdaQueryWrapper<MesChangePointConfigEntity>()
                        .eq(MesChangePointConfigEntity::getConfigCode, record.getConfigCode()).last("LIMIT 1"), false);
        if (config == null) {
            record.setNextEscalationTime(null).setLastNotifyResult("FAILED:变化点配置不存在");
            updateById(record);
            saveOperation(record, IdUtil.fastSimpleUUID(), "ESCALATE_STOP", record.getStatus(), record.getStatus(),
                    "变化点配置不存在，已停止自动升级，请人工核查");
            return false;
        }
        ChangePointNotifyResult result = notifyService.notifyEscalation(config, record);
        int count = record.getEscalationCount() == null ? 1 : record.getEscalationCount() + 1;
        int repeatMinutes = Math.min(1440, Math.max(5, config.getResponseSlaMin()));
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
                .in(MesChangePointHandleEntity::getStatus, WAIT_ACCEPT, HANDLING, WAIT_VERIFY)
                .and(wrapper -> wrapper.eq(MesChangePointHandleEntity::getArrangeCode, arrange.getCode())
                        .or().eq(MesChangePointHandleEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                        .or().eq(MesChangePointHandleEntity::getTaskCode, arrange.getTaskCode())));
        List<String> blocking = active.stream().filter(record -> scopeEventMatches(record, arrange))
                .filter(record -> start
                        ? Set.of("HOLD_START", "HOLD_ALL").contains(record.getControlMode())
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

    private MesTaskArrangeEntity resolveArrange(ChangePointTriggerCommand command) {
        if (StrUtil.isBlank(command.getArrangeCode())) return null;
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, command.getArrangeCode()).last("LIMIT 1"));
        require(arrange != null, "关联派工任务不存在");
        requireBlankOrEqual(command.getTaskCode(), arrange.getTaskCode(), "生产工单与派工任务不一致");
        requireBlankOrEqual(command.getProduceTaskCode(), arrange.getProduceTaskCode(), "工序任务与派工任务不一致");
        return arrange;
    }

    private TriggerContext buildContext(ChangePointTriggerCommand command, MesTaskArrangeEntity arrange) {
        String produceTaskCode = first(command.getProduceTaskCode(), arrange == null ? null : arrange.getProduceTaskCode());
        MesProduceTaskEntity produceTask = StrUtil.isBlank(produceTaskCode) ? null : produceTaskService.getOne(
                new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getCode, produceTaskCode)
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

    private MesChangePointConfigEntity requireConfig(String configCode) {
        MesChangePointConfigEntity config = configService.getOne(new LambdaQueryWrapper<MesChangePointConfigEntity>()
                .eq(MesChangePointConfigEntity::getConfigCode, configCode).last("LIMIT 1"), false);
        require(config != null, "变化点配置不存在");
        return config;
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
