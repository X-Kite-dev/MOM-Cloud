package com.kitex.mom.mes.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 用料清单领料操作 VO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Data
@Schema(description = "用料清单领料操作VO")
public class MesMaterialListPickVO {

    /**
     * 用料清单明细ID
     */
    @Schema(description = "用料清单明细ID")
    @NotNull(message = "用料清单明细ID不能为空")
    private Long materialListDetailId;

    /**
     * 操作数量
     */
    @Schema(description = "操作数量")
    @NotNull(message = "操作数量不能为空")
    @Positive(message = "操作数量必须大于0")
    private BigDecimal quantity;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 批次号
     */
    @Schema(description = "批次号")
    private String batchNo;

    /**
     * 关联单据号（出库单）
     */
    @Schema(description = "关联单据号（出库单）")
    private String referenceBillNo;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}