package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 当前人员实际工厂内的货主候选查询，禁止空关键词全量目录。
 */
@Data
@Schema(description = "当前人员实际工厂内的货主候选查询，禁止空关键词全量目录。")
public class MdmStockOwnerSearchDTO {

	/**
	 * 货权类型，仅 CUSTOMER_SUPPLIED 客供或 CONSIGNED 寄售
	 */
	@Schema(description = "货权类型，仅 CUSTOMER_SUPPLIED 客供或 CONSIGNED 寄售")
	@JsonDeserialize(using = StrictJsonTextDeserializer.class)
	private String ownerType;

	/**
	 * 编码或名称的字面子串关键词，修剪后1至100字符，最多返回20条
	 */
	@Schema(description = "编码或名称的字面子串关键词，修剪后1至100字符，最多返回20条")
	@JsonDeserialize(using = StrictJsonTextDeserializer.class)
	private String keyword;
}
