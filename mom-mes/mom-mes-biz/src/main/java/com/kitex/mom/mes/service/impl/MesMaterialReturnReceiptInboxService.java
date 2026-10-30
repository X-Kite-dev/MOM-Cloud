package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesMaterialReturnReceiptEvent;
import com.kitex.mom.mes.entity.MesMaterialReturnOperationEntity;
import com.kitex.mom.mes.entity.MesMaterialReturnReceiptInboxEntity;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.mapper.MesMaterialReturnOperationMapper;
import com.kitex.mom.mes.mapper.MesMaterialReturnReceiptInboxMapper;
import com.kitex.mom.mes.mapper.MesWarehouseStockFeedJudgeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * 接收WMS已经记账的生产退料实收事实。
 *
 * <p>
 * 以永久事件号幂等，以冻结指纹防篡改；累计实收只能单调递增，差异状态不能反向覆盖。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class MesMaterialReturnReceiptInboxService {
    private static final Set<String> RECEIPT_STATES = Set.of(
            "WAITING", "RECEIVING", "RECEIVED", "VARIANCE_PENDING", "CLOSED_WITH_VARIANCE");

    private final MesMaterialReturnReceiptInboxMapper inboxMapper;
    private final MesWarehouseStockFeedJudgeMapper requestMapper;
    private final MesMaterialReturnOperationMapper operationMapper;

    @Transactional(rollbackFor = Exception.class)
    public MesMaterialReturnReceiptEvent.Receipt receive(MesMaterialReturnReceiptEvent event) {
        require(event != null && Objects.equals(event.getFingerprint(), event.calculateFingerprint()),
                "WMS生产退料实收事件指纹不一致");
        require(RECEIPT_STATES.contains(event.getReceiptStatus()), "WMS生产退料实收状态不受支持");
        require(Boolean.valueOf(Set.of("RECEIVED", "CLOSED_WITH_VARIANCE").contains(event.getReceiptStatus()))
                .equals(event.getFinalReceipt()), "WMS实收终结标记与状态不一致");
        require(!"RECEIVED".equals(event.getReceiptStatus())
                        || event.getCumulativeReceivedQuantity().compareTo(event.getExpectedQuantity()) == 0,
                "WMS未足量实收不能标记收货完成");

        MesWarehouseStockFeedJudgeEntity request = requestMapper.selectOne(
                new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
                        .eq(MesWarehouseStockFeedJudgeEntity::getId, event.getReturnRequestId())
                        .last("FOR UPDATE"));
        // 先锁申请再首次查询回执，避免MyBatis事务内缓存锁前“未接收”的结果而误拒合法重投。
        MesMaterialReturnReceiptEvent.Receipt existing = accepted(event);
        if (existing != null) {
            return existing;
        }
        require(request != null && "V2".equals(request.getWorkflowVersion()) && "APPROVED".equals(request.getStatus()),
                "WMS实收事件没有对应的已批准生产退料申请");
        require(Objects.equals(request.getFactoryCode(), event.getFactoryCode()), "WMS实收事件与MES退料申请工厂不一致");
        require(request.getWmsNoticeId() == null || Objects.equals(request.getWmsNoticeId(), event.getWmsNoticeId()),
                "MES退料申请不能切换原WMS实收通知");
        require(request.getNum() != null && request.getNum().compareTo(event.getExpectedQuantity()) == 0,
                "WMS通知量与MES批准退料量不一致");
        require(request.getWmsRequestId() != null
                        && request.getWmsRequestId().equals("MES-MATERIAL-RETURN:" + request.getId()),
                "MES退料申请缺少可信WMS来源号");

        BigDecimal beforeQuantity = zero(request.getWmsActualReceivedNum());
        require(event.getCumulativeReceivedQuantity().compareTo(beforeQuantity) >= 0,
                "WMS累计实收数量不能回退");
        require(event.getCumulativeReceivedQuantity().subtract(beforeQuantity)
                        .compareTo(event.getReceiptQuantity()) == 0,
                "WMS本次实收与累计增量不一致");
        String beforeStatus = request.getWmsReceiptStatus();
        require(validTransition(beforeStatus, event.getReceiptStatus()), "WMS实收状态不允许反向流转");
        require(!Set.of("RECEIVED", "CLOSED_WITH_VARIANCE").contains(Objects.toString(beforeStatus, ""))
                        || event.getReceiptQuantity().signum() == 0,
                "已终结的退料申请不能继续增加实收");

        LocalDateTime acceptedTime = LocalDateTime.now();
        String readiness = event.getCumulativeReceivedQuantity().signum() > 0
                ? "REPLENISHED"
                : (Boolean.TRUE.equals(event.getFinalReceipt()) ? "NO_RECEIPT" : "WAITING");
        request.setWmsNoticeId(event.getWmsNoticeId())
                .setWmsReceiptStatus(event.getReceiptStatus())
                .setWmsActualReceivedNum(event.getCumulativeReceivedQuantity())
                .setWmsShortageNum(event.getShortageQuantity())
                .setWmsOverageNum(event.getOverageQuantity())
                .setWmsReceiptTime(event.getReceiptTime())
                .setReadinessFeedbackStatus(readiness)
                .setLastReceiptEventId(event.getEventId())
                .setLastReceiptFingerprint(event.getFingerprint());
        require(requestMapper.updateById(request) == 1, "MES生产退料实收反馈保存失败");

        MesMaterialReturnReceiptEvent.Receipt receipt = new MesMaterialReturnReceiptEvent.Receipt()
                .setEventId(event.getEventId()).setFingerprint(event.getFingerprint())
                .setReturnRequestId(request.getId()).setWmsNoticeId(event.getWmsNoticeId())
                .setFactoryCode(event.getFactoryCode())
                .setCumulativeReceivedQuantity(event.getCumulativeReceivedQuantity())
                .setShortageQuantity(event.getShortageQuantity()).setOverageQuantity(event.getOverageQuantity())
                .setReceiptStatus(event.getReceiptStatus()).setReadinessFeedbackStatus(readiness)
                .setAcceptedTime(acceptedTime);
        require(inboxMapper.insert(new MesMaterialReturnReceiptInboxEntity()
                .setEventId(event.getEventId()).setReturnRequestId(request.getId())
                .setWmsNoticeId(event.getWmsNoticeId()).setWmsInBillId(event.getWmsInBillId())
                .setFingerprint(event.getFingerprint()).setPayloadJson(JSONUtil.toJsonStr(event))
                .setReceiptJson(JSONUtil.toJsonStr(receipt)).setAcceptedTime(acceptedTime)
                .setFactoryCode(event.getFactoryCode())) == 1, "MES生产退料实收永久回执保存失败");
        require(operationMapper.insert(new MesMaterialReturnOperationEntity()
                .setReturnRequestId(request.getId()).setRequestId(event.getEventId())
                .setRequestFingerprint(event.getFingerprint()).setOperationType("WMS_RECEIPT")
                .setBeforeStatus(beforeStatus).setAfterStatus(event.getReceiptStatus())
                .setContent(JSONUtil.toJsonStr(receipt)).setOperatorCode("WMS")
                .setOperatorName("WMS实收回写").setOperationTime(acceptedTime)
                .setFactoryCode(event.getFactoryCode())) == 1, "MES生产退料实收履历保存失败");
        return receipt;
    }

    private MesMaterialReturnReceiptEvent.Receipt accepted(MesMaterialReturnReceiptEvent event) {
        MesMaterialReturnReceiptInboxEntity row = inboxMapper.selectOne(
                new LambdaQueryWrapper<MesMaterialReturnReceiptInboxEntity>()
                        .eq(MesMaterialReturnReceiptInboxEntity::getEventId, event.getEventId()));
        if (row == null) {
            return null;
        }
        require(Objects.equals(row.getFingerprint(), event.getFingerprint()), "同一WMS实收事件不能覆盖不同事实");
        return receipt(event.getEventId());
    }

    @Transactional(readOnly = true)
    public MesMaterialReturnReceiptEvent.Receipt receipt(String eventId) {
        require(eventId != null && !eventId.isBlank() && eventId.length() <= 100, "WMS实收事件号为空或过长");
        MesMaterialReturnReceiptInboxEntity row = inboxMapper.selectOne(
                new LambdaQueryWrapper<MesMaterialReturnReceiptInboxEntity>()
                        .eq(MesMaterialReturnReceiptInboxEntity::getEventId, eventId));
        if (row == null) {
            return null;
        }
        MesMaterialReturnReceiptEvent.Receipt receipt = JSONUtil.toBean(
                row.getReceiptJson(), MesMaterialReturnReceiptEvent.Receipt.class);
        require(receipt != null && Objects.equals(receipt.getEventId(), row.getEventId())
                        && Objects.equals(receipt.getFingerprint(), row.getFingerprint())
                        && Objects.equals(receipt.getReturnRequestId(), row.getReturnRequestId())
                        && Objects.equals(receipt.getWmsNoticeId(), row.getWmsNoticeId())
                        && Objects.equals(receipt.getFactoryCode(), row.getFactoryCode())
                        && receipt.getAcceptedTime() != null,
                "MES生产退料实收回执内容异常");
        return receipt;
    }

    private boolean validTransition(String before, String after) {
        if (before == null || before.isBlank() || Objects.equals(before, after)) {
            return true;
        }
        if (Set.of("RECEIVED", "CLOSED_WITH_VARIANCE").contains(before)) {
            return false;
        }
        if ("VARIANCE_PENDING".equals(before)) {
            return Set.of("RECEIVING", "CLOSED_WITH_VARIANCE").contains(after);
        }
        if ("RECEIVING".equals(before)) {
            return Set.of("RECEIVED", "VARIANCE_PENDING", "CLOSED_WITH_VARIANCE").contains(after);
        }
        return "WAITING".equals(before)
                && Set.of("RECEIVING", "RECEIVED", "VARIANCE_PENDING", "CLOSED_WITH_VARIANCE").contains(after);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void require(boolean value, String message) {
        if (!value) {
            throw new CheckedException(message);
        }
    }
}
