package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.dto.AndonNoticeResp;
import com.kitex.mom.mes.dto.AndonRecordCancelVO;
import com.kitex.mom.mes.dto.AndonRecordHandleVO;
import com.kitex.mom.mes.dto.AndonRecordVerifyVO;
import com.kitex.mom.mes.entity.MesAndonHandleConfigEntity;
import com.kitex.mom.mes.entity.MesAndonHandleConfigStaffEntity;
import com.kitex.mom.mes.entity.MesAndonNoticeEntity;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.AndonNoticeTypeEnum;
import com.kitex.mom.mes.enums.AndonStateEnum;
import com.kitex.mom.mes.mapper.MesAndonRecordMapper;
import com.kitex.mom.mes.service.MesAndonHandleConfigService;
import com.kitex.mom.mes.service.MesAndonHandleConfigStaffService;
import com.kitex.mom.mes.service.MesAndonNoticeService;
import com.kitex.mom.mes.service.MesAndonOperationService;
import com.kitex.mom.mes.service.MesAndonRecordService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 安灯事件全生命周期服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesAndonRecordServiceImpl extends ServiceImpl<MesAndonRecordMapper, MesAndonRecordEntity>
        implements MesAndonRecordService {

    private static final String MES = "MES";
    private static final String PASS = "PASS";
    private static final String REJECT = "REJECT";

    private final MesAndonNoticeService noticeService;
    private final RemoteClassesService classesService;
    private final RemoteSeqConfigService seqConfigService;
    private final MesAndonHandleConfigService configService;
    private final MesAndonHandleConfigStaffService staffService;
    private final MesAndonOperationService operationService;
    private final MesTaskArrangeService taskArrangeService;
    private final RemoteMessagePushService messageService;
    private final RemoteUserService remoteUserService;
    private final MesAndonProductionReleaseService productionReleaseService;
    private final MesAndonPauseCoordinator pauseCoordinator;

    @Override
    public IPage<AndonNoticeResp> noticePage(Page<AndonNoticeResp> pageParam, AndonNoticeResp query) {
        String factory = productionReleaseService.authorizeFactory("read");
        MomUser sysUser = SecurityUtils.getUser();
        return baseMapper.noticePage(pageParam, query, String.valueOf(sysUser.getId()), Arrays.asList(
                AndonStateEnum.UN_RECEIVE.getCode(), AndonStateEnum.UP_UN_RECEIVE.getCode(),
                AndonStateEnum.UN_HANDLE.getCode(), AndonStateEnum.UP_UN_HANDLE.getCode()), factory);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesAndonRecordEntity saveData(MesAndonRecordEntity command) {
        require(command != null, "安灯请求不能为空");
        require(StrUtil.isNotBlank(command.getTypeCode()), "异常类型不能为空");
        require(StrUtil.isNotBlank(command.getLevel()), "异常级别不能为空");
        require(StrUtil.isNotBlank(command.getExDesc()), "异常描述不能为空");

        MomUser user = SecurityUtils.getUser();
        String factory = productionReleaseService.authorizeFactory("write");
        String fingerprint = productionReleaseService.creationFingerprint(command);
        String requestId = StrUtil.blankToDefault(command.getRequestId(), IdUtil.fastSimpleUUID());
        require(requestId.length() <= 64, "安灯请求号不能超过64字");
        MesAndonRecordEntity existing = this.getOne(new LambdaQueryWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getRequestId, requestId)
                .last("limit 1"));
        if (existing != null) {
            require(Objects.equals(existing.getFactoryCode(), factory) && Objects.equals(existing.getRequestFingerprint(), fingerprint),
                    "请求号已用于不同安灯命令或历史记录缺少命令证据，请核对原单");
            return existing;
        }

        require(!Boolean.TRUE.equals(command.getImpactProduction()) || StrUtil.isNotBlank(command.getArrangeCode()),
                "影响生产的安灯必须关联明确派工单");
        enrichArrangeLink(command);
        R<MdmClassesEntity> shiftResponse = classesService.getBanci();
        MdmClassesEntity currentShift = shiftResponse == null ? null : shiftResponse.getData();
        require(currentShift != null && StrUtil.isNotBlank(currentShift.getShiftCode()), "当前时间未匹配到有效班次");

        MesAndonHandleConfigEntity config = resolveConfig(command, currentShift.getShiftCode());

        List<MesAndonHandleConfigStaffEntity> staffList = staffService.list(
                new LambdaQueryWrapper<MesAndonHandleConfigStaffEntity>()
                        .eq(MesAndonHandleConfigStaffEntity::getConfigCode, config.getCode())
                        .eq(MesAndonHandleConfigStaffEntity::getLevel, command.getLevel()));
        staffList.sort(Comparator.comparingInt(item -> parseStep(item.getSort())));
        require(!staffList.isEmpty() && parseStep(staffList.get(0).getSort()) == 0,
                "安灯流程未配置初始处理人");
        MesAndonHandleConfigStaffEntity initialStaff = staffList.get(0);
        require(StrUtil.isNotBlank(initialStaff.getStaffCode()), "安灯流程初始处理人为空");
        require(initialStaff.getLimitTime() != null && initialStaff.getLimitTime() > 0,
                "安灯流程初始响应时限未配置");

        LocalDateTime now = LocalDateTime.now();
        MesAndonRecordEntity result = new MesAndonRecordEntity();
        result.setRequestId(requestId);
        result.setRequestFingerprint(fingerprint);
        result.setFactoryCode(factory);
        result.setProductionReleaseRequired(Boolean.TRUE.equals(command.getImpactProduction()));
        result.setState(AndonStateEnum.UN_RECEIVE.getCode());
        R<String> codeResponse = seqConfigService.create("mes_andon_code");
        result.setCode(codeResponse == null ? null : codeResponse.getData());
        require(StrUtil.isNotBlank(result.getCode()), "安灯流程编号生成失败");
        result.setConfigCode(config.getCode());
        result.setTypeCode(config.getTypeCode());
        result.setTypeName(config.getTypeName());
        result.setWorkshopCode(StrUtil.blankToDefault(command.getWorkshopCode(), config.getWorkshopCode()));
        result.setWorkshopName(Objects.equals(result.getWorkshopCode(), config.getWorkshopCode())
                ? config.getWorkshopName() : command.getWorkshopName());
        result.setLineCode(StrUtil.blankToDefault(command.getLineCode(), config.getLineCode()));
        result.setLineName(Objects.equals(result.getLineCode(), config.getLineCode())
                ? config.getLineName() : command.getLineName());
        result.setShiftCode(currentShift.getShiftCode());
        result.setShiftName(currentShift.getShiftName());
        result.setLevel(command.getLevel());
        result.setFaultCode(command.getFaultCode());
        result.setFaultName(command.getFaultName());
        result.setExDesc(command.getExDesc());
        result.setTaskCode(command.getTaskCode());
        result.setProduceTaskCode(command.getProduceTaskCode());
        result.setArrangeCode(command.getArrangeCode());
        result.setDeviceCode(command.getDeviceCode());
        result.setWorksiteCode(command.getWorksiteCode());
        result.setImpactProduction(Boolean.TRUE.equals(command.getImpactProduction()));
        result.setProductionPaused(false);
        result.setHandlerName(initialStaff.getStaffName());
        result.setHandlerCode(initialStaff.getStaffCode());
        result.setCreateByName(user.getName());
        result.setResponseDeadline(now.plusMinutes(initialStaff.getLimitTime()));
        result.setEscalationStep(0);
        result.setEscalationCount(0);
        result.setNextEscalationTime(staffList.size() > 1 ? result.getResponseDeadline() : null);

        this.save(result);
        createNotices(result, initialStaff, AndonNoticeTypeEnum.HANDLE.getCode(), now);

        if (Boolean.TRUE.equals(result.getImpactProduction())) {
            result.setProductionPauseResult("生产逻辑阻断已建立，提交后尝试暂停；暂停失败仍阻断新报工/开工，须人工确认设备安全");
            this.updateById(result);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { pauseCoordinator.attempt(result.getId()); }
                    });
        }

        saveOperation(result, requestId, "CREATE", null, result.getState(),
                "发起安灯；生产联动=" + StrUtil.blankToDefault(result.getProductionPauseResult(), "不影响生产"));
        safeSend(buildMessage(result, "请及时接收"), initialStaff.getStaffCode());
        return result;
    }

    /**
     * 按“产线 > 车间 > 全局”选择唯一配置，避免数据库返回顺序导致流程随机命中。
     */
    private MesAndonHandleConfigEntity resolveConfig(MesAndonRecordEntity command, String shiftCode) {
        List<MesAndonHandleConfigEntity> configs = configService.list(
                new LambdaQueryWrapper<MesAndonHandleConfigEntity>()
                        .eq(MesAndonHandleConfigEntity::getTypeCode, command.getTypeCode())
                        .eq(MesAndonHandleConfigEntity::getFactoryCode, com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode())
                        .apply("{0} = ANY(regexp_split_to_array(shift_code, '\\s*,\\s*'))", shiftCode));
        List<MesAndonHandleConfigEntity> matched = configs.stream()
                .filter(item -> scopeMatches(item.getWorkshopCode(), command.getWorkshopCode()))
                .filter(item -> scopeMatches(item.getLineCode(), command.getLineCode()))
                .toList();
        require(!matched.isEmpty(), "当前异常类型、班次及生产范围未配置安灯处理流程");
        int highestSpecificity = matched.stream().mapToInt(this::configSpecificity).max().orElse(0);
        List<MesAndonHandleConfigEntity> preferred = matched.stream()
                .filter(item -> configSpecificity(item) == highestSpecificity)
                .toList();
        require(preferred.size() == 1,
                "当前异常类型、班次及生产范围匹配到多条同优先级安灯配置，请合并或停用重复配置");
        return preferred.get(0);
    }

    private boolean scopeMatches(String configuredCode, String actualCode) {
        return StrUtil.isBlank(configuredCode) || Objects.equals(configuredCode, actualCode);
    }

    private int configSpecificity(MesAndonHandleConfigEntity config) {
        int score = 0;
        if (StrUtil.isNotBlank(config.getWorkshopCode())) {
            score++;
        }
        if (StrUtil.isNotBlank(config.getLineCode())) {
            score += 2;
        }
        return score;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(AndonRecordCancelVO vo) {
        require(vo != null && vo.getId() != null, "安灯记录不能为空");
        require(StrUtil.isNotBlank(vo.getReason()), "撤销原因不能为空");
        MesAndonRecordEntity record = lockRecord(vo.getId());
        MomUser user = SecurityUtils.getUser();
        require(isCreator(record, user), "必须由安灯发起人撤销");
        if (AndonStateEnum.CANCEL.getCode().equals(record.getState())) {
            return;
        }
        require(isUnreceived(record.getState()), "仅未接收的安灯可以撤销");
        String requestId = StrUtil.blankToDefault(vo.getRequestId(), IdUtil.fastSimpleUUID());
        if (hasOperation(record.getId(), requestId)) {
            return;
        }
        String before = record.getState();
        record.setState(AndonStateEnum.CANCEL.getCode());
        record.setCancelReason(vo.getReason());
        record.setNextEscalationTime(null);
        this.updateById(record);
        saveOperation(record, requestId, "CANCEL", before, record.getState(), vo.getReason());
        safeSend(buildMessage(record, "发起人已撤销，原因：" + vo.getReason()), allNotifiedUsers(record.getCode()));
    }

    /**
     * 兼容旧入口：关闭等同于发起人验证通过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id) {
        AndonRecordVerifyVO vo = new AndonRecordVerifyVO();
        vo.setId(id);
        vo.setRequestId(IdUtil.fastSimpleUUID());
        vo.setVerificationResult(PASS);
        vo.setVerificationRemark("发起人确认处理有效");
        verify(vo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long id) {
        MesAndonRecordEntity record = lockRecord(id);
        MomUser user = SecurityUtils.getUser();
        if (!isUnreceived(record.getState())) {
            if (Objects.equals(record.getReceiverCode(), String.valueOf(user.getId()))) {
                return;
            }
            throw new CheckedException("该安灯已被其他人员接收或不在待接收状态");
        }
        require(csvContains(record.getHandlerCode(), String.valueOf(user.getId())), "必须由当前指定处理人接收");

        LocalDateTime now = LocalDateTime.now();
        String before = record.getState();
        record.setReceiverName(user.getName());
        record.setReceiverCode(String.valueOf(user.getId()));
        record.setState(AndonStateEnum.UP_UN_RECEIVE.getCode().equals(before)
                ? AndonStateEnum.UP_UN_HANDLE.getCode() : AndonStateEnum.UN_HANDLE.getCode());
        record.setReceiveTime(now);
        int limitMinutes = initialLimitMinutes(record.getConfigCode(), record.getLevel());
        record.setHandleDeadline(now.plusMinutes(limitMinutes));
        record.setNextEscalationTime(hasNextEscalation(record) ? record.getHandleDeadline() : null);
        this.updateById(record);
        saveOperation(record, IdUtil.fastSimpleUUID(), "RECEIVE", before, record.getState(), "处理人接收");
        safeSend(buildMessage(record, "处理人" + user.getName() + "已接收"), allNotifiedUsers(record.getCode()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(AndonRecordHandleVO vo) {
        require(vo != null && vo.getId() != null, "安灯记录不能为空");
        require(StrUtil.isNotBlank(vo.getRootCause()), "根本原因不能为空");
        require(StrUtil.isNotBlank(vo.getCorrectiveAction()), "纠正措施不能为空");
        require(StrUtil.isNotBlank(vo.getResult()), "处理结果不能为空");
        MesAndonRecordEntity record = lockRecord(vo.getId());
        MomUser user = SecurityUtils.getUser();
        require(Objects.equals(record.getReceiverCode(), String.valueOf(user.getId())), "必须由实际接收人处理");
        require(isHandling(record.getState()), "仅接收后的安灯可以提交处理结果");
        String requestId = StrUtil.blankToDefault(vo.getRequestId(), IdUtil.fastSimpleUUID());
        if (hasOperation(record.getId(), requestId)) {
            return;
        }

        String before = record.getState();
        record.setState(AndonStateEnum.HANDLED.getCode());
        record.setHandleTime(LocalDateTime.now());
        record.setRootCause(vo.getRootCause());
        record.setCorrectiveAction(vo.getCorrectiveAction());
        record.setResult(vo.getResult());
        record.setNextEscalationTime(null);
        record.setVerificationResult(null);
        record.setVerificationRemark(null);
        this.updateById(record);
        saveOperation(record, requestId, "HANDLE", before, record.getState(),
                "根因：" + vo.getRootCause() + "；措施：" + vo.getCorrectiveAction() + "；结果：" + vo.getResult());
        safeSendCreator(buildMessage(record, "处理已完成，请发起人验证"), record.getCreateBy());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verify(AndonRecordVerifyVO vo) {
        require(vo != null && vo.getId() != null, "安灯记录不能为空");
        require(PASS.equals(vo.getVerificationResult()) || REJECT.equals(vo.getVerificationResult()),
                "验证结果必须为PASS或REJECT");
        require(StrUtil.isNotBlank(vo.getVerificationRemark()), "验证说明不能为空");
        MesAndonRecordEntity record = lockRecord(vo.getId());
        MomUser user = SecurityUtils.getUser();
        require(isCreator(record, user), "必须由安灯发起人验证处理结果");
        String requestId = StrUtil.blankToDefault(vo.getRequestId(), IdUtil.fastSimpleUUID());
        if (hasOperation(record.getId(), requestId)) {
            return;
        }
        require(AndonStateEnum.HANDLED.getCode().equals(record.getState()), "仅已处理的安灯可以验证");

        String before = record.getState();
        LocalDateTime now = LocalDateTime.now();
        record.setVerificationResult(vo.getVerificationResult());
        record.setVerificationRemark(vo.getVerificationRemark());
        record.setVerifierCode(String.valueOf(user.getId()));
        record.setVerifierName(user.getName());
        record.setVerificationTime(now);
        if (PASS.equals(vo.getVerificationResult())) {
            record.setState(AndonStateEnum.CLOSED.getCode());
            record.setCloseTime(now);
            record.setNextEscalationTime(null);
        } else {
            record.setState(AndonStateEnum.UN_HANDLE.getCode());
            record.setCloseTime(null);
            int limitMinutes = initialLimitMinutes(record.getConfigCode(), record.getLevel());
            record.setHandleDeadline(now.plusMinutes(limitMinutes));
            record.setEscalationStep(0);
            record.setNextEscalationTime(hasNextEscalation(record) ? record.getHandleDeadline() : null);
        }
        this.updateById(record);
        String action = PASS.equals(vo.getVerificationResult()) ? "VERIFY_PASS" : "VERIFY_REJECT";
        saveOperation(record, requestId, action, before, record.getState(), vo.getVerificationRemark());
        safeSend(buildMessage(record, PASS.equals(vo.getVerificationResult())
                        ? "验证通过，事件已闭环；生产恢复仍需人工确认" : "验证未通过，已退回重新处理"),
                StrUtil.join(",", record.getHandlerCode(), record.getReceiverCode()));
    }

    @Override
    public List<MesAndonOperationEntity> listOperations(Long recordId) {
        var event = productionReleaseService.readScopedEvent(recordId);
        return operationService.list(new LambdaQueryWrapper<MesAndonOperationEntity>()
                .eq(MesAndonOperationEntity::getRecordId, recordId)
                .eq(MesAndonOperationEntity::getFactoryCode, event.getFactoryCode())
                .orderByAsc(MesAndonOperationEntity::getOperationTime)
                .orderByAsc(MesAndonOperationEntity::getId));
    }

    private void enrichArrangeLink(MesAndonRecordEntity record) {
        if (StrUtil.isBlank(record.getArrangeCode())) {
            return;
        }
        MesTaskArrangeEntity arrange = productionReleaseService.lockLinkedArrange(record.getArrangeCode(),
                com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode());
        require(StrUtil.isBlank(record.getTaskCode()) || Objects.equals(record.getTaskCode(), arrange.getTaskCode()), "工单与派工不一致");
        require(StrUtil.isBlank(record.getProduceTaskCode()) || Objects.equals(record.getProduceTaskCode(), arrange.getProduceTaskCode()), "工序任务与派工不一致");
        require(StrUtil.isBlank(record.getDeviceCode()) || Objects.equals(record.getDeviceCode(), arrange.getPlannedDeviceCode()), "设备与派工计划设备不一致");
        require(StrUtil.isBlank(record.getWorksiteCode()) || Objects.equals(record.getWorksiteCode(), arrange.getActualWorksiteCode()), "工位与派工实际工位不一致");
        require(StrUtil.isBlank(record.getWorkshopCode()) || Objects.equals(record.getWorkshopCode(), arrange.getWorkshopCode()), "车间与派工不一致");
        record.setTaskCode(StrUtil.blankToDefault(record.getTaskCode(), arrange.getTaskCode()));
        record.setProduceTaskCode(StrUtil.blankToDefault(record.getProduceTaskCode(), arrange.getProduceTaskCode()));
        record.setDeviceCode(StrUtil.blankToDefault(record.getDeviceCode(), arrange.getPlannedDeviceCode()));
        record.setWorksiteCode(StrUtil.blankToDefault(record.getWorksiteCode(), arrange.getActualWorksiteCode()));
        record.setWorkshopCode(StrUtil.blankToDefault(record.getWorkshopCode(), arrange.getWorkshopCode()));
        record.setWorkshopName(StrUtil.blankToDefault(record.getWorkshopName(), arrange.getWorkshopName()));
    }

    private MesAndonRecordEntity lockRecord(Long id) {
        require(id != null, "安灯记录不能为空");
        String factory = productionReleaseService.authorizeFactory("write");
        MesAndonRecordEntity record = this.getOne(new LambdaQueryWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getId, id).eq(MesAndonRecordEntity::getFactoryCode, factory).last("FOR UPDATE"));
        if (record == null) {
            throw new CheckedException("安灯记录不存在");
        }
        return record;
    }

    private int initialLimitMinutes(String configCode, String level) {
        MesAndonHandleConfigStaffEntity initial = staffService.getOne(
                new LambdaQueryWrapper<MesAndonHandleConfigStaffEntity>()
                        .eq(MesAndonHandleConfigStaffEntity::getConfigCode, configCode)
                        .eq(MesAndonHandleConfigStaffEntity::getLevel, level)
                        .eq(MesAndonHandleConfigStaffEntity::getSort, "0")
                        .last("limit 1"));
        return initial == null || initial.getLimitTime() == null || initial.getLimitTime() <= 0
                ? 30 : initial.getLimitTime();
    }

    private boolean hasNextEscalation(MesAndonRecordEntity record) {
        int currentStep = record.getEscalationStep() == null ? 0 : record.getEscalationStep();
        return staffService.list(new LambdaQueryWrapper<MesAndonHandleConfigStaffEntity>()
                        .eq(MesAndonHandleConfigStaffEntity::getConfigCode, record.getConfigCode())
                        .eq(MesAndonHandleConfigStaffEntity::getLevel, record.getLevel()))
                .stream().anyMatch(item -> parseStep(item.getSort()) > currentStep);
    }

    private void createNotices(MesAndonRecordEntity record, MesAndonHandleConfigStaffEntity staff,
                               String noticeType, LocalDateTime now) {
        List<String> codes = splitCsv(staff.getStaffCode());
        List<String> names = splitCsv(staff.getStaffName());
        List<MesAndonNoticeEntity> notices = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            long count = noticeService.count(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                    .eq(MesAndonNoticeEntity::getRecordCode, record.getCode())
                    .eq(MesAndonNoticeEntity::getStaffCode, code)
                    .eq(MesAndonNoticeEntity::getNoticeType, noticeType));
            if (count > 0) {
                continue;
            }
            MesAndonNoticeEntity notice = new MesAndonNoticeEntity();
            notice.setTenantId(record.getTenantId());
            notice.setFactoryCode(record.getFactoryCode());
            notice.setNoticeTime(now);
            notice.setNoticeType(noticeType);
            notice.setStaffCode(code);
            notice.setStaffName(i < names.size() ? names.get(i) : code);
            notice.setRecordCode(record.getCode());
            notices.add(notice);
        }
        if (!notices.isEmpty()) {
            noticeService.saveBatch(notices);
        }
    }

    private boolean hasOperation(Long recordId, String requestId) {
        return operationService.count(new LambdaQueryWrapper<MesAndonOperationEntity>()
                .eq(MesAndonOperationEntity::getRecordId, recordId)
                .eq(MesAndonOperationEntity::getRequestId, requestId)) > 0;
    }

    private void saveOperation(MesAndonRecordEntity record, String requestId, String operationType,
                               String beforeState, String afterState, String content) {
        MomUser user = SecurityUtils.getUser();
        var operation = new MesAndonOperationEntity()
                .setRequestId(requestId)
                .setRecordId(record.getId())
                .setRecordCode(record.getCode())
                .setOperationType(operationType)
                .setBeforeState(beforeState)
                .setAfterState(afterState)
                .setContent(StrUtil.subPre(StrUtil.nullToEmpty(content), 2000))
                .setOperatorCode(String.valueOf(user.getId()))
                .setOperatorName(user.getName())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(record.getFactoryCode());
        operation.setTenantId(record.getTenantId());
        operationService.save(operation);
    }

    private String allNotifiedUsers(String recordCode) {
        return noticeService.list(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                        .eq(MesAndonNoticeEntity::getRecordCode, recordCode))
                .stream().map(MesAndonNoticeEntity::getStaffCode).filter(StrUtil::isNotBlank)
                .flatMap(value -> splitCsv(value).stream()).distinct().collect(Collectors.joining(","));
    }

    private boolean isCreator(MesAndonRecordEntity record, MomUser user) {
        return Objects.equals(record.getCreateBy(), user.getUsername())
                || Objects.equals(record.getCreateBy(), String.valueOf(user.getId()));
    }

    private boolean isUnreceived(String state) {
        return AndonStateEnum.UN_RECEIVE.getCode().equals(state)
                || AndonStateEnum.UP_UN_RECEIVE.getCode().equals(state);
    }

    private boolean isHandling(String state) {
        return AndonStateEnum.UN_HANDLE.getCode().equals(state)
                || AndonStateEnum.UP_UN_HANDLE.getCode().equals(state);
    }

    private boolean csvContains(String csv, String value) {
        return splitCsv(csv).contains(value);
    }

    private List<String> splitCsv(String value) {
        if (StrUtil.isBlank(value)) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).toList();
    }

    private int parseStep(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private String buildMessage(MesAndonRecordEntity record, String action) {
        return "安灯呼叫通知：\n流程编号：" + record.getCode()
                + "\n时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "\n异常类型：" + record.getTypeName()
                + "\n车间：" + StrUtil.nullToEmpty(record.getWorkshopName())
                + "\n产线：" + StrUtil.nullToEmpty(record.getLineName())
                + "\n\n" + action;
    }

    private void safeSend(String message, String users) {
        try {
            sendMessage(message, users);
        } catch (Exception ex) {
            log.error("安灯消息推送失败, users={}", users, ex);
        }
    }

    private void safeSendCreator(String message, String username) {
        try {
            if (StrUtil.isBlank(username)) {
                return;
            }
            R<List<SysUserEntity>> response = remoteUserService.getUserListByUserName(username);
            List<SysUserEntity> users = response == null ? null : response.getData();
            if (CollUtil.isEmpty(users)) {
                return;
            }
            safeSend(message, users.stream().map(SysUserEntity::getUserId).filter(Objects::nonNull)
                    .map(String::valueOf).collect(Collectors.joining(",")));
        } catch (Exception ex) {
            log.error("安灯发起人消息推送失败, username={}", username, ex);
        }
    }

    private void sendMessage(String message, String users) {
        List<Long> userIds = splitCsv(users).stream().map(value -> {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }).filter(Objects::nonNull).distinct().toList();
        if (CollUtil.isEmpty(userIds)) {
            return;
        }
        R<List<SysUserEntity>> response = remoteUserService.getUserByIdInner(userIds);
        List<SysUserEntity> userList = response == null ? null : response.getData();
        if (CollUtil.isEmpty(userList)) {
            throw new CheckedException("查询消息接收人失败");
        }
        String dingUserIds = userList.stream().map(SysUserEntity::getWxDingUserid)
                .filter(StrUtil::isNotBlank).collect(Collectors.joining(","));
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setCode(MES);
        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
        messageDTO.setUserList(userIds);
        messageDTO.setUserIds(dingUserIds);
        messageDTO.setMessage(message);
        messageService.pushPersonMsg(messageDTO);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
