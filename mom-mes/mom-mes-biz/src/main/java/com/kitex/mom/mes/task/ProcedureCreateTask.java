package com.kitex.mom.mes.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.QualityGateStatusEnum;
import com.kitex.mom.mes.mapper.MesInspectionGenRecordMapper;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.impl.MesQmsInspectionCoordinator;
import com.kitex.mom.mes.service.impl.MesQualityGateSourceEvidence;
import com.kitex.mom.mes.support.MesPatrolInterval;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.enums.InspectionMethodEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 质检任务生成定时任务
 *
 * @author kitex
 * @since 2025-04-18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcedureCreateTask {

    private final MesInspectionGenRecordService genRecordService;
    private final MesInspectionGenRecordMapper records;
    private final MesQmsInspectionCoordinator qmsInspectionCoordinator;
    private final MesQualityGateSourceEvidence sourceEvidence;
    private final RemoteClassesService remoteClassesService;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final MesProduceTaskService produceTaskService;
    private final RemoteUserService remoteUserService;
    private final RemoteMessagePushService messageService;
    private final MesInspectionRuleService mesInspectionRuleService;
    private final MesTaskService mesTaskService;
    private final BatchGeneratorService batchGeneratorService;
    private final RemoteParamService remoteParamService;

    /** 每个组织各自查班次和到期排程，避免共享执行器串用工厂日历。 */
    @XxlJob("qmsPatrolCreate")
    public void createTask() {
        if (RequestContextHolder.getRequestAttributes() != null
                || SecurityContextHolder.getContext().getAuthentication() != null
                || TenantContextHolder.getTenantId() != null || FactoryContextHolder.getFactoryCode() != null
                || TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("中检跨组织扫描必须由独立后台调度执行");
        }
        LocalDateTime observedAt = LocalDateTime.now();
        for (var organization : records.findPatrolOrganizations(observedAt)) {
            TenantContextHolder.setTenantId(organization.getTenantId());
            FactoryContextHolder.setFactoryCode(organization.getFactoryCode());
            try {
                processOrganization(observedAt);
            } catch (RuntimeException failure) {
                log.error("中检组织调度失败，tenantId={} factoryCode={}",
                        organization.getTenantId(), organization.getFactoryCode(), failure);
            } finally {
                FactoryContextHolder.clear();
                TenantContextHolder.clear();
            }
        }
    }

    private void processOrganization(LocalDateTime observedAt) {
        var due = genRecordService.list(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                .eq(MesInspectionGenRecordEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesInspectionGenRecordEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .and(where -> where.le(MesInspectionGenRecordEntity::getNextTime, observedAt)
                        .or(legacy -> legacy.isNull(MesInspectionGenRecordEntity::getNextTime)
                                .isNotNull(MesInspectionGenRecordEntity::getNextShift)
                                .ne(MesInspectionGenRecordEntity::getNextShift, "")))
                .orderByAsc(MesInspectionGenRecordEntity::getId));
        for (var record : due) {
            try {
                processDueRecord(record, observedAt);
            } catch (RuntimeException failure) {
                log.error("中检生成失败，recordId={} produceTaskCode={}",
                        record.getId(), record.getProduceTaskCode(), failure);
            }
        }
    }

    private void processDueRecord(MesInspectionGenRecordEntity record, LocalDateTime observedAt) {
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, record.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, record.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, record.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, record.getProduceTaskCode()));
        if (produceTask == null) {
            clearSchedule(record, "工序任务不存在，停止中检调度");
            return;
        }
        if (MesProduceTaskStatusEnum.FINISH.name().equals(produceTask.getStatus())
                || MesProduceTaskStatusEnum.CLOSE.name().equals(produceTask.getStatus())) {
            clearSchedule(record, "工序已结束，停止中检调度");
            return;
        }
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, record.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, record.getFactoryCode())
                .eq(MesTaskEntity::getCode, record.getTaskCode()));
        if (task == null) {
            throw new IllegalStateException("未找到中检记录对应生产工单");
        }
        processInspectionTask(record, produceTask, task, observedAt);
    }

    /**
     * 按原到期时刻或班次生成中检。停机后的后续排程仍从原计划推进。
     *
     * @param record      当前工序尚未处理的排程
     * @param produceTask 原工序任务
     * @param task        原工单及批次规则
     * @param observedAt  本次后台扫描时间
     */
    private void processInspectionTask(MesInspectionGenRecordEntity record, MesProduceTaskEntity produceTask,
                                       MesTaskEntity task, LocalDateTime observedAt) {
        LocalDateTime dueTime = record.getNextTime();
        String dueShift = record.getNextShift();
        boolean byShift = StrUtil.isNotBlank(dueShift);
        if (dueTime != null && dueTime.isAfter(observedAt)) {
            return;
        }
        String sourceRequestId = MesPatrolInterval.requestId(record);
        MesInspectionGenRecordEntity generated = genRecordService.getOne(
                new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getTenantId, record.getTenantId())
                        .eq(MesInspectionGenRecordEntity::getFactoryCode, record.getFactoryCode())
                        .eq(MesInspectionGenRecordEntity::getProduceTaskCode, record.getProduceTaskCode())
                        .eq(MesInspectionGenRecordEntity::getSourceRequestId, sourceRequestId));
        if (generated != null) {
            clearSchedule(record, null);
            return;
        }
        if (dueTime == null) {
            if (MesPatrolInterval.shiftDate(dueShift).isAfter(observedAt.toLocalDate())) {
                return;
            }
            dueTime = MesPatrolInterval.originalShift(
                    remoteClassesService.getPatrolSequence(dueShift, 1), dueShift).startsAt();
            if (dueTime.isAfter(observedAt)) {
                return;
            }
        }

        MesInspectionRuleEntity rule = mesInspectionRuleService.getOne(new LambdaQueryWrapper<MesInspectionRuleEntity>()
                .eq(MesInspectionRuleEntity::getTenantId, record.getTenantId())
                .eq(MesInspectionRuleEntity::getFactoryCode, record.getFactoryCode())
                .eq(MesInspectionRuleEntity::getCode, record.getRuleCode()));
        String method = byShift ? "SHIFT" : "HOUR";
        if (rule == null || !method.equals(rule.getMethod())) {
            throw new CheckedException("中检规则与原排程不一致，请核对原规则");
        }
        MesPatrolInterval.validate(rule);
        MesInspectionGenRecordEntity newRecord = new MesInspectionGenRecordEntity()
                .setProduceTaskCode(record.getProduceTaskCode()).setTaskCode(record.getTaskCode())
                .setInspectionType(ProcedureLabelTypeEnum.MIDDLE.name()).setSourceRequestId(sourceRequestId)
                .setRuleCode(record.getRuleCode()).setRuleName(record.getRuleName())
                .setGenTime(LocalDateTime.now()).setBlockedCount(0).setFactoryCode(produceTask.getFactoryCode());
        newRecord.setTenantId(record.getTenantId());
        if (byShift) {
            int count = MesPatrolInterval.shiftCount(rule.getNum());
            var next = MesPatrolInterval.nextShift(remoteClassesService.getPatrolSequence(dueShift, count), count, dueShift);
            if (!next.startsAt().isAfter(dueTime)) {
                throw new CheckedException("下一次中检班次早于原计划，请核对班次配置");
            }
            newRecord.setGenShiftCode(dueShift).setNextShift(next.code()).setNextTime(next.startsAt());
        } else {
            newRecord.setNextTime(MesPatrolInterval.nextTime(dueTime, rule.getNum()));
        }

        Long taskId = null;
        String failure = null;
        try {
            var command = sourceEvidence.replay(newRecord, produceTask, task, sourceRequestId,
                    ProcedureLabelTypeEnum.MIDDLE.name());
            if (command == null) {
                command = buildCommand(newRecord, produceTask, task, byShift ? dueShift : null, observedAt);
            }
            taskId = qmsInspectionCoordinator.create(command, produceTask.getFactoryCode());
        } catch (Exception exception) {
            failure = rootMessage(exception);
        }

        if (taskId == null) {
            newRecord.setGenFlag("0").setGenRemark("中检任务生成失败，请沿原号核对")
                    .setGateStatus(QualityGateStatusEnum.ERROR.name()).setSyncMessage(failure);
        } else {
            newRecord.setInspectionId(taskId).setGenFlag("1").setGenRemark("生成成功")
                    .setGateStatus(QualityGateStatusEnum.PENDING.name())
                    .setSyncMessage("等待中检结论");
        }
        if (!genRecordService.save(newRecord) || newRecord.getId() == null) {
            throw new CheckedException("中检生成记录未保存，保留原排程供核对");
        }
        clearSchedule(record, null);
        if (taskId == null) {
            try {
                sendMessage("工序任务" + produceTask.getCode() + "创建中检失败：" + failure
                        + "。请在质量门台账修复条件后重试", produceTask.getCreateBy());
            } catch (RuntimeException messageFailure) {
                log.warn("中检异常通知未送达，生成记录已保存，recordId={}", newRecord.getId(), messageFailure);
            }
        }
    }

    private InspectionProcedureCreateParam buildCommand(MesInspectionGenRecordEntity record,
                                                        MesProduceTaskEntity process, MesTaskEntity task,
                                                        String dueShift, LocalDateTime observedAt) {
        var response = remoteQualityStandardService.getPatrolStandard(process.getMatCode());
        var standard = response == null || !response.isOk() ? null : response.getData();
        if (standard == null || standard.getId() == null || standard.getId() <= 0
                || !Integer.valueOf(1).equals(standard.getPublished())
                || !Objects.equals(record.getTenantId(), standard.getTenantId())
                || !Objects.equals(record.getFactoryCode(), standard.getFactoryCode())
                || !Objects.equals(process.getMatCode(), standard.getMatCode())
                || !InspectionTaskTypeEnum.PROCEDURE.name().equals(standard.getInspectionType())) {
            throw new CheckedException("未匹配到当前工厂已发布的工序质检标准");
        }
        String batchNo = "";
        BatchGenerator generator = batchGeneratorService.getBatchGenerator(task.getBatchRuleCode());
        if (generator != null) {
            String batchShift = dueShift;
            if (batchShift == null) {
                var shift = remoteClassesService.getPatrolShift(RemoteClassesService.RULE_DATE_TIME_FORMAT.format(observedAt));
                if (shift == null || !shift.isOk() || StrUtil.isBlank(shift.getData())) {
                    throw new CheckedException("当前工厂班次未确认，无法生成中检批次");
                }
                batchShift = shift.getData();
            }
            batchNo = generator.generateBatchNumber(process.getTaskCode(), batchShift);
        }
        var command = new InspectionProcedureCreateParam()
                .setNum(Math.max(1, process.getNoticeNum().intValue()))
                .setTaskCode(process.getCode()).setSourceTaskCode(process.getCode())
                .setSourceRequestId(record.getSourceRequestId()).setMatCode(process.getMatCode())
                .setMatName(process.getMatName()).setMatSpec(process.getMatSpec())
                .setProcessCode(process.getRouteCode()).setProcessName(process.getRouteName())
                .setBatchNo(batchNo).setStandardId(standard.getId()).setDeptCode(process.getWorkshopCode())
                .setOrderNo(process.getTaskCode()).setOrderId(String.valueOf(task.getId()))
                .setLabelType(ProcedureLabelTypeEnum.MIDDLE.name());
        if (InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
            String inspectionNum = remoteParamService.getByKey("QMS_DEFAULT_NUM").getData();
            if (StrUtil.isBlank(inspectionNum)) {
                throw new CheckedException("未配置QMS_DEFAULT_NUM抽检数量");
            }
            command.setInspectionNum(Integer.parseInt(inspectionNum));
        }
        return command;
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    /** 只清除本次读取的排程，显式写入 NULL，避免实体更新忽略空值。 */
    private void clearSchedule(MesInspectionGenRecordEntity record, String message) {
        var update = new LambdaUpdateWrapper<MesInspectionGenRecordEntity>()
                .eq(MesInspectionGenRecordEntity::getId, record.getId())
                .eq(MesInspectionGenRecordEntity::getTenantId, record.getTenantId())
                .eq(MesInspectionGenRecordEntity::getFactoryCode, record.getFactoryCode())
                .eq(record.getNextTime() != null, MesInspectionGenRecordEntity::getNextTime, record.getNextTime())
                .isNull(record.getNextTime() == null, MesInspectionGenRecordEntity::getNextTime)
                .eq(record.getNextShift() != null, MesInspectionGenRecordEntity::getNextShift, record.getNextShift())
                .isNull(record.getNextShift() == null, MesInspectionGenRecordEntity::getNextShift)
                .set(MesInspectionGenRecordEntity::getNextTime, null)
                .set(MesInspectionGenRecordEntity::getNextShift, null)
                .set(message != null, MesInspectionGenRecordEntity::getSyncMessage, message);
        if (!genRecordService.update(update)) {
            throw new CheckedException("中检原排程已变化，请重新读取");
        }
    }

    /**
     * 发送钉钉消息
     *
     * @param message
     * @param users
     */
    private void sendMessage(String message, String users) {
        List<Long> userIdsList = Arrays.stream(users.split(",")).map(Long::parseLong).toList();
        List<SysUserEntity> userList = remoteUserService.getUserByIdInner(userIdsList.stream().toList()).getData();
        if (CollUtil.isEmpty(userList)) {
            throw new RuntimeException("查询用户数据失败！");
        }
        String userIds = userList.stream().map(SysUserEntity::getWxDingUserid).collect(Collectors.joining(","));
        //构建参数推送消息
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setCode("MES");
        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
        messageDTO.setUserList(userIdsList);
        messageDTO.setUserIds(userIds);
        messageDTO.setMessage(message);
        messageService.pushPersonMsg(messageDTO);
    }
}
