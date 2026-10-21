package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesFinishedGoodsReceiptReverseInboxService;
import com.kitex.mom.mes.service.MesInboundAllocationService;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.oms.enums.OrderLongStateEnum;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 以WMS永久事件为边界反向恢复MES成品入库数量、报工来源可用量、APS来源分摊和PLAN完成量。
 *
 * <p>PLAN接口自身以requestId永久防重；若远端已成功而本地事务回滚，WMS重投同一事件时
 * 会用相同回调号继续完成，不会重复冲减。</p>
 */
@Service
@RequiredArgsConstructor
public class MesFinishedGoodsReceiptReverseInboxServiceImpl
        extends ServiceImpl<MesFinishedGoodsReceiptReverseInboxMapper, MesFinishedGoodsReceiptReverseInboxEntity>
        implements MesFinishedGoodsReceiptReverseInboxService {

    private final MesTaskInWmsReceiptMapper receiptMapper;
    private final MesTaskInWmsReceiptDetailMapper receiptDetailMapper;
    private final MesTaskInWmsReceiptReverseDetailMapper reverseDetailMapper;
    private final MesTaskMapper taskMapper;
    private final MesApsBatchAllocationMapper allocationMapper;
    private final MesInboundAllocationService allocationLedger;
    private final RemotePlnIssueService planService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesFinishedGoodsReceiptReverseEvent.Receipt receive(MesFinishedGoodsReceiptReverseEvent event) {
        require(event != null && Objects.equals(event.getFingerprint(), event.calculateFingerprint()),
                "WMS成品实物红冲事件指纹不一致");
        MesFinishedGoodsReceiptReverseInboxEntity existing = getOne(
                new LambdaQueryWrapper<MesFinishedGoodsReceiptReverseInboxEntity>()
                        .eq(MesFinishedGoodsReceiptReverseInboxEntity::getEventId, event.getEventId()), false);
        if (existing != null) {
            require(Objects.equals(existing.getFingerprint(), event.getFingerprint()),
                    "同一成品实物红冲事件不能覆盖不同事实");
            return receipt(event.getEventId());
        }

        receiptMapper.lockRequest(event.getSourceMesInboundRequestId());
        MesTaskInWmsReceiptEntity source = receiptMapper.selectOne(
                new LambdaQueryWrapper<MesTaskInWmsReceiptEntity>()
                        .eq(MesTaskInWmsReceiptEntity::getRequestId, event.getSourceMesInboundRequestId())
                        .eq(MesTaskInWmsReceiptEntity::getFactoryCode, event.getFactoryCode())
                        .last("FOR UPDATE"), false);
        require(source != null, "WMS红冲没有对应的MES成品入库永久回执");
        require(Objects.equals(source.getRequestFingerprint(), event.getSourceRequestFingerprint()),
                "WMS通知冻结的MES入库命令指纹与原回执不一致");
        BigDecimal receiptQuantity = zero(source.getInStockQuantity());
        BigDecimal alreadyReversed = zero(source.getReversedQuantity());
        BigDecimal remainingReceipt = receiptQuantity.subtract(alreadyReversed);
        require(receiptQuantity.signum() > 0 && alreadyReversed.signum() >= 0
                        && remainingReceipt.signum() >= 0,
                "MES成品入库回执累计红冲数量异常");
        require(event.getReverseQuantity().compareTo(remainingReceipt) <= 0,
                "本次红冲数量超过MES原入库回执剩余可红冲数量");

        MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, source.getMesTaskId())
                .eq(MesTaskEntity::getFactoryCode, event.getFactoryCode()).last("FOR UPDATE"), false);
        require(task != null && Objects.equals(task.getCode(), source.getMesTaskCode()),
                "MES成品入库回执对应生产工单不存在或归属漂移");
        List<MesTaskInWmsReceiptDetailEntity> active = allocationLedger.activeByTask(
                task.getCode(), task.getFactoryCode(), true);
        List<MesTaskInWmsReceiptDetailEntity> sourceDetails = active.stream()
                .filter(item -> Objects.equals(item.getReceiptId(), source.getId())).toList();
        BigDecimal allocated = sourceDetails.stream()
                .map(MesTaskInWmsReceiptDetailEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(!sourceDetails.isEmpty() && allocated.compareTo(remainingReceipt) == 0
                        && allocated.compareTo(event.getReverseQuantity()) >= 0,
                "原MES成品入库剩余报工来源与回执净额不守恒，禁止形成伪红冲链路");
        List<ReverseAllocation> reverseAllocations = allocateReverseSource(
                sourceDetails, event.getReverseQuantity());

        BigDecimal inStock = zero(task.getInStockNum());
        require(inStock.compareTo(event.getReverseQuantity()) >= 0, "生产工单当前已入库量不足以红冲");
        reversePlan(task, source, event);
        task.setInStockNum(inStock.subtract(event.getReverseQuantity()));
        task.setUnInStockNum(zero(task.getUnInStockNum()).add(event.getReverseQuantity()));
        require(taskMapper.updateById(task) == 1, "MES生产工单成品入库数量回冲失败");

        LocalDateTime accepted = LocalDateTime.now();
        MesFinishedGoodsReceiptReverseEvent.Receipt response = new MesFinishedGoodsReceiptReverseEvent.Receipt()
                .setEventId(event.getEventId()).setFingerprint(event.getFingerprint())
                .setCorrectionId(event.getCorrectionId()).setWmsNoticeId(event.getWmsNoticeId())
                .setSourceMesInboundRequestId(event.getSourceMesInboundRequestId())
                .setMesReceiptId(source.getId()).setMesTaskId(task.getId()).setMesTaskCode(task.getCode())
                .setReversedQuantity(event.getReverseQuantity()).setFactoryCode(event.getFactoryCode())
                .setAcceptedTime(accepted);
        MesFinishedGoodsReceiptReverseInboxEntity inbox = new MesFinishedGoodsReceiptReverseInboxEntity()
                .setEventId(event.getEventId()).setFingerprint(event.getFingerprint())
                .setCorrectionId(event.getCorrectionId()).setWmsNoticeId(event.getWmsNoticeId())
                .setSourceMesInboundRequestId(event.getSourceMesInboundRequestId())
                .setMesReceiptId(source.getId()).setMesTaskId(task.getId())
                .setReversedQuantity(event.getReverseQuantity()).setPayloadJson(JSONUtil.toJsonStr(event))
                .setReceiptJson(JSONUtil.toJsonStr(response)).setAcceptedTime(accepted)
                .setFactoryCode(event.getFactoryCode());
        require(save(inbox), "MES成品实物红冲永久回执保存失败");
        for (ReverseAllocation allocation : reverseAllocations) {
            MesTaskInWmsReceiptDetailEntity detail = allocation.detail();
            require(reverseDetailMapper.insert(new MesTaskInWmsReceiptReverseDetailEntity()
                    .setInboxId(inbox.getId()).setReceiptId(source.getId()).setReceiptDetailId(detail.getId())
                    .setReportId(detail.getReportId()).setReversedQuantity(allocation.quantity())
                    .setFactoryCode(event.getFactoryCode())) == 1, "MES成品入库报工来源反向明细保存失败");
        }
        BigDecimal cumulativeReversed = alreadyReversed.add(event.getReverseQuantity());
        source.setReversalStatus(cumulativeReversed.compareTo(receiptQuantity) == 0
                        ? "REVERSED" : "PARTIALLY_REVERSED")
                .setReverseEventId(event.getEventId())
                .setReversedQuantity(cumulativeReversed).setReversedTime(accepted);
        require(receiptMapper.updateById(source) == 1, "MES原成品入库回执红冲状态保存失败");
        return response;
    }

    /**
     * 按原报工来源稳定FIFO释放剩余净额，不根据物料或时间猜测新的来源。
     */
    private List<ReverseAllocation> allocateReverseSource(
            List<MesTaskInWmsReceiptDetailEntity> sourceDetails, BigDecimal quantity) {
        BigDecimal remaining = quantity;
        List<ReverseAllocation> result = new ArrayList<>();
        for (MesTaskInWmsReceiptDetailEntity detail : sourceDetails) {
            if (remaining.signum() == 0) break;
            BigDecimal available = zero(detail.getAllocatedQuantity());
            BigDecimal reversed = available.min(remaining);
            if (reversed.signum() > 0) {
                result.add(new ReverseAllocation(detail, reversed));
                remaining = remaining.subtract(reversed);
            }
        }
        require(remaining.signum() == 0, "MES原报工来源剩余数量不足以承接本次红冲");
        return result;
    }

    private void reversePlan(MesTaskEntity task, MesTaskInWmsReceiptEntity source,
                             MesFinishedGoodsReceiptReverseEvent event) {
        if (StrUtil.isBlank(task.getPlanNo())) return;
        List<MesApsBatchAllocationEntity> allocations = StrUtil.isBlank(task.getApsBatchNo()) ? List.of()
                : allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getMesTaskId, task.getId())
                .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq).last("FOR UPDATE"));
        List<OmsOrderLifeCycleDTO> callbacks = new ArrayList<>();
        if (allocations.isEmpty()) {
            callbacks.add(callback(task, task.getPlanNo(), null, event,
                    event.getReverseQuantity().negate()));
        } else {
            BigDecimal oldTotal = allocations.stream().map(item -> zero(item.getInStockQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(oldTotal.compareTo(event.getReverseQuantity()) >= 0,
                    "APS批次累计入库量不足以执行成品实物红冲");
            BigDecimal remaining = event.getReverseQuantity();
            for (int index = allocations.size() - 1; index >= 0 && remaining.signum() > 0; index--) {
                MesApsBatchAllocationEntity allocation = allocations.get(index);
                BigDecimal old = zero(allocation.getInStockQuantity());
                BigDecimal reversed = old.min(remaining);
                if (reversed.signum() == 0) continue;
                BigDecimal target = old.subtract(reversed);
                BigDecimal delta = reversed.negate();
                allocation.setInStockQuantity(target);
                require(allocationMapper.updateById(allocation) == 1, "APS批次入库分摊回冲失败");
                callbacks.add(callback(task, allocation.getIssueNo(), allocation.getIssueId(), event, delta));
                remaining = remaining.subtract(reversed);
            }
            require(remaining.signum() == 0, "APS批次累计入库量分配不足以执行本次红冲");
        }
        R<Boolean> result = callbacks.isEmpty() ? R.ok(Boolean.TRUE) : planService.edit(callbacks);
        require(result != null && result.isOk() && Boolean.TRUE.equals(result.getData()),
                "PLAN未确认成品入库负向回写");
    }

    private OmsOrderLifeCycleDTO callback(MesTaskEntity task, String issueNo, Long issueId,
                                          MesFinishedGoodsReceiptReverseEvent event, BigDecimal delta) {
        OmsOrderLifeCycleDTO result = new OmsOrderLifeCycleDTO();
        String requestId = "MES:IN_WMS_REVERSE:" + event.getEventId() + ":"
                + Objects.requireNonNullElse(issueId, issueNo);
        require(requestId.length() <= 200, "PLAN红冲回调永久请求号长度超过200");
        result.setRequestId(requestId);
        result.setNo(issueNo);
        result.setProductCode(task.getMatCode());
        result.setType("mes");
        result.setStatus(OrderLongStateEnum.WAREHOUSE.getCode());
        result.setFinishNum(delta);
        result.setContent("WMS成品实物红冲已批准，MES入库回执=" + event.getSourceMesInboundRequestId()
                + "，本次来源计划回冲=" + delta.abs().stripTrailingZeros().toPlainString()
                + "，原因=" + event.getReason());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public MesFinishedGoodsReceiptReverseEvent.Receipt receipt(String eventId) {
        require(StrUtil.isNotBlank(eventId) && eventId.length() <= 100, "成品实物红冲事件号为空或过长");
        MesFinishedGoodsReceiptReverseInboxEntity row = getOne(
                new LambdaQueryWrapper<MesFinishedGoodsReceiptReverseInboxEntity>()
                        .eq(MesFinishedGoodsReceiptReverseInboxEntity::getEventId, eventId), false);
        if (row == null) return null;
        MesFinishedGoodsReceiptReverseEvent.Receipt result = JSONUtil.toBean(
                row.getReceiptJson(), MesFinishedGoodsReceiptReverseEvent.Receipt.class);
        require(result != null && Objects.equals(result.getEventId(), row.getEventId())
                        && Objects.equals(result.getFingerprint(), row.getFingerprint())
                        && Objects.equals(result.getCorrectionId(), row.getCorrectionId())
                        && Objects.equals(result.getSourceMesInboundRequestId(), row.getSourceMesInboundRequestId())
                        && Objects.equals(result.getMesReceiptId(), row.getMesReceiptId())
                        && result.getAcceptedTime() != null,
                "MES成品实物红冲永久回执内容异常");
        return result;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    private record ReverseAllocation(MesTaskInWmsReceiptDetailEntity detail, BigDecimal quantity) {
    }
}
