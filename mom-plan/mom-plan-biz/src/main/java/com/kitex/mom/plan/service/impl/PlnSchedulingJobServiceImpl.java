package com.kitex.mom.plan.service.impl;

import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnSchedulingJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlnSchedulingJobServiceImpl implements PlnSchedulingJobService {

    private final PlnSchedulingEngineService plnSchedulingEngineService;

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean onMrpCompleted(String cycleType, LocalDate scheduleDate) {
        PlnSchedulingRunDTO runDTO = new PlnSchedulingRunDTO();
        runDTO.setMode("simulate");
        runDTO.setTriggerType("AUTO");
        runDTO.setCycleType(cycleType);
        runDTO.setScheduleDate(scheduleDate);
        return plnSchedulingEngineService.run(runDTO);
    }

    /**
     * 旧补偿丢失targetIds/规则版本且会改写原失败状态；没有冻结请求证据时禁止自动扩围重排。
     */
    @Override
    public Boolean retryFailedTasks() {
        log.warn("旧排产失败补偿已停用：缺少精确来源与运行快照；请从工作台核对来源后新建排产版本，原失败结果保留");
        return Boolean.FALSE;
    }

    @Override
    public Boolean retryFailedTask(PlnSchedulingTaskEntity task) {
        throw new com.kitex.common.core.exception.CheckedException("旧排产任务缺少可验证的原始运行命令，禁止按日期扩大重试范围；请核实来源后创建新版本");
    }
}
