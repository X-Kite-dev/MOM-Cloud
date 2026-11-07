package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 外部货主最小真实身份；不包含账户、地址、联系人或深关联。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "外部货主最小真实身份；不包含账户、地址、联系人或深关联。")
public class MdmStockOwnerIdentityDTO {

	/**
	 * 真实货主主键，规范正int64文本
	 */
	@Schema(description = "真实货主主键，规范正int64文本")
	private String id;

	/**
	 * 真实所属租户主键，规范正int64文本
	 */
	@Schema(description = "真实所属租户主键，规范正int64文本")
	private String tenantId;

	/**
	 * 真实所属工厂编码，沿实际认证上下文核对，不改写原文
	 */
	@Schema(description = "真实所属工厂编码，沿实际认证上下文核对，不改写原文")
	private String factoryCode;

	/**
	 * 货权类型，CUSTOMER_SUPPLIED 为客户，CONSIGNED 为供应商
	 */
	@Schema(description = "货权类型，CUSTOMER_SUPPLIED 为客户，CONSIGNED 为供应商")
	private String ownerType;

	/**
	 * 真实货主编码，保留原文
	 */
	@Schema(description = "真实货主编码，保留原文")
	private String code;

	/**
	 * 真实货主名称，保留原文
	 */
	@Schema(description = "真实货主名称，保留原文")
	private String name;

	/**
	 * 真实启用状态，true启用、false停用、null未知
	 */
	@Schema(description = "真实启用状态，true启用、false停用、null未知")
	private Boolean enabled;

	/**
	 * 供应商真实审核状态，0待审核、1通过、2驳回；客户无此字段，明确为null
	 */
	@Schema(description = "供应商真实审核状态，0待审核、1通过、2驳回；客户无此字段，明确为null")
	private String supplierCheckStatus;

	/**
	 * 当前主体资格；客户须启用，供应商须启用且审核状态为1；不代表WMS已获业务办理授权
	 */
	@Schema(description = "当前主体资格；客户须启用，供应商须启用且审核状态为1；不代表WMS已获业务办理授权")
	private Boolean eligible;
}
