package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.mapper.QmsInspectionProcedureTaskMapper;
import com.kitex.mom.qms.mapper.QmsInspectionProductTaskMapper;
import com.kitex.mom.qms.mapper.QmsReworkOrderMapper;
import com.kitex.mom.qms.service.QmsReworkResultPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class QmsReworkResultPublisherImpl implements QmsReworkResultPublisher {
    private final QmsReworkOrderMapper reworkOrderMapper;
    private final QmsInspectionProcedureTaskMapper procedureTaskMapper;
    private final QmsInspectionProductTaskMapper productTaskMapper;
    private final RemoteMesTaskService remoteMesTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(String inspectionType, Long qmsTaskId) {
        if (qmsTaskId == null || StrUtil.isBlank(inspectionType)) return;
        QmsReworkOrderEntity order = reworkOrderMapper.selectOne(new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getInspectionType, inspectionType.trim().toUpperCase())
                .eq(QmsReworkOrderEntity::getReinspectionTaskId, qmsTaskId).last("FOR UPDATE"));
        if (order == null) return;
        publish(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long reworkOrderId) {
        QmsReworkOrderEntity order = reworkOrderMapper.selectOne(new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getId, reworkOrderId).last("FOR UPDATE"));
        if (order == null || order.getReinspectionTaskId() == null) {
            throw new IllegalStateException("返工协调单不存在或尚未生成复检任务");
        }
        publish(order);
    }

    private void publish(QmsReworkOrderEntity order) {
        Snapshot task = snapshot(order);
        if (StrUtil.isBlank(task.result())) return;
        int total = Objects.requireNonNullElse(task.total(), 0);
        int usable = Objects.requireNonNullElse(task.usable(), 0);
        int handled = Objects.requireNonNullElse(task.handled(), 0);
        boolean qualified = InspectionTaskResultEnum.QUALIFIED.name().equals(task.result());
        if (qualified) {
            usable = total;
            handled = total;
        }
        MesReworkInspectionResultDTO result = new MesReworkInspectionResultDTO()
                .setRequestId(order.getRequestId()).setQmsTaskId(order.getReinspectionTaskId())
                .setInspectionType(order.getInspectionType()).setResult(task.result()).setTotalNum(total)
                .setUsableNum(usable).setHandleNum(handled).setHandleResult(task.handleResult());
        order.setResultSyncCount(Objects.requireNonNullElse(order.getResultSyncCount(), 0) + 1);
        try {
            R<Void> response = remoteMesTaskService.syncReworkInspection(result);
            if (response == null || response.getCode() != 0) {
                throw new IllegalStateException(response == null ? "MES未响应复检同步" : response.getMsg());
            }
            boolean finished = qualified || handled >= total;
            order.setReinspectionStatus(finished ? (usable == 0 ? "REJECTED" : "COMPLETED") : "PARTIAL")
                    .setStatus(finished ? "CLOSED" : "REINSPECTION_PENDING")
                    .setResultSyncTime(LocalDateTime.now()).setLastError(null);
        } catch (Exception exception) {
            order.setReinspectionStatus("SYNC_FAILED").setStatus("RESULT_SYNC_FAILED")
                    .setLastError(StrUtil.maxLength(rootMessage(exception), 1000));
        }
        reworkOrderMapper.updateById(order);
    }

    private Snapshot snapshot(QmsReworkOrderEntity order) {
        if ("PROCEDURE".equals(order.getInspectionType())) {
            QmsInspectionProcedureTaskEntity task = procedureTaskMapper.selectById(order.getReinspectionTaskId());
            if (task == null) throw new IllegalStateException("返工工序复检任务不存在");
            return new Snapshot(task.getResult(), task.getNum(), task.getUsableNum(), task.getHandleNum(), task.getHandleResult());
        }
        if ("PRODUCT".equals(order.getInspectionType())) {
            QmsInspectionProductTaskEntity task = productTaskMapper.selectById(order.getReinspectionTaskId());
            if (task == null) throw new IllegalStateException("返工成品复检任务不存在");
            return new Snapshot(task.getResult(), task.getNum(), task.getUsableNum(), task.getHandleNum(), task.getHandleResult());
        }
        throw new IllegalStateException("不支持的返工复检类型");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private record Snapshot(String result, Integer total, Integer usable, Integer handled, String handleResult) {
    }
}
