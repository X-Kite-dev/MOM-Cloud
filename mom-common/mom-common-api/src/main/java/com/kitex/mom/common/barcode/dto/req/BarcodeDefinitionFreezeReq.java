package com.kitex.mom.common.barcode.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 按当前实名租户和实际工厂读取条码规则，组织不能由请求体指定。
 */
@Data
@Schema(description = "条码定义冻结请求")
public class BarcodeDefinitionFreezeReq {

	/** 当前实际工厂内的条码规则编码，必须唯一且尚未删除。 */
	@NotBlank(message = "条码规则编码不能为空")
	@Schema(description = "当前实际工厂内的条码规则编码", requiredMode = Schema.RequiredMode.REQUIRED)
	private String barcodeRuleCode;

}
