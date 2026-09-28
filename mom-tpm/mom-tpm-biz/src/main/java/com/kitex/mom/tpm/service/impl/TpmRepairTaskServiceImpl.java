package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.mapper.*;
import com.kitex.mom.tpm.service.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 维修任务表
 *
 * @author kitex
 * @date 2025-02-06 16:06:41
 */
@Service
@AllArgsConstructor
@Slf4j
public class TpmRepairTaskServiceImpl extends ServiceImpl<TpmRepairTaskMapper, TpmRepairTaskEntity> implements TpmRepairTaskService {

    private final TpmRepairTaskDetailsService tpmRepairTaskDetailsService;
    private final TpmRepairRemindMapper tpmRepairRemindMapper;
    private final TpmRepairRemindDetailsMapper tpmRepairRemindDetailsMapper;
    private final RemoteDeviceService remoteDeviceService;
    private final TpmRepairNoticeService noticeService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final TpmMaintenanceTaskMapper tpmMaintenanceTaskMapper;
    private final TpmRepairRecordMapper tpmRepairRecordMapper;
    private final TpmSparePartsInOrOutRecordService tpmSparePartsInOrOutRecordService;
    private final TpmSparePartsInOrOutBillService tpmSparePartsInOrOutBillService;
    private final TpmSparePartsStockService tpmSparePartsStockService;
    private final TpmSparePartsService tpmSparePartsService;
    private final TpmSparePartsApplicationService tpmSparePartsApplicationService;
    private final TpmPlanEventOutboxService tpmPlanEventOutboxService;
    private final TpmRepairVerificationMapper verificationMapper;


    @Override
    public TpmRepairTaskEntity getTaskById(Long id) {
        //查询主表
        TpmRepairTaskEntity byId = this.getById(id);
        //查询子表
        List<TpmRepairTaskDetailsEntity> detailsList = tpmRepairTaskDetailsService.list(new LambdaQueryWrapper<TpmRepairTaskDetailsEntity>()
                .eq(TpmRepairTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveTask(TpmRepairTaskEntity tpmRepairTask) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tpmRepairTask == null || user == null || user.getId() == null || TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)
                || tpmRepairTask.getId() != null || StrUtil.isBlank(tpmRepairTask.getTaskCode()) || StrUtil.isBlank(tpmRepairTask.getDeviceCode())
                || StrUtil.isNotBlank(tpmRepairTask.getState()) || tpmRepairTask.getRepairTime() != null)
            throw new com.kitex.common.core.exception.CheckedException("报修须实名选择实际工厂、完整设备与新任务编码，不能携带已存在任务或完成状态");
        LocalDateTime now = LocalDateTime.now();
        if (tpmRepairTask.getExpectedRecoveryTime() != null && !tpmRepairTask.getExpectedRecoveryTime().isAfter(now)) {
            throw new RuntimeException("设备预计恢复时间必须晚于当前报修时间");
        }
        if (tpmRepairTask.getExpectedRecoveryTime() != null && StrUtil.isBlank(tpmRepairTask.getRecoveryForecastReason())) {
            throw new RuntimeException("填写设备预计恢复时间时必须同时提供预测依据");
        }
        // 两种报修通知模式共享同一权威设备及数据库原子故障阻断；提醒开关只决定是否通知。
        var query = new MdmDeviceEntity();
        query.setDeviceCode(tpmRepairTask.getDeviceCode());
        var deviceResponse = remoteDeviceService.getDetails(query);
        var device = deviceResponse == null ? null : deviceResponse.getData();
        if (deviceResponse == null || !deviceResponse.isOk() || device == null || !Objects.equals(device.getTenantId(), TenantContextHolder.getTenantId())
                || !factory.equals(device.getFactoryCode()) || !Boolean.TRUE.equals(device.getStatus())
                || !Set.of(DeviceStateEnum.IDLE.getCode(), DeviceStateEnum.RUNNING.getCode(), DeviceStateEnum.PAUSE.getCode(),
                DeviceStateEnum.MAINTENANCE.getCode(), DeviceStateEnum.REPAIR.getCode()).contains(Objects.toString(device.getDeviceState(), "")))
            throw new com.kitex.common.core.exception.CheckedException("设备不存在、跨工厂、已停用/报废或处于未知状态；须先核对MDM实际设备状态");
        tpmRepairTask.setFactoryCode(factory);
        tpmRepairTask.setTenantId(TenantContextHolder.getTenantId());
        tpmRepairTask.setDeviceName(device.getDeviceName());
        tpmRepairTask.setType(RepairTaskTypeEnum.INTERNAL.getCode());
        tpmRepairTask.setSubmitBy(user.getUsername());
        tpmRepairTask.setSubmitByName(user.getName());
        tpmRepairTask.setSubmitTime(now);
        tpmRepairTask.setState(RepairStateEnum.CREATE.getCode());
        tpmRepairTask.setRepairMethod(null);
        tpmRepairTask.setRepairResult(null);
        if (tpmRepairTask.getExpectedRecoveryTime() != null) {
            tpmRepairTask.setForecastUpdatedTime(now);
            tpmRepairTask.setForecastUpdatedBy(user.getUsername());
        }
        tpmRepairTask.setRemindSign(Boolean.TRUE.equals(tpmRepairTask.getRemindSign()));
        List<TpmRepairNoticeService.Draft> noticeDrafts = List.of();
        if (Boolean.TRUE.equals(tpmRepairTask.getRemindSign())) {
            //根据班次、设备、故障类型查询维修配置
            List<TpmRepairRemindEntity> targetRemindList = tpmRepairRemindMapper.getTarget(tpmRepairTask.getFaultClassId(), tpmRepairTask.getDeviceCode(), tpmRepairTask.getShiftCode());
            if (CollUtil.isEmpty(targetRemindList)) {
                throw new RuntimeException("根据故障类型、设备、班次未查询到相关的维修配置信息，请先维护！");
            }
            // 提前核定全部升级规则及收件人，任务保存后与报修同事务持久入箱。
            List<String> remindCodes = targetRemindList.stream().map(TpmRepairRemindEntity::getRemindCode).distinct().toList();
            List<TpmRepairRemindDetailsEntity> tpmRepairRemindDetailsList = tpmRepairRemindDetailsMapper.selectList(new LambdaQueryWrapper<TpmRepairRemindDetailsEntity>()
                    .in(TpmRepairRemindDetailsEntity::getRemindCode, remindCodes));
            noticeDrafts = noticeService.prepare(tpmRepairTask, tpmRepairRemindDetailsList);
        } else if (StrUtil.isBlank(tpmRepairTask.getRepairBy()) || StrUtil.isBlank(tpmRepairTask.getRepairByName()))
            throw new com.kitex.common.core.exception.CheckedException("关闭自动提醒时须指定实际维修处理人");
        if (!this.save(tpmRepairTask)) throw new com.kitex.common.core.exception.CheckedException("维修任务保存失败");
        // 第131节数据库触发器在本地事务中同步将真实MDM行置维修中/不可用并记录前后状态；不再在事务内调用远程MDM写接口。
        tpmPlanEventOutboxService.enqueueRepairUnavailable(tpmRepairTask);
        if (!noticeDrafts.isEmpty()) {
            var staged = noticeService.enqueue(tpmRepairTask, noticeDrafts);
            var immediate = staged.stream().filter(n -> "IMMEDIATE".equals(n.getNoticeKey())).findFirst().orElseThrow();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        noticeService.dispatchOne(immediate.getId(), immediate.getTenantId(), immediate.getFactoryCode());
                    } catch (RuntimeException ex) {
                        log.error("报修已提交，即时提醒暂留持久投递账，noticeId={}", immediate.getId(), ex);
                    }
                }
            });
        }
        return Boolean.TRUE;
    }


    /**
     * 处理维修任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handleTask(TpmRepairTaskEntity tpmRepairTask) {
        throw new com.kitex.common.core.exception.CheckedException("维修完成须先提交结果并由另一人独立验证，请使用维修验证工作台");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean completeVerifiedTask(Long verificationId) {
        var proof = verificationMapper.selectById(verificationId);
        if (proof == null || !"VERIFIED".equals(proof.getStatus()) || !Objects.equals(proof.getVerifierId(), SecurityUtils.getUser().getId()))
            throw new com.kitex.common.core.exception.CheckedException("缺少同事务独立维修验证依据");
        TpmRepairTaskEntity tpmRepairTask;
        try {
            tpmRepairTask = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(proof.getWorkJson(), TpmRepairTaskEntity.class);
        } catch (Exception ex) {
            throw new com.kitex.common.core.exception.CheckedException("冻结维修结果无法读取");
        }
        var current = this.getById(proof.getTaskId());
        if (current == null || !"0".equals(current.getState()) || !Objects.equals(proof.getTaskId(), tpmRepairTask.getId())
                || !Objects.equals(proof.getTenantId(), current.getTenantId()) || !Objects.equals(proof.getTenantId(), tpmRepairTask.getTenantId())
                || !Objects.equals(proof.getFactoryCode(), current.getFactoryCode()) || !Objects.equals(proof.getFactoryCode(), tpmRepairTask.getFactoryCode())
                || !Objects.equals(proof.getTaskCode(), current.getTaskCode()) || !Objects.equals(proof.getTaskCode(), tpmRepairTask.getTaskCode())
                || !Objects.equals(proof.getDeviceCode(), current.getDeviceCode()) || !Objects.equals(proof.getDeviceCode(), tpmRepairTask.getDeviceCode()))
            throw new com.kitex.common.core.exception.CheckedException("冻结维修结果与当前任务身份或状态不一致");
        //保存主表
        LocalDateTime now = LocalDateTime.now();
        MomUser user = SecurityUtils.getUser();
        tpmRepairTask.setRepairBy(proof.getApplicantCode());
        tpmRepairTask.setRepairByName(proof.getApplicantName());
        tpmRepairTask.setRepairTime(now);
        tpmRepairTask.setState(RepairStateEnum.COMPLETE.getCode());
        if (!this.updateById(tpmRepairTask))
            throw new com.kitex.common.core.exception.CheckedException("维修任务完成保存失败");
        //保存明细
        List<TpmRepairTaskDetailsEntity> detailsList = tpmRepairTask.getDetailsList();
        {
            //逻辑变更
            //1.保存维修备件使用明细
            detailsList.forEach(item -> {
                item.setId(null);
                item.setUseTime(now);
                item.setTaskCode(tpmRepairTask.getTaskCode());
            });
            if (CollUtil.isNotEmpty(detailsList) && !tpmRepairTaskDetailsService.saveBatch(detailsList))
                throw new com.kitex.common.core.exception.CheckedException("维修实际备件明细保存失败");

            //2.查询备件申请数据
            List<TpmSparePartsApplicationDetailsEntity> applyBySourceCode = tpmSparePartsApplicationService.getApplyBySourceCode(tpmRepairTask.getTaskCode(), SparePartsApplicationSourceEnum.FAULT.getCode());
            List<TpmSparePartsApplicationDetailsEntity> passApplicationDetailsList = applyBySourceCode.stream().filter(item -> item.getApprovalStatus()
                    .equals(SparePartsApplicationStateEnum.PASS.getCode())).toList();

            Map<String, TpmSparePartsApplicationDetailsEntity> applicationSparePartsMap = passApplicationDetailsList.stream().collect(Collectors.toMap(TpmSparePartsApplicationDetailsEntity::getSparePartCode, Function.identity(), (k1, k2) -> k1));

            Map<String, BigDecimal> applicationMap = passApplicationDetailsList.stream().collect(Collectors.groupingBy(
                    TpmSparePartsApplicationDetailsEntity::getSparePartCode,
                    Collectors.reducing(BigDecimal.ZERO, TpmSparePartsApplicationDetailsEntity::getQuantityRequested, BigDecimal::add)));

            //3.比对备件申请通过的备件数与备件使用的数据，如果备件没有全部使用，自动生成备件退回单据
            List<TpmSparePartsApplicationDetailsEntity> returnList = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : applicationMap.entrySet()) {
                TpmRepairTaskDetailsEntity entity = detailsList.stream().filter(item -> item.getSparePartsCode().equals(entry.getKey())).findFirst().orElse(null);
                if (ObjectUtil.isEmpty(entity)) {
                    TpmSparePartsApplicationDetailsEntity item = applicationSparePartsMap.get(entry.getKey());
                    returnList.add(buildApply(item, entry.getValue()));
                } else {
                    if (entry.getValue().compareTo(entity.getUseNum()) > 0) {
                        TpmSparePartsApplicationDetailsEntity item = applicationSparePartsMap.get(entry.getKey());
                        BigDecimal subtract = entry.getValue().subtract(entity.getUseNum());
                        returnList.add(buildApply(item, subtract));
                    }
                }

            }
            if (CollUtil.isNotEmpty(returnList)) {
                String applyCode = remoteSeqConfigService.create("spare_parts_application").getData();
                returnList.forEach(item -> {
                    item.setApplyCode(applyCode);
                });
                TpmSparePartsApplicationEntity application = new TpmSparePartsApplicationEntity();
                application.setApplyCode(applyCode);
                application.setType(BillOperateTypeEnum.IN.getCode());
                application.setSourceCode(tpmRepairTask.getTaskCode());
                application.setSource(SparePartsApplicationSourceEnum.FAULT_RETURN.getCode());
                application.setRequesterCode(user.getUsername());
                application.setRequesterName(user.getName());
                application.setRequestDate(LocalDate.now());
                application.setApprovalStatus(SparePartsApplicationStateEnum.WAIT.getCode());
                application.setDetailsList(returnList);
                //保存备件退回申请
                if (!tpmSparePartsApplicationService.addApply(application))
                    throw new com.kitex.common.core.exception.CheckedException("未用备件退回申请保存失败");

            }

        }
        //生成维修记录
        TpmRepairRecordEntity repairRecord = new TpmRepairRecordEntity();
        BeanUtils.copyProperties(tpmRepairTask, repairRecord, "id");
        if (tpmRepairRecordMapper.insert(repairRecord) != 1)
            throw new com.kitex.common.core.exception.CheckedException("维修记录保存失败");
        //保存备件信息

        //保存设备状态
        // 维修验证只结束本维修业务；不覆盖MDM的维修/保养/停用/报废资格，也不自动开机。
        // 未投递的过期异常会被取消；已投递事实保留，PLAN正式发布前会再次核验TPM实时状态。
        tpmPlanEventOutboxService.markRepairRecovered(tpmRepairTask);
        noticeService.cancelPendingForTask(tpmRepairTask);
        return Boolean.TRUE;
    }

    private TpmSparePartsApplicationDetailsEntity buildApply(TpmSparePartsApplicationDetailsEntity item, BigDecimal quantityRequested) {
        TpmSparePartsApplicationDetailsEntity entity = new TpmSparePartsApplicationDetailsEntity();
        entity.setSparePartCode(item.getSparePartCode());
        entity.setSparePartName(item.getSparePartName());
        entity.setSpecification(item.getSpecification());
        entity.setUnit(item.getUnit());
        entity.setQuantityRequested(quantityRequested);
        return entity;
    }


}
