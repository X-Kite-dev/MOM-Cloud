package com.kitex.mom.mes.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.batch.BatchGenerator;
import com.kitex.mom.mes.batch.BatchGeneratorService;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.QualityGateStatusEnum;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.impl.MesQmsInspectionCoordinator;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.enums.InspectionMethodEnum;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Date;
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
    private final MesQmsInspectionCoordinator qmsInspectionCoordinator;
    private final RemoteClassesService remoteClassesService;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final MesProduceTaskService produceTaskService;
    private final RemoteUserService remoteUserService;
    private final RemoteMessagePushService messageService;
    private final MesInspectionRuleService mesInspectionRuleService;
    private final MesTaskService mesTaskService;
    private final BatchGeneratorService batchGeneratorService;
    private final RemoteParamService remoteParamService;

    @XxlJob("qmsPatrolCreate")
    public void createTask() {
        try {
            log.info("定时生成工序质检任务，开始时间：{}", DateUtil.format(new Date(), "yyyy-MM-dd HH:mm"));
            Date currentDate = new Date();

            // 获取当前班次
            String currentShiftCode = remoteClassesService.getRuleBanci(LocalDateTime.now()).getData();

            // 查询下次执行时间在此次定时任务执行期间的任务
            List<MesInspectionGenRecordEntity> recordList = genRecordService.list(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                    .isNotNull(MesInspectionGenRecordEntity::getNextTime)
                    .le(MesInspectionGenRecordEntity::getNextTime, currentDate));

            // 查询下次班组在此次定时任务执行期间的任务
            List<MesInspectionGenRecordEntity> shiftRecordList = genRecordService.list(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                    .eq(MesInspectionGenRecordEntity::getNextShift, currentShiftCode));

            // 循环创建
            recordList.forEach(record -> {
                try {
                    processDueRecord(record, currentShiftCode);
                } catch (Exception exception) {
                    log.error("按时间生成中检任务失败, recordId={}, produceTaskCode={}",
                            record.getId(), record.getProduceTaskCode(), exception);
                }
            });

            shiftRecordList.forEach(shiftRecord -> {
                try {
                    processDueRecord(shiftRecord, currentShiftCode);
                } catch (Exception exception) {
                    log.error("按班次生成中检任务失败, recordId={}, produceTaskCode={}",
                            shiftRecord.getId(), shiftRecord.getProduceTaskCode(), exception);
                }
            });

            log.info("定时生成工序质检任务。");
        } catch (Exception e) {
            log.error("定时生成工序质检任务失败", e);
        }
    }

    private void processDueRecord(MesInspectionGenRecordEntity record, String currentShiftCode) {
        MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, record.getProduceTaskCode()));
        if (produceTask == null || MesProduceTaskStatusEnum.FINISH.name().equals(produceTask.getStatus())) {
            record.setNextTime(null).setNextShift(null);
            record.setSyncMessage(produceTask == null ? "工序任务不存在，停止中检调度" : "工序已完工，停止中检调度");
            genRecordService.updateById(record);
            return;
        }
        MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, record.getTaskCode()));
        if (task == null) {
            throw new IllegalStateException("未找到中检记录对应生产工单");
        }
        QmsQualityStandardEntity standard = null;
        try {
            standard = remoteQualityStandardService.getRandomStandard(
                    new QmsQualityStandardEntity().setMatCode(produceTask.getMatCode())
                            .setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name())).getData();
        } catch (Exception exception) {
            log.error("读取中检质量标准失败, produceTaskCode={}", produceTask.getCode(), exception);
        }
        processInspectionTask(record, produceTask, standard, null, currentShiftCode, task.getBatchRuleCode());
    }

    /**
     * 处理质检任务创建
     *
     * @param record           生成记录
     * @param produceTask      生产任务
     * @param standard         质检标准
     * @param currentShiftCode 当前班次
     */
    private void processInspectionTask(MesInspectionGenRecordEntity record, MesProduceTaskEntity produceTask,
                                       QmsQualityStandardEntity standard, MesInspectionGenRecordEntity ignoredExistingRecord,
                                       String currentShiftCode, String ruleCode) {
        LocalDateTime dueTime = record.getNextTime();
        String dueShift = record.getNextShift();
        String scheduleKey = dueTime == null ? dueShift : DateUtil.format(dueTime, "yyyyMMddHHmmss");
        String sourceRequestId = "MES_QMS_V2:" + produceTask.getCode() + ":MIDDLE:"
                + StrUtil.blankToDefault(scheduleKey, String.valueOf(record.getId()));
        MesInspectionGenRecordEntity generated = genRecordService.getOne(
                new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getSourceRequestId, sourceRequestId), false);
        if (generated != null) {
            record.setNextTime(null).setNextShift(null);
            genRecordService.updateById(record);
            return;
        }

        MesInspectionGenRecordEntity newRecord = new MesInspectionGenRecordEntity()
                .setProduceTaskCode(record.getProduceTaskCode()).setTaskCode(record.getTaskCode())
                .setInspectionType(ProcedureLabelTypeEnum.MIDDLE.name()).setSourceRequestId(sourceRequestId)
                .setRuleCode(record.getRuleCode()).setRuleName(record.getRuleName())
                .setGenTime(LocalDateTime.now()).setBlockedCount(0).setFactoryCode(produceTask.getFactoryCode());
        if (dueTime != null) {
            newRecord.setNextTime(getNextTime(record.getRuleCode(), dueTime));
        } else if (StrUtil.isNotBlank(dueShift)) {
            newRecord.setGenShiftCode(dueShift).setNextShift(getNextShift(record.getRuleCode(), dueShift));
        }

        Long taskId = null;
        String failure = null;
        try {
            if (standard == null) {
                throw new IllegalStateException("未匹配到已发布的工序质检标准");
            }
            String batchNo = "";
            BatchGenerator generator = batchGeneratorService.getBatchGenerator(ruleCode);
            if (generator != null) {
                batchNo = generator.generateBatchNumber(produceTask.getTaskCode(), currentShiftCode);
            }
            InspectionProcedureCreateParam createParam = new InspectionProcedureCreateParam()
                    .setNum(Math.max(1, produceTask.getNoticeNum().intValue()))
                    .setTaskCode(produceTask.getCode()).setSourceTaskCode(produceTask.getCode())
                    .setSourceRequestId(sourceRequestId).setMatCode(produceTask.getMatCode())
                    .setMatName(produceTask.getMatName()).setMatSpec(produceTask.getMatSpec())
                    .setProcessCode(produceTask.getRouteCode()).setProcessName(produceTask.getRouteName())
                    .setBatchNo(batchNo).setStandardId(standard.getId()).setDeptCode(produceTask.getWorkshopCode())
                    .setOrderNo(produceTask.getTaskCode()).setLabelType(ProcedureLabelTypeEnum.MIDDLE.name());
            if (InspectionMethodEnum.PENDING.name().equals(standard.getInspectionMethod())) {
                String inspectionNum = remoteParamService.getByKey("QMS_DEFAULT_NUM").getData();
                if (StrUtil.isBlank(inspectionNum)) {
                    throw new IllegalStateException("未配置QMS_DEFAULT_NUM抽检数量");
                }
                createParam.setInspectionNum(Integer.parseInt(inspectionNum));
            }
            taskId = qmsInspectionCoordinator.create(createParam, produceTask.getFactoryCode());
        } catch (Exception exception) {
            failure = rootMessage(exception);
        }

        if (taskId == null) {
            newRecord.setGenFlag("0").setGenRemark("中检任务生成失败：" + failure)
                    .setGateStatus(QualityGateStatusEnum.ERROR.name()).setSyncMessage(failure);
            sendMessage("工序任务" + produceTask.getCode() + "创建中检失败：" + failure
                    + "。请在质量门台账修复条件后重试", produceTask.getCreateBy());
        } else {
            newRecord.setInspectionId(taskId).setGenFlag("1").setGenRemark("生成成功")
                    .setGateStatus(QualityGateStatusEnum.PENDING.name())
                    .setSyncMessage("等待中检结论");
        }
        genRecordService.save(newRecord);
        record.setNextTime(null).setNextShift(null);
        genRecordService.updateById(record);
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    /**
     * 获取下一次执行时间
     *
     * @param ruleCode
     * @param currentDate
     * @return
     */
    private LocalDateTime getNextTime(String ruleCode, LocalDateTime currentDate) {
        LocalDateTime nextTime = null;
        MesInspectionRuleEntity rule = mesInspectionRuleService.getOne(new LambdaQueryWrapper<MesInspectionRuleEntity>()
                .eq(MesInspectionRuleEntity::getCode, ruleCode));
        if (Objects.nonNull(rule)) {
            // 根据当前执行时间和rule.getNum(小时数)来计算下一次执行时间
            nextTime = currentDate.plusHours(rule.getNum().intValue()); // 添加规则中指定的小时数
        }
        return nextTime;
    }

    /**
     * 获取下一次执行班次
     *
     * @param ruleCode
     * @param currentShift
     * @return
     */
    private String getNextShift(String ruleCode, String currentShift) {
        String nextShift = "";
        MesInspectionRuleEntity rule = mesInspectionRuleService.getOne(new LambdaQueryWrapper<MesInspectionRuleEntity>()
                .eq(MesInspectionRuleEntity::getCode, ruleCode));
        if (Objects.nonNull(rule)) {
            // 根据当前班次和rule.getNum(班次数)获取下N个班次
            List<MdmClassesEntity> shifts = remoteClassesService.getClassesByCode(currentShift, rule.getNum().intValue() + 1).getData();
            if (CollUtil.isNotEmpty(shifts)) {
                nextShift = shifts.get(shifts.size() - 1).getShiftCode();
            }
        }
        return nextShift;
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
