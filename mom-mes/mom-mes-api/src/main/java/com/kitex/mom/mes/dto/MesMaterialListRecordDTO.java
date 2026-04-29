package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesMaterialListRecordEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 用料清单领退料记录表 DTO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "用料清单领退料记录表DTO")
public class MesMaterialListRecordDTO extends MesMaterialListRecordEntity {

    /**
     * 操作类型名称
     */
    @Schema(description = "操作类型名称")
    private String operationTypeName;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String materialName;

    /**
     * 物料规格型号
     */
    @Schema(description = "物料规格型号")
    private String materialSpec;

    /**
     * 操作人员姓名
     */
    @Schema(description = "操作人员姓名")
    private String operatorName;
}