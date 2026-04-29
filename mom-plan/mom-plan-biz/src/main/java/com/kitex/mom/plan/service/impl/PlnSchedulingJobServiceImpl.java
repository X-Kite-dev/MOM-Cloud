package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnSchedulingJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlnSchedulingJobServiceImpl implements PlnSchedulingJobService {

    private static final int MAX_RETRY_COUNT = 3;
    private static final String RETRY_PREFIX = "RETRY_COUNT=";

    private final PlnSchedulingEngineService plnSchedulingEngineService;
    private final PlnSchedulingTaskMapper plnSchedulingTaskMapper;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean retryFailedTasks() {
        List<PlnSchedulingTaskEntity> failedTasks = plnSchedulingTaskMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getTaskStatus, "FAILED"));

        boolean allSuccess = true;
        for (PlnSchedulingTaskEntity failedTask : failedTasks) {
            Boolean success = retryFailedTask(failedTask);
            if (!Boolean.TRUE.equals(success)) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean retryFailedTask(PlnSchedulingTaskEntity task) {
        if (task == null || task.getId() == null) {
            return Boolean.FALSE;
        }
        int currentRetry = parseRetryCount(task.getRemark());
        if (currentRetry >= MAX_RETRY_COUNT) {
            task.setTaskStatus("FAILED");
            task.setRemark(appendRetryRemark(task.getRemark(), currentRetry, "超过重试上限"));
            plnSchedulingTaskMapper.updateById(task);
            return Boolean.FALSE;
        }

        int nextRetry = currentRetry + 1;
        task.setTaskStatus("RUNNING");
        task.setRemark(appendRetryRemark(task.getRemark(), nextRetry, "重试中"));
        plnSchedulingTaskMapper.updateById(task);

        try {
            PlnSchedulingRunDTO runDTO = new PlnSchedulingRunDTO();
            runDTO.setMode("simulate");
            runDTO.setTriggerType("AUTO");
            runDTO.setCycleType(task.getCycleType());
            runDTO.setScheduleDate(task.getScheduleDate());
            boolean success = Boolean.TRUE.equals(plnSchedulingEngineService.run(runDTO));
            if (success) {
                task.setTaskStatus("SUCCESS");
                task.setRemark(appendRetryRemark(task.getRemark(), nextRetry, "重试成功"));
            } else {
                task.setTaskStatus("FAILED");
                task.setRemark(appendRetryRemark(task.getRemark(), nextRetry, "重试失败"));
            }
            plnSchedulingTaskMapper.updateById(task);
            return success;
        } catch (Exception ex) {
            log.error("排产补偿重试失败, taskNo={}", task.getTaskNo(), ex);
            task.setTaskStatus("FAILED");
            task.setRemark(appendRetryRemark(task.getRemark(), nextRetry, "重试异常"));
            plnSchedulingTaskMapper.updateById(task);
            return Boolean.FALSE;
        }
    }

    private int parseRetryCount(String remark) {
        if (remark == null || remark.isBlank()) {
            return 0;
        }
        int index = remark.lastIndexOf(RETRY_PREFIX);
        if (index < 0) {
            return 0;
        }
        String value = remark.substring(index + RETRY_PREFIX.length()).trim();
        int sep = value.indexOf('，');
        if (sep >= 0) {
            value = value.substring(0, sep).trim();
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String appendRetryRemark(String original, int retryCount, String message) {
        String base = (original == null || original.isBlank()) ? "" : original;
        String suffix = RETRY_PREFIX + retryCount + "，" + message;
        return base.isBlank() ? suffix : base + "；" + suffix;
    }
}
