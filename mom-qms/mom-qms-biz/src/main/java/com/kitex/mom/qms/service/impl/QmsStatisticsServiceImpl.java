package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.enums.InspectionTaskStatusEnum;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.QmsStatisticsService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 质量统计分析
 *
 * @author kitex
 * @since 2025-04-02
 */
@Service
@AllArgsConstructor
public class QmsStatisticsServiceImpl implements QmsStatisticsService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int DIVISION_SCALE = 4;
    private static final int RESULT_SCALE = 2;
    private final InspectionProcedureTaskService procedureTaskService;

    /**
     * 获取生产线数据
     *
     * @return
     */
    @Override
    public Map<String, Object> getLineNum() {
        Map<String, Object> resultMap = new HashMap<>();
        List<QmsInspectionProcedureTaskEntity> procedureTaskList = procedureTaskService.list();

        long procedureWaitCount = procedureTaskList.stream().filter(item -> InspectionTaskStatusEnum.WAIT.name().equals(item.getStatus())).count();

        BigDecimal waitNum = BigDecimal.valueOf(procedureWaitCount);
        resultMap.put("waitNum", waitNum);
        resultMap.put("waitTime", 15);

        long procedureNormalCount = procedureTaskList.size() - procedureWaitCount;
        resultMap.put("normalCount", procedureNormalCount);

        List<QmsInspectionProcedureTaskEntity> finishProcedureList = procedureTaskList.stream().filter(item -> InspectionTaskStatusEnum.FINISHED.name().equals(item.getStatus())).toList();
        Integer allProcedureNum = finishProcedureList.stream().map(QmsInspectionProcedureTaskEntity::getNum).reduce(0, Integer::sum);
        Integer allProcedurePassNum = finishProcedureList.stream().map(QmsInspectionProcedureTaskEntity::getUsableNum).reduce(0, Integer::sum);

        BigDecimal utilization = new BigDecimal(allProcedurePassNum)
                .divide(new BigDecimal(allProcedureNum), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
        resultMap.put("normalCapacity", utilization);

        long procedureUnPassNum = procedureTaskList.stream().filter(item -> InspectionTaskStatusEnum.FINISHED.name().equals(item.getStatus())
                && InspectionTaskResultEnum.UN_QUALIFIED.name().equals(item.getResult())).count();
        resultMap.put("warningNum", procedureUnPassNum);

        return resultMap;
    }

    /**
     * 获取卡片区域数据
     *
     * @return 卡片区域统计数据
     */
    @Override
    public Map<String, Object> getCardNum() {
        // 获取当天的时间范围
        LocalDateTime today = LocalDateTime.now();
        LocalDateTime startOfDay = today.with(LocalTime.MIN);
        LocalDateTime endOfDay = today.with(LocalTime.MAX);

        var procedureTasks = procedureTaskService.list(new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                        .between(QmsInspectionProcedureTaskEntity::getCreateTime, startOfDay, endOfDay))
                .stream()
                .filter(task -> InspectionTaskStatusEnum.FINISHED.name().equals(task.getStatus()))
                .toList();

        // 计算总检验数量和合格品数量
        int totalCount = Stream.of(
                procedureTasks.stream().mapToInt(QmsInspectionProcedureTaskEntity::getNum)
        ).flatMapToInt(Function.identity()).sum();

        int qualifiedCount = Stream.of(
                procedureTasks.stream().filter(task -> InspectionTaskResultEnum.QUALIFIED.name().equals(task.getResult())).mapToInt(QmsInspectionProcedureTaskEntity::getNum)
        ).flatMapToInt(Function.identity()).sum();

        // 计算并返回合格率
        return Map.of("qualificationRate", totalCount > 0
                ? new BigDecimal(qualifiedCount)
                .divide(new BigDecimal(totalCount), DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(RESULT_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
    }

}
