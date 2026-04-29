package com.kitex.mom.mes.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.util.R;
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
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.api.feign.RemoteQualityStandardService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.entity.QmsQualityStandardEntity;
import com.kitex.mom.qms.enums.InspectionTaskTypeEnum;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
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
    private final RemoteProcedureService remoteProcedureService;
    private final RemoteClassesService remoteClassesService;
    private final RemoteQualityStandardService remoteQualityStandardService;
    private final MesProduceTaskService produceTaskService;
    private final RemoteUserService remoteUserService;
    private final RemoteMessagePushService messageService;
    private final MesInspectionRuleService mesInspectionRuleService;
    private final MesTaskService mesTaskService;
    private final BatchGeneratorService batchGeneratorService;

    @XxlJob("qmsPatrolCreate")
    public void createTask() {
        try {
            log.info("定时生成工序质检任务，开始时间：{}", DateUtil.format(new Date(), "yyyy-MM-dd HH:mm"));
            Date currentDate = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(currentDate);
            calendar.add(Calendar.MINUTE, -10);
            Date lastDate = calendar.getTime();

            // 获取当前班次
            String currentShiftCode = remoteClassesService.getRuleBanci(LocalDateTime.now()).getData();

            // 查询下次执行时间在此次定时任务执行期间的任务
            List<MesInspectionGenRecordEntity> recordList = genRecordService.list(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                    .between(MesInspectionGenRecordEntity::getNextTime, lastDate, currentDate));

            // 查询下次班组在此次定时任务执行期间的任务
            List<MesInspectionGenRecordEntity> shiftRecordList = genRecordService.list(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                    .eq(MesInspectionGenRecordEntity::getNextTime, currentShiftCode));

            // 循环创建
            recordList.forEach(record -> {
                // 获取生产工序任务
                MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getCode, record.getProduceTaskCode()));
                MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getCode, record.getTaskCode()));
                String taskRule = task.getBatchRuleCode();
                // 根据物料匹配质检标准
                QmsQualityStandardEntity standard = remoteQualityStandardService.getRandomStandard(new QmsQualityStandardEntity()
                        .setMatCode(produceTask.getMatCode()).setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name())).getData();

                // 处理质检任务创建
                processInspectionTask(record, produceTask, standard, null, currentShiftCode, taskRule);
            });

            shiftRecordList.forEach(shiftRecord -> {
                // 如果存在，则说明已经创建，跳过创建
                MesInspectionGenRecordEntity existRecord = genRecordService.getOne(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                        .eq(MesInspectionGenRecordEntity::getProduceTaskCode, shiftRecord.getProduceTaskCode())
                        .eq(MesInspectionGenRecordEntity::getGenShiftCode, currentShiftCode));

                // 获取生产工序任务
                MesProduceTaskEntity produceTask = produceTaskService.getOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getCode, shiftRecord.getProduceTaskCode()));
                MesTaskEntity task = mesTaskService.getOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getCode, shiftRecord.getTaskCode()));
                String taskRule = task.getBatchRuleCode();
                // 根据物料匹配质检标准
                QmsQualityStandardEntity standard = remoteQualityStandardService.getRandomStandard(new QmsQualityStandardEntity()
                        .setMatCode(produceTask.getMatCode()).setInspectionType(InspectionTaskTypeEnum.PROCEDURE.name())).getData();

                // 处理质检任务创建
                processInspectionTask(shiftRecord, produceTask, standard, existRecord, currentShiftCode, taskRule);
            });

            log.info("定时生成工序质检任务。");
        } catch (Exception e) {
        }
    }

    /**
     * 处理质检任务创建
     *
     * @param record           生成记录
     * @param produceTask      生产任务
     * @param standard         质检标准
     * @param existRecord      已存在的记录（仅用于班次记录）
     * @param currentShiftCode 当前班次
     */
    private void processInspectionTask(MesInspectionGenRecordEntity record, MesProduceTaskEntity produceTask,
                                       QmsQualityStandardEntity standard, MesInspectionGenRecordEntity existRecord,
                                       String currentShiftCode, String ruleCode) {
        // 处理班次记录已存在的情况
        if (Objects.nonNull(existRecord)) {
            record.setGenFlag("0");
            record.setGenRemark("当前班次已存在质检任务");
            genRecordService.updateById(record);
            return;
        }

        // 处理质检标准为空的情况
        if (Objects.isNull(standard)) {
            record.setGenFlag("0");
            record.setGenRemark("未匹配到质检标准");
            genRecordService.updateById(record);

            String toUsers = produceTask.getCreateBy();
            String message = "工序任务" + produceTask.getCode() + "未能匹配到质检标准，创建工序质检任务失败。下次任务将不会自动创建";
            sendMessage(message, toUsers);
            return;
        }

        String batchNo = "";
        BatchGenerator generator = batchGeneratorService.getBatchGenerator(ruleCode);
        if (Objects.nonNull(generator)) {
            batchNo = generator.generateBatchNumber(produceTask.getTaskCode(), currentShiftCode);
        }


        // 创建工序质检任务
        R<Long> LR = remoteProcedureService.create(new InspectionProcedureCreateParam()
                .setNum(produceTask.getNoticeNum().intValue())
                .setTaskCode(produceTask.getCode())
                .setMatCode(produceTask.getMatCode())
                .setMatName(produceTask.getMatName())
                .setProcessCode(produceTask.getRouteCode())
                .setProcessName(produceTask.getRouteName())
                .setBatchNo(batchNo)
                .setStandardId(standard.getId())
                .setLabelType(ProcedureLabelTypeEnum.MIDDLE.getName()));

        Long taskId = LR.getData();

        // 处理创建结果
        if (Objects.isNull(taskId)) {
            String msg = LR.getMsg();
            if (msg != null && msg.contains("\"msg\":\"")) {
                int start = msg.indexOf("\"msg\":\"");
                int end = msg.indexOf("\"", start + 7);
                msg = msg.substring(start + 7, end);
            }
            record.setGenFlag("0");
            record.setGenRemark(msg);
            String toUsers = produceTask.getCreateBy();
            String message = "工序任务" + produceTask.getCode() + "创建失败。失败原因：" + msg + "。下次任务将不会自动创建";
            sendMessage(message, toUsers);
        } else {
            record.setGenFlag("1");
            record.setGenRemark("生成成功");

            // 保存新生成记录
            MesInspectionGenRecordEntity newRecord = new MesInspectionGenRecordEntity()
                    .setProduceTaskCode(record.getProduceTaskCode())
                    .setTaskCode(record.getTaskCode())
                    .setInspectionType(record.getInspectionType())
                    .setInspectionId(taskId)
                    .setRuleCode(record.getRuleCode())
                    .setRuleName(record.getRuleName())
                    .setFactoryCode(produceTask.getFactoryCode());

            // 根据记录类型设置不同的时间字段
            if (record.getNextTime() != null) {
                newRecord.setGenTime(record.getNextTime())
                        .setNextTime(getNextTime(record.getRuleCode(), record.getNextTime()));
            } else {
                newRecord.setGenShiftCode(record.getNextShift())
                        .setNextShift(getNextShift(record.getRuleCode(), record.getNextShift()));
            }

            genRecordService.save(newRecord);
        }

        genRecordService.updateById(record);
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
