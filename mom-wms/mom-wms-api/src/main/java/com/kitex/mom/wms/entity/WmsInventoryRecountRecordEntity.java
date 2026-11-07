package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次复盘的原数、新数与操作依据；只新增和查询，不原地修改。
 */
@Data
@TenantTable
@TableName("wms_inventory_recount_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "盘点复盘履历")
public class WmsInventoryRecountRecordEntity extends BaseEntity<WmsInventoryRecountRecordEntity> {
	private Long planId;
	private Long planDetailId;
	private String requestId;
	private Double previousNum;
	private Double recountNum;
	private String reason;
	private String operatorCode;
	private LocalDateTime operatedTime;

	@TableField(fill = FieldFill.INSERT)
	private String factoryCode;

	/**
	 * 按货权、归属方、来源和单位冻结的原账面与实盘依据。
	 */
	private String ownerCountJson;

	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	public String getRecountNumText() {
		return quantityText(recountNum);
	}

	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	public String getPreviousNumText() {
		return quantityText(previousNum);
	}

	private String quantityText(Double value) {
		return value == null || !Double.isFinite(value) || value < 0 ? null
				: BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}
}
