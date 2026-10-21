package com.kitex.mom.mes.dto;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * WMS正式发料V1事件；只建待签收通知，不代表现场已经收料。
 */
@Data
@Accessors(chain = true)
public class MesWmsIssueEvent {
    private String eventId;
    private Integer version;
    private Long wmsBillId;
    private String wmsBillNo;
    private String factoryCode;
    private String fingerprint;
    private MesPickingWmsOutBillBackDTO callback;

    public String calculateFingerprint() {
        if (!Integer.valueOf(1).equals(version) || wmsBillId == null || !Objects.equals(eventId, "WMS-MES-ISSUE:" + wmsBillId)
                || wmsBillNo == null || wmsBillNo.isBlank() || factoryCode == null || factoryCode.isBlank()
                || callback == null || callback.getId() == null || callback.getMatDetails() == null || callback.getMatDetails().isEmpty())
            throw new CheckedException("WMS发料事件版本或来源不完整");
        List<MesWarehouseMaterialInBillEntity> details = callback.getMatDetails();
        if (details.stream().anyMatch(d -> d == null || d.getWmsOutDetailId() == null || d.getNoticeInNum() == null
                || d.getNoticeInNum().signum() <= 0 || !Objects.equals(d.getResourceNo(), wmsBillNo)
                || !Objects.equals(d.getFactoryCode(), factoryCode))
                || details.stream().map(MesWarehouseMaterialInBillEntity::getWmsOutDetailId).distinct().count() != details.size())
            throw new CheckedException("发料事件明细重复或数量/工厂/出库单来源不一致");
        List<Object> facts = new ArrayList<>();
        facts.add(Arrays.asList(version, eventId, wmsBillId, wmsBillNo, factoryCode, callback.getId()));
        details.stream().sorted(Comparator.comparing(MesWarehouseMaterialInBillEntity::getWmsOutDetailId)).forEach(d ->
                facts.add(Arrays.asList(d.getWmsOutDetailId(), d.getResourceNo(), d.getFactoryCode(), d.getMatCode(),
                        d.getMatName(), d.getMatSpec(), d.getMatUnit(), d.getMatBatch(), d.getMatBarcode(),
                        d.getSupplierCode(), d.getSupplierName(), d.getWarehouseCode(), d.getWarehouseName(),
                        d.getMatProduceDate(), d.getMatValidPeriod(), d.getMatValidEndDate(), d.getInspectionStatus(),
                        number(d.getNoticeInNum()), d.getReservationNo(), d.getRequirementMatCode(), d.getRequirementMatName(),
                        number(d.getRequirementMatNum()), d.getSubstituteFlag(), number(d.getSubstituteRatio()))));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSONUtil.toJsonStr(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String eventId;
        private String fingerprint;
        private Long wmsBillId;
        private Long pickingBillId;
        private String factoryCode;
        private Integer detailCount;
        private List<Long> noticeIds;
        private LocalDateTime acceptedTime;
    }
}
