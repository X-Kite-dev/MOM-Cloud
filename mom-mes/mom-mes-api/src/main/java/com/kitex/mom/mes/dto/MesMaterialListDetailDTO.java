package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesMaterialListDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 用料清单明细表 DTO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "用料清单明细表DTO")
public class MesMaterialListDetailDTO extends MesMaterialListDetailEntity {

    /**
     * 领退料记录列表
     */
    @Schema(description = "领退料记录列表")
    private List<MesMaterialListRecordDTO> pickRecords;

    /**
     * 待领数量。普通工单扣净领用，批准返工单扣累计领用
     */
    @Schema(description = "待领数量。普通工单扣净领用，批准返工单扣累计领用")
    private BigDecimal remainingQuantity;

    /**
     * 领料状态名称
     */
    @Schema(description = "领料状态名称")
    private String pickStatusName;

    /**
     * 同工厂同冻结单位的线边账面量，异常或混合单位待核
     */
    @Schema(description = "同工厂同冻结单位的线边账面量，异常或混合单位待核")
    private BigDecimal stockQuantity;

    /**
     * 是否库存不足
     */
    @Schema(description = "是否库存不足")
    private Boolean isStockShortage;

    /**
     * 库存不足数量
     */
    @Schema(description = "库存不足数量")
    private BigDecimal shortageQuantity;
}
