package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesWipTransferCommandCodec.require;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.*;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWipTransferCommand;
import com.kitex.mom.mes.entity.*;

import java.math.BigDecimal;
import java.util.*;

/** MES发出事实来源核验，不以当前库存余量或今日主数据重建历史。 */
final class MesWipTransferOriginVerifier {
    private MesWipTransferOriginVerifier() {}

    record Origin(
            Long stockId,
            Long reportId,
            String operationCode,
            String arrangeCode,
            String matCode,
            com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.Selection boundTarget) {}

    static Origin verify(MesWipTransferEntity t, List<MesWipTransferOperationEntity> originals) {
        try {
            return checked(t, originals);
        } catch (CheckedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CheckedException("原发出依据损坏，不能解释下一站", e);
        }
    }

    private static Origin checked(
            MesWipTransferEntity t, List<MesWipTransferOperationEntity> originals) {
        require(
                t != null
                        && t.getId() != null
                        && t.getTenantId() != null
                        && text(t.getFactoryCode())
                        && "0".equals(t.getDelFlag())
                        && t.getRevision() != null
                        && t.getRevision() > 0
                        && Set.of("TRANSIT", "HOLD", "RETURNING", "CLOSED")
                                .contains(Objects.toString(t.getStatus(), "")),
                "转运单身份或状态不完整");
        require(originals != null && originals.size() == 1, "缺少唯一原DISPATCH发出记录，不能借当前库存恢复来源");
        var op = originals.get(0);
        require(
                op.getId() != null
                        && Objects.equals(op.getTransferId(), t.getId())
                        && Objects.equals(op.getTenantId(), t.getTenantId())
                        && Objects.equals(op.getFactoryCode(), t.getFactoryCode())
                        && "0".equals(op.getDelFlag())
                        && Long.valueOf(1).equals(op.getSequenceNo())
                        && "DISPATCH".equals(op.getOperationType())
                        && op.getOperatorId() != null
                        && op.getOperatorId() > 0
                        && Objects.equals(op.getOperatorId(), t.getDispatchUserId())
                        && "NONE".equals(op.getBeforeStatus())
                        && "TRANSIT".equals(op.getAfterStatus()),
                "原发出身份、序号或状态不一致");
        var evidence = object(op.getEvidence());
        var command = evidence.getJSONObject("command");
        require(command != null, "原发出命令未留存");
        var c = JSONUtil.toBean(command, MesWipTransferCommand.class);
        MesWipTransferCommandCodec.normalize(c);
        require(
                "DISPATCH".equals(c.getOperationType())
                        && Objects.equals(c.getRequestId(), op.getRequestId())
                        && Objects.equals(
                                MesWipTransferCommandCodec.fingerprint(c, op.getOperatorId()),
                                op.getFingerprint())
                        && Objects.equals(c.getSourceStockId(), integer(command, "sourceStockId"))
                        && c.getTransferId() == null
                        && Objects.equals(c.getSourceStockId(), t.getSourceStockId())
                        && Boolean.TRUE.equals(c.getConditionConfirmed())
                        && Objects.equals(c.getReason(), op.getReason())
                        && Objects.equals(c.getFromLocationCode(), t.getFromLocationCode())
                        && Objects.equals(c.getToLocationCode(), t.getToLocationCode())
                        && Objects.equals(c.getDueTime(), t.getDueTime()),
                "原发出命令、指纹或转运依据不一致");
        var source = object(t.getSourceSnapshot());
        var copiedSource = evidence.getJSONObject("source");
        var locations = evidence.getJSONArray("locations");
        require(
                copiedSource != null
                        && source.toString().equals(copiedSource.toString())
                        && locations != null
                        && locations.toString().equals(t.getLocationSnapshot())
                        && locations.size() == 2,
                "原库存或库位快照与发出原件不一致");
        require(
                Objects.equals(integer(source, "id"), t.getSourceStockId())
                        && Objects.equals(integer(source, "tenantId"), t.getTenantId())
                        && Objects.equals(source.getStr("factoryCode"), t.getFactoryCode())
                        && "0".equals(source.getStr("delFlag"))
                        && "WIP".equals(source.getStr("matDetailType"))
                        && Objects.equals(integer(source, "sourceReportId"), t.getSourceReportId())
                        && Objects.equals(source.getStr("produceTaskCode"), t.getProduceTaskCode())
                        && Objects.equals(source.getStr("billNo"), t.getArrangeCode())
                        && Objects.equals(source.getStr("matCode"), t.getMatCode())
                        && Objects.equals(source.getStr("matBatch"), t.getMatBatch())
                        && Objects.equals(source.getStr("matBarcode"), t.getMatBarcode())
                        && Objects.equals(source.getStr("matUnit"), t.getMatUnit())
                        && text(t.getMatUnit()),
                "原库存快照身份与转运来源不一致");
        require(
                Objects.equals(
                        StrUtil.blankToDefault(t.getMatBarcode(), ""),
                        StrUtil.blankToDefault(c.getObservedBarcode(), "")),
                "原命令扫描条码与库存快照不一致");
        require(
                text(t.getFromLocationCode())
                        && text(t.getToLocationCode())
                        && !t.getFromLocationCode().equals(t.getToLocationCode()),
                "原转运来源/目标库位缺失");
        var from = locations.getJSONObject(0);
        var to = locations.getJSONObject(1);
        location(from, t, t.getFromLocationCode());
        location(to, t, t.getToLocationCode());
        require(
                text(from.getStr("warehouseCode"))
                        && Objects.equals(from.getStr("warehouseCode"), to.getStr("warehouseCode")),
                "原转运库位不属于同一仓库");
        require(
                t.getQuantity() != null
                        && t.getQuantity().signum() > 0
                        && t.getRemainingQuantity() != null
                        && t.getRemainingQuantity().signum() >= 0
                        && t.getRemainingQuantity().compareTo(t.getQuantity()) <= 0
                        && same(t.getQuantity(), op.getQuantity())
                        && same(c.getQuantity(), op.getQuantity())
                        && same(op.getBeforeRemaining(), BigDecimal.ZERO)
                        && same(op.getAfterRemaining(), op.getQuantity())
                        && same(source.getBigDecimal("num"), op.getSourceBeforeNum())
                        && same(c.getExpectedSourceQuantity(), op.getSourceBeforeNum())
                        && op.getSourceAfterNum() != null
                        && op.getSourceAfterNum().signum() >= 0
                        && same(
                                op.getSourceBeforeNum().subtract(op.getQuantity()),
                                op.getSourceAfterNum())
                        && Objects.equals(op.getLocationCode(), t.getFromLocationCode()),
                "原发出数量与库存扣减不守恒");
        require(
                !"CLOSED".equals(t.getStatus()) || t.getRemainingQuantity().signum() == 0,
                "已结清转运单仍有剩余在途，须先核对原单");
        require(
                text(t.getProduceTaskCode()) && text(t.getArrangeCode()) && text(t.getMatCode()),
                "原发出业务来源缺失");
        return new Origin(
                t.getSourceStockId(),
                t.getSourceReportId(),
                t.getProduceTaskCode(),
                t.getArrangeCode(),
                t.getMatCode(),
                c.getDispatchTarget());
    }

    private static void location(JSONObject l, MesWipTransferEntity t, String code) {
        require(
                l != null
                        && Objects.equals(integer(l, "tenantId"), t.getTenantId())
                        && Objects.equals(l.getStr("factoryCode"), t.getFactoryCode())
                        && Objects.equals(l.getStr("locationCode"), code)
                        && Boolean.TRUE.equals(l.getBool("status"))
                        && Integer.valueOf(3).equals(l.getInt("locationLevel")),
                "原库位快照身份不一致");
    }

    private static JSONObject object(String raw) {
        require(
                raw != null && raw.length() <= 262144 && JSONUtil.isTypeJSONObject(raw),
                "原发出JSON未留存或超出范围");
        return JSONUtil.parseObj(raw);
    }

    private static Long integer(JSONObject json, String key) {
        var v = json.get(key);
        String s = v == null ? "" : v.toString();
        require(s.matches("[1-9][0-9]{0,18}"), "原发出ID格式不完整");
        return Long.valueOf(s);
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank();
    }
}
