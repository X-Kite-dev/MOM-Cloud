package com.kitex.mom.plan.dto;

import com.kitex.common.core.exception.CheckedException;

import java.math.BigDecimal;
import java.util.*;

/**
 * 正式合同中可由同一执行订单承接的自制明细；PLAN与MES共用口径，不合并原始合同事实。
 */
public record PlnFormalPlanMakeCoverage(String sourceDemandNo, String productCode, String unitCode,
                                        String bomVersion, String routeVersion, String erpProductionOrderNo,
                                        BigDecimal quantity, List<MakeLine> lines) {

    public PlnFormalPlanMakeCoverage {
        lines = List.copyOf(lines);
    }

    public static PlnFormalPlanMakeCoverage resolve(PlnFormalPlanContractDTO contract,
                                                    String demandNo, String productCode) {
        require(text(demandNo) && text(productCode) && contract != null && contract.getLines() != null,
                "正式合同分摊缺少来源需求、产品或合同明细");
        Set<String> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        List<PlnFormalPlanContractDTO.Line> matched = new ArrayList<>();
        for (var line : contract.getLines()) {
            require(line != null && positiveId(line.getId()) && text(line.getLineNo())
                            && ids.add(line.getId()) && numbers.add(line.getLineNo()),
                    "正式合同明细主键或行号缺失、重复");
            if ("MAKE".equals(line.getSupplyType()) && Objects.equals(demandNo, line.getSourceDemandNo())
                    && Objects.equals(productCode, line.getProductCode())) matched.add(line);
        }
        require(!matched.isEmpty(), "正式合同没有覆盖来源需求与产品的自制行");
        matched.sort(Comparator.comparing(PlnFormalPlanContractDTO.Line::getLineNo)
                .thenComparing(PlnFormalPlanContractDTO.Line::getId));
        var first = matched.get(0);
        BigDecimal total = BigDecimal.ZERO;
        List<MakeLine> lines = new ArrayList<>();
        for (var line : matched) {
            require(line.getPlannedQuantity() != null && line.getPlannedQuantity().signum() > 0
                            && text(line.getUnitCode()) && fingerprint(line.getContentFingerprint()),
                    "正式合同自制行缺少有效数量、计量或指纹");
            require(Objects.equals(first.getUnitCode(), line.getUnitCode())
                            && Objects.equals(first.getBomVersion(), line.getBomVersion())
                            && Objects.equals(first.getRouteVersion(), line.getRouteVersion())
                            && Objects.equals(blank(first.getErpProductionOrderNo()), blank(line.getErpProductionOrderNo())),
                    "同产品自制行的单位、BOM、路线或ERP生产订单不一致，须按明确来源拆分");
            total = total.add(line.getPlannedQuantity());
            lines.add(new MakeLine(line.getId(), line.getLineNo(), line.getPlannedQuantity(), line.getContentFingerprint()));
        }
        return new PlnFormalPlanMakeCoverage(demandNo, productCode, first.getUnitCode(), first.getBomVersion(),
                first.getRouteVersion(), blank(first.getErpProductionOrderNo()), total, lines);
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean positiveId(String value) {
        try {
            return value != null && Long.parseLong(value) > 0 && Long.toString(Long.parseLong(value)).equals(value);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public boolean contains(Long representativeId) {
        return representativeId != null && lines.stream().anyMatch(line -> line.id().equals(representativeId.toString()));
    }

    public record MakeLine(String id, String lineNo, BigDecimal quantity, String fingerprint) {
    }
}
