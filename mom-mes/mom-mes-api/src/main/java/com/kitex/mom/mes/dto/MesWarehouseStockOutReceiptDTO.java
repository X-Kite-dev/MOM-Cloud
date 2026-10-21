package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * 真实出库事务的不可变逐来源回执，仅由内部业务服务取得。
 *
 * <p>回执来自本次必要写成功并回读的实际OUT流水，不是预检、预留或历史匹配结果。
 * STOCK_LINE_V1仍只证明核心库存操作；本回执不代替永久生产操作账、投料出生全集及数据库OLD/NEW证明。</p>
 */
@Schema(description = "线边库存真实逐来源出库回执")
public record MesWarehouseStockOutReceiptDTO(
        /** 原逐库存行协议，不能将旧NULL证据升级为本版本。 */
        String version,
        /** 本次实际登录租户与工厂。 */
        Long tenantId,
        String factoryCode,
        /** 调用业务的真实主键、单据号及独立业务类型。 */
        Long billId,
        String billNo,
        String bizType,
        /** 本次请求的实际物料、原单位及总量。 */
        String materialCode,
        String materialUnit,
        BigDecimal quantity,
        /** 实际OUT全集，按真实分配顺序；集合和行对象均不可修改。 */
        List<Line> lines) {

    public MesWarehouseStockOutReceiptDTO {
        quantity = normalize(quantity);
        lines = List.copyOf(lines);
    }

    /**
     * SQL可能补尾零；数值比较不把无意义的scale差异当作事实变更。
     */
    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    /**
     * 内部投料服务选定的原库存谱系过滤条件，不是库存来源证明。
     * 两字段保留原NULL/空文本区别；未预留库存也必须精确过滤，不能因NULL省略条件而串用有预留库存。
     */
    public record Lineage(String reservationNo, String requirementMaterialCode) {
    }

    /**
     * 单个实际库存来源的一次真实OUT，保留库存自己的工序归属，不改为接收派工。
     * 源GOOD报告、签收及预留均可能为空，空值不代表已查明来源。
     */
    @Schema(description = "一次实际库存行扣减及其已保存流水")
    public record Line(
            /** 本次实际插入的OUT主键与被扣库存主键。 */
            Long inoutId,
            Long stockId,
            /** 库存原GOOD报告和原签收来源，不是本次业务主键。 */
            Long sourceReportId,
            Long materialReceiptId,
            /** 原实际租户、工厂及本次业务关联。 */
            Long tenantId,
            String factoryCode,
            Long billId,
            String billNo,
            String resourceNo,
            String bizType,
            /** 原库存工序归属，不能按接收派工覆盖。 */
            String sourceProduceTaskCode,
            /** 原物料、原单位、原批次和条码。 */
            String materialCode,
            String materialUnit,
            String batchNo,
            String barcode,
            /** 本次实际预留及BOM主替代绑定。 */
            String reservationNo,
            String requirementMaterialCode,
            Boolean substitute,
            BigDecimal substituteRatio,
            /** 原单位的实际扣量及库存操作前后量，全部精确而非四舍五入。 */
            BigDecimal quantity,
            BigDecimal beforeQuantity,
            BigDecimal afterQuantity,
            /** 原库存证据协议和本次原选择政策。 */
            String proofVersion,
            String allocationPolicy) {

        public Line {
            substituteRatio = normalize(substituteRatio);
            quantity = normalize(quantity);
            beforeQuantity = normalize(beforeQuantity);
            afterQuantity = normalize(afterQuantity);
        }
    }
}
