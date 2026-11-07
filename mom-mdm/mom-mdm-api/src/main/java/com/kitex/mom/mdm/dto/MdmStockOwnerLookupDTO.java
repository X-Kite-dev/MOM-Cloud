package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 当前人员实际工厂内的外部货主精确身份查询，不接受客户端指定组织或人员。
 */
@Data
@Schema(description = "当前人员实际工厂内的外部货主精确身份查询，不接受客户端指定组织或人员。")
public class MdmStockOwnerLookupDTO {

	/**
	 * 货权类型，仅 CUSTOMER_SUPPLIED 客供或 CONSIGNED 寄售
	 */
	@Schema(description = "货权类型，仅 CUSTOMER_SUPPLIED 客供或 CONSIGNED 寄售")
	@JsonDeserialize(using = StrictJsonTextDeserializer.class)
	private String ownerType;

	/**
	 * 精确货主编码，保留原文，非空且最多64字符，不允许首尾空白
	 */
	@Schema(description = "精确货主编码，保留原文，非空且最多64字符，不允许首尾空白")
	@JsonDeserialize(using = StrictJsonTextDeserializer.class)
	private String ownerCode;

	/**
	 * 可空的预期货主主键；有值时必须为规范正int64文本，先查编码唯一再核对主键
	 */
	@Schema(description = "可空的预期货主主键；有值时必须为规范正int64文本，先查编码唯一再核对主键")
	@JsonDeserialize(using = StrictJsonTextDeserializer.class)
	private String expectedOwnerId;
}
