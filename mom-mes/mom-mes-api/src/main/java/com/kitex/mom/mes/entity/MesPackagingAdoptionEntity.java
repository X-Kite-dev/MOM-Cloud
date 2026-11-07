package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 派工采用的不可变包装依据；后续报工和封箱不查询当前规则。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_packaging_adoption")
@Schema(description = "派工采用的不可变包装依据；后续报工和封箱不查询当前规则。")
public class MesPackagingAdoptionEntity extends BaseEntity<MesPackagingAdoptionEntity> {

	/** 实际工厂编码。 */
	@Schema(description = "实际工厂编码")
	private String factoryCode;

	/** 精确原派工主键，每组织仅采用一次。 */
	@Schema(description = "精确原派工主键，每组织仅采用一次")
	private Long arrangeId;

	/** 精确原派工编码。 */
	@Schema(description = "精确原派工编码")
	private String arrangeCode;

	/** 真实原工单编码。 */
	@Schema(description = "真实原工单编码")
	private String taskCode;

	/** 真实原工序任务编码。 */
	@Schema(description = "真实原工序任务编码")
	private String produceTaskCode;

	/** 冻结包装模式 PACKAGE_ONE_CODE/PACKAGE_QUANTITY。 */
	@Schema(description = "冻结包装模式 PACKAGE_ONE_CODE/PACKAGE_QUANTITY")
	private String reportMode;

	/** 采用协议 MES_PACKAGING_ADOPTION_V1。 */
	@Schema(description = "采用协议 MES_PACKAGING_ADOPTION_V1")
	private String adoptionVersion;

	/** 来源 NEW_DISPATCH/LEGACY_ADOPTION。 */
	@Schema(description = "来源 NEW_DISPATCH/LEGACY_ADOPTION")
	private String adoptionKind;

	/** 采用时已启用包装规则主键。 */
	@Schema(description = "采用时已启用包装规则主键")
	private Long ruleId;

	/** 采用时包装规则编码。 */
	@Schema(description = "采用时包装规则编码")
	private String ruleCode;

	/** 冻结每箱最大数量，单位与原报工基本单位一致。 */
	@Schema(description = "冻结每箱最大数量，单位与原报工基本单位一致")
	private Integer capacity;

	/** 采用规则与容量及条码定义的完整JSON原文。 */
	@Schema(description = "采用规则与容量及条码定义的完整JSON原文")
	private String sourceSnapshot;

	/** 完整采用原文UTF-8 SHA-256。 */
	@Schema(description = "完整采用原文UTF-8 SHA-256")
	private String sourceFingerprint;

	/** 逐件模式原Common条码定义，数量模式为空。 */
	@Schema(description = "逐件模式原Common条码定义，数量模式为空")
	private String barcodeDefinitionSnapshot;

	/** 实际采用人员主键。 */
	@Schema(description = "实际采用人员主键")
	private Long actorId;

	/** 实际采用人员账号。 */
	@Schema(description = "实际采用人员账号")
	private String actorCode;

	/** 派工冻结或历史任务显式采用原因。 */
	@Schema(description = "派工冻结或历史任务显式采用原因")
	private String reason;

	/** 服务器采用时刻。 */
	@Schema(description = "服务器采用时刻")
	private LocalDateTime adoptedAt;

}
