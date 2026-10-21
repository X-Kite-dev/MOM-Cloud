package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.inspection.InspectionProductCreateParam;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.enums.HandleResultEnum;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.InspectionProductTaskService;
import com.kitex.mom.qms.service.QmsReworkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class QmsReworkOrderServiceImpl extends ServiceImpl<QmsReworkOrderMapper, QmsReworkOrderEntity>
        implements QmsReworkOrderService {

    private final QmsInspectionProcedureHandleRecordMapper procedureHandleMapper;
    private final QmsInspectionProductHandleRecordMapper productHandleMapper;
    private final QmsInspectionPurchaseHandleRecordMapper purchaseHandleMapper;
    private final QmsInspectionProcedureTaskMapper procedureTaskMapper;
    private final QmsInspectionProductTaskMapper productTaskMapper;
    private final RemoteMesTaskService remoteMesTaskService;
    private final InspectionProcedureTaskService procedureTaskService;
    private final InspectionProductTaskService productTaskService;
    private final com.kitex.mom.qms.service.QmsReworkResultPublisher resultPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsReworkOrderEntity create(String inspectionType, Long handleRecordId) {
        if (handleRecordId == null || StrUtil.isBlank(inspectionType)) {
            throw new IllegalStateException("质检类型和处置记录不能为空");
        }
        String type = inspectionType.trim().toUpperCase();
        if ("PURCHASE".equals(type)) {
            QmsInspectionPurchaseHandleRecordEntity record = purchaseHandleMapper.selectById(handleRecordId);
            if (record == null || !HandleResultEnum.REWORK.name().equals(record.getHandleResult())) {
                throw new IllegalStateException("未找到有效的采购返工处置记录");
            }
            throw new IllegalStateException("采购来料返工必须走供应商退换或配置委外返工路线，不能直接生成普通MES生产工单");
        }
        if (!"PROCEDURE".equals(type) && !"PRODUCT".equals(type)) {
            throw new IllegalStateException("不支持的返工质检类型：" + type);
        }
        QmsReworkOrderEntity existing = this.getOne(new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getInspectionType, type)
                .eq(QmsReworkOrderEntity::getHandleRecordId, handleRecordId), false);
        if (existing != null) {
            return "DISPATCH_FAILED".equals(existing.getStatus()) ? dispatch(existing) : existing;
        }

        Source source = "PROCEDURE".equals(type)
                ? procedureSource(handleRecordId) : productSource(handleRecordId);
        MomUser user = SecurityUtils.getUser();
        QmsReworkOrderEntity order = new QmsReworkOrderEntity()
                .setRequestId("QMS-RW-" + type + "-" + handleRecordId)
                .setInspectionType(type)
                .setInspectionTaskId(source.inspectionTaskId())
                .setHandleRecordId(handleRecordId)
                .setOriginalMesTaskCode(source.originalMesTaskCode())
                .setOriginalMesProduceTaskCode(source.originalMesProduceTaskCode())
                .setReworkProcessCode(source.processCode())
                .setMatCode(source.matCode()).setMatName(source.matName()).setMatSpec(source.matSpec())
                .setBatchNo(source.batchNo()).setQuantity(BigDecimal.valueOf(source.quantity()))
                .setReason(source.reason()).setStatus("DISPATCHING").setDispatchCount(0).setCallbackCount(0)
                .setInitiatedBy(user == null ? "SYSTEM" : user.getUsername())
                .setInitiatedByName(user == null ? "系统" : user.getName())
                .setInitiatedTime(LocalDateTime.now()).setFactoryCode(source.factoryCode());
        this.save(order);
        return dispatch(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsReworkOrderEntity retry(Long id) {
        QmsReworkOrderEntity order = this.getOne(new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getId, id).last("FOR UPDATE"));
        if (order == null) {
            throw new IllegalStateException("返工协调单不存在");
        }
        if (!"DISPATCH_FAILED".equals(order.getStatus()) && !"DISPATCHING".equals(order.getStatus())) {
            return order;
        }
        return dispatch(order);
    }

    private QmsReworkOrderEntity dispatch(QmsReworkOrderEntity order) {
        order.setDispatchCount(Objects.requireNonNullElse(order.getDispatchCount(), 0) + 1)
                .setStatus("DISPATCHING").setLastError(null);
        this.updateById(order);
        try {
            MesTaskReceiveDTO command = new MesTaskReceiveDTO();
            command.setReworkRequestId(order.getRequestId());
            command.setSourceTaskCode(order.getOriginalMesTaskCode());
            command.setSourceProduceTaskCode(order.getOriginalMesProduceTaskCode());
            command.setSourceInspectionType(order.getInspectionType());
            command.setSourceInspectionTaskId(order.getInspectionTaskId());
            command.setSourceHandleRecordId(order.getHandleRecordId());
            command.setReworkProcessCode(order.getReworkProcessCode());
            command.setReworkReason(order.getReason());
            command.setMatCode(order.getMatCode());
            command.setMatName(order.getMatName());
            command.setMatSpec(order.getMatSpec());
            command.setNoticeNum(order.getQuantity());
            command.setResourceType("REWORK");
            command.setResourceId(order.getInspectionTaskId());
            R<String> response = remoteMesTaskService.addRework(command);
            if (response == null || StrUtil.isBlank(response.getData())) {
                throw new IllegalStateException(response == null ? "MES未返回返工工单" : response.getMsg());
            }
            order.setMesTaskCode(response.getData()).setStatus("MES_CREATED").setLastError(null);
            markHandleDispatched(order);
        } catch (Exception exception) {
            order.setStatus("DISPATCH_FAILED")
                    .setLastError(StrUtil.maxLength(rootMessage(exception), 1000));
        }
        this.updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long completeFromMes(QmsReworkCompletionDTO command) {
        if (command == null || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getMesTaskCode())) {
            throw new IllegalStateException("MES返工完工回传缺少请求号或工单号");
        }
        QmsReworkOrderEntity order = this.getOne(new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getRequestId, command.getRequestId().trim()).last("FOR UPDATE"), false);
        if (order == null || !Objects.equals(order.getMesTaskCode(), command.getMesTaskCode())) {
            throw new IllegalStateException("未找到匹配的返工协调单");
        }
        if (order.getReinspectionTaskId() != null) {
            return order.getReinspectionTaskId();
        }
        if (command.getCompletedQuantity() == null
                || command.getCompletedQuantity().compareTo(order.getQuantity()) < 0) {
            throw new IllegalStateException("MES返工完工数量小于质量处置数量，不能发起复检");
        }
        String sourceRequestId = "REWORK-REINSPECTION:" + order.getRequestId();
        Long reinspectionId;
        if ("PROCEDURE".equals(order.getInspectionType())) {
            QmsInspectionProcedureTaskEntity original = procedureTaskMapper.selectById(order.getInspectionTaskId());
            if (original == null) throw new IllegalStateException("原工序质检任务不存在");
            InspectionProcedureCreateParam param = new InspectionProcedureCreateParam()
                    .setSourceRequestId(sourceRequestId).setSourceTaskCode(command.getMesProduceTaskCode())
                    .setTaskCode(command.getMesProduceTaskCode()).setMatCode(order.getMatCode())
                    .setMatName(order.getMatName()).setMatSpec(order.getMatSpec())
                    .setProcessCode(StrUtil.blankToDefault(command.getProcessCode(), order.getReworkProcessCode()))
                    .setProcessName(command.getProcessName()).setStandardId(original.getStandardId())
                    .setBatchNo(order.getBatchNo()).setNum(order.getQuantity().intValueExact())
                    .setInspectionNum(original.getInspectionNum()).setLabelType(original.getLabelType())
                    .setDeptCode(original.getDeptCode()).setOrderNo(order.getMesTaskCode());
            reinspectionId = procedureTaskService.create(param);
        } else {
            QmsInspectionProductTaskEntity original = productTaskMapper.selectById(order.getInspectionTaskId());
            if (original == null) throw new IllegalStateException("原成品质检任务不存在");
            InspectionProductCreateParam param = new InspectionProductCreateParam()
                    .setSourceRequestId(sourceRequestId).setSourceTaskCode(command.getMesProduceTaskCode())
                    .setName("返工复检-" + order.getMesTaskCode()).setMatCode(order.getMatCode())
                    .setMatName(order.getMatName()).setMatSpec(order.getMatSpec())
                    .setProcessCode(StrUtil.blankToDefault(command.getProcessCode(), order.getReworkProcessCode()))
                    .setProcessName(command.getProcessName()).setStandardId(original.getStandardId())
                    .setBatchNo(order.getBatchNo()).setNum(order.getQuantity().intValueExact())
                    .setInspectionNum(original.getInspectionNum()).setDeptCode(original.getDeptCode())
                    .setOrderNo(order.getMesTaskCode());
            productTaskService.create(param);
            QmsInspectionProductTaskEntity created = productTaskMapper.selectOne(
                    new LambdaQueryWrapper<QmsInspectionProductTaskEntity>()
                            .eq(QmsInspectionProductTaskEntity::getSourceRequestId, sourceRequestId));
            if (created == null) throw new IllegalStateException("成品返工复检任务创建后未找到");
            reinspectionId = created.getId();
        }
        order.setMesCompletedQuantity(command.getCompletedQuantity()).setMesCompletedTime(LocalDateTime.now())
                .setReinspectionTaskId(reinspectionId).setReinspectionStatus("WAIT")
                .setReinspectionCreatedTime(LocalDateTime.now()).setStatus("REINSPECTION_PENDING")
                .setCallbackCount(Objects.requireNonNullElse(order.getCallbackCount(), 0) + 1)
                .setLastError(null);
        this.updateById(order);
        return reinspectionId;
    }

    @Override
    public void retryResultSync(Long id) {
        resultPublisher.retry(id);
    }

    private Source procedureSource(Long handleRecordId) {
        QmsInspectionProcedureHandleRecordEntity record = procedureHandleMapper.selectById(handleRecordId);
        if (record == null || !HandleResultEnum.REWORK.name().equals(record.getHandleResult())) {
            throw new IllegalStateException("未找到有效的工序返工处置记录");
        }
        QmsInspectionProcedureTaskEntity task = procedureTaskMapper.selectById(record.getTaskId());
        if (task == null || StrUtil.isBlank(task.getOrderNo())) {
            throw new IllegalStateException("工序质检未关联原生产工单，不能生成返工工单");
        }
        return new Source(task.getId(), task.getOrderNo(), task.getSourceTaskCode(), task.getProcessCode(),
                task.getMatCode(), task.getMatName(), task.getMatSpec(), task.getBatchNo(),
                record.getHandleNum(), record.getHandleRemark(), task.getFactoryCode());
    }

    private Source productSource(Long handleRecordId) {
        QmsInspectionProductHandleRecordEntity record = productHandleMapper.selectById(handleRecordId);
        if (record == null || !HandleResultEnum.REWORK.name().equals(record.getHandleResult())) {
            throw new IllegalStateException("未找到有效的成品返工处置记录");
        }
        QmsInspectionProductTaskEntity task = productTaskMapper.selectById(record.getTaskId());
        if (task == null || StrUtil.isBlank(task.getOrderNo())) {
            throw new IllegalStateException("成品质检未关联原生产工单，不能生成返工工单");
        }
        return new Source(task.getId(), task.getOrderNo(), task.getSourceTaskCode(), task.getProcessCode(),
                task.getMatCode(), task.getMatName(), task.getMatSpec(), task.getBatchNo(),
                record.getHandleNum(), record.getHandleRemark(), task.getFactoryCode());
    }

    private void markHandleDispatched(QmsReworkOrderEntity order) {
        if ("PROCEDURE".equals(order.getInspectionType())) {
            QmsInspectionProcedureHandleRecordEntity record = procedureHandleMapper.selectById(order.getHandleRecordId());
            record.setStatus(Boolean.TRUE);
            procedureHandleMapper.updateById(record);
        } else {
            QmsInspectionProductHandleRecordEntity record = productHandleMapper.selectById(order.getHandleRecordId());
            record.setStatus(Boolean.TRUE);
            productHandleMapper.updateById(record);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private record Source(Long inspectionTaskId, String originalMesTaskCode, String originalMesProduceTaskCode,
                          String processCode, String matCode, String matName, String matSpec, String batchNo,
                          Integer quantity, String reason, String factoryCode) {
        private Source {
            if (quantity == null || quantity <= 0) throw new IllegalStateException("返工处置数量必须大于0");
            reason = StrUtil.blankToDefault(StrUtil.trim(reason), "质量不合格返工");
        }
    }
}
