package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.dto.GanttListDTO;
import com.kitex.mom.mes.dto.GanttResultDTO;
import com.kitex.mom.mes.dto.RouteProcessDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.service.MesProduceTaskService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产工序任务单
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesProduceTaskServiceImpl extends ServiceImpl<MesProduceTaskMapper, MesProduceTaskEntity> implements MesProduceTaskService {

    /**
     * 查询列表
     *
     * @param query
     * @return
     */
    @Override
    public List<MesProduceTaskEntity> queryList(MesProduceTaskEntity query) {
        return this.list(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTaskCode, query.getTaskCode()));
    }

    /**
     * 查询工序任务列表
     *
     * @param taskCode
     * @return
     */
    @Override
    public List<RouteProcessDTO> queryProcessList(String taskCode) {
        List<MesProduceTaskEntity> processTasks = this.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTaskCode, taskCode)
                .orderByAsc(MesProduceTaskEntity::getRouteOrder));

        List<RouteProcessDTO> processes = new ArrayList<>();
        if (CollUtil.isNotEmpty(processTasks)) {
            Map<String, MesProduceTaskEntity> byProcess = processTasks.stream().collect(Collectors.toMap(
                    MesProduceTaskEntity::getRouteCode, item -> item, (a, b) -> a, LinkedHashMap::new));
            processTasks.forEach(task -> {
                RouteProcessDTO process = new RouteProcessDTO();
                process.setProduceTaskCode(task.getCode());
                process.setTaskCode(task.getTaskCode());
                process.setMatCode(task.getMatCode());
                process.setMatName(task.getMatName());
                process.setMatSpec(task.getMatSpec());
                process.setWorksiteCodes(task.getWorksiteCodes());
                process.setWorksiteNames(task.getWorksiteNames());
                process.setProcessCode(task.getRouteCode());
                process.setProcessName(task.getRouteName());
                process.setStatus(task.getStatus());
                process.setPredecessorCodes(task.getPredecessorCodes());
                process.setSuccessorCodes(task.getSuccessorCodes());
                process.setOverlapEnabled(task.getOverlapEnabled());
                process.setTransferBatchQuantity(task.getTransferBatchQuantity());
                List<String> blocking = splitCodes(task.getPredecessorCodes()).stream().filter(code -> {
                    MesProduceTaskEntity predecessor = byProcess.get(code);
                    return predecessor == null || predecessor.getTransferReleasedTime() == null
                            || predecessor.getTransferReleasedTime().isAfter(java.time.LocalDateTime.now());
                }).toList();
                process.setReadyToStart(blocking.isEmpty());
                process.setBlockingPredecessorCodes(String.join(",", blocking));
                process.setFinishNum(task.getCompleteNum() != null ? task.getCompleteNum() : BigDecimal.ZERO);
                if (task.getNoticeNum() != null && task.getNoticeNum().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal percent = task.getCompleteNum() != null ?
                            task.getCompleteNum().multiply(new BigDecimal("100")).divide(task.getNoticeNum(), 2, RoundingMode.HALF_UP) :
                            BigDecimal.ZERO;
                    process.setPercent(percent);
                } else {
                    process.setPercent(BigDecimal.ZERO);
                }
                processes.add(process);
            });
        }
        return processes;
    }

    private List<String> splitCodes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(code -> !code.isBlank()).distinct().toList();
    }


    /**
     * 查询甘特图
     *
     * @param query
     * @return
     */
    @Override
    public GanttResultDTO getGanttList(MesProduceTaskEntity query) {
        List<MesProduceTaskEntity> tasks = this.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(query != null && query.getTaskCode() != null && !query.getTaskCode().isBlank(), MesProduceTaskEntity::getTaskCode, query.getTaskCode())
                .eq(query != null && query.getMatCode() != null && !query.getMatCode().isBlank(), MesProduceTaskEntity::getMatCode, query.getMatCode())
                .orderByAsc(MesProduceTaskEntity::getNoticeDate)
                .orderByAsc(MesProduceTaskEntity::getRouteOrder));

        GanttResultDTO resultDTO = new GanttResultDTO();
        if (CollUtil.isEmpty(tasks)) {
            resultDTO.setTaskList(new ArrayList<>());
            return resultDTO;
        }

        List<GanttListDTO> ganttGroups = tasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(MesProduceTaskEntity::getTaskCode, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .entrySet().stream()
                .map(entry -> {
                    GanttListDTO dto = new GanttListDTO();
                    dto.setId(entry.getValue().get(0).getId());
                    dto.setTitle(entry.getKey());
                    dto.setChildren(entry.getValue());
                    return dto;
                }).toList();

        java.time.LocalDateTime minStart = tasks.stream()
                .map(task -> task.getStartTime() != null ? task.getStartTime() : task.getNoticeDate())
                .filter(java.util.Objects::nonNull)
                .min(java.time.LocalDateTime::compareTo)
                .orElse(null);

        java.time.LocalDateTime maxEnd = tasks.stream()
                .map(task -> task.getFinishTime() != null ? task.getFinishTime() : task.getNoticeDate())
                .filter(java.util.Objects::nonNull)
                .max(java.time.LocalDateTime::compareTo)
                .orElse(null);

        if (minStart != null) {
            resultDTO.setGanttStart(java.sql.Timestamp.valueOf(minStart));
        }
        if (maxEnd != null) {
            resultDTO.setGanttEnd(java.sql.Timestamp.valueOf(maxEnd));
        }
        resultDTO.setTaskList(ganttGroups);
        return resultDTO;
    }

}
