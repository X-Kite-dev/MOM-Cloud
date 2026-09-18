package com.kitex.mom.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单生命周期DTO
 *
 * @author kitex
 * @date 2025-04-18 17:35:48
 */
@Data
@Schema(description = "订单生命周期DTO")
public class OmsOrderLifeCycleDTO {
    /**
     * 来源系统永久幂等号
     */
    @Schema(description = "来源系统永久幂等号；同号重试载荷必须一致")
    private String requestId;
    /**
     * 下达号
     */
    @Schema(description = "下达号")
    private String no;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 操作类型
     */
    @Schema(description = "订单操作类型")
    private String type;

    /**
     * 订单处理状态
     */
    @Schema(description = "订单处理状态")
    private String status;

    /**
     * 下达任务状态
     */
    @Schema(description = "下达任务状态")
    private String issueStatus;

    /**
     * 操作内容
     */
    @Schema(description = "操作内容")
    private String content;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal finishNum;

    @Schema(description = "MES直接不合格上报数量增量")
    private BigDecimal unqualifiedNum;

    @Schema(description = "QMS已隔离数量增量")
    private BigDecimal qualityIsolatedNum;

    @Schema(description = "QMS放行或让步接收数量增量")
    private BigDecimal qualityReleasedNum;

    @Schema(description = "QMS质量判退数量增量")
    private BigDecimal qualityRejectedNum;

    @Schema(description = "已审批报废数量增量；撤销审批时为负数")
    private BigDecimal scrapNum;

    @Schema(description = "质量处置发起返工数量增量")
    private BigDecimal reworkNum;

    @Schema(description = "返工完工数量增量")
    private BigDecimal reworkCompletedNum;

    @Schema(description = "返工复检放行数量增量")
    private BigDecimal reworkQualifiedNum;

    @Schema(description = "返工复检拒绝数量增量")
    private BigDecimal reworkRejectedNum;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private LocalDate finishTime;
}
