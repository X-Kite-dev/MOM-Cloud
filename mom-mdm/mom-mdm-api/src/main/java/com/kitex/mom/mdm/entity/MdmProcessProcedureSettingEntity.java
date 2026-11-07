package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工艺路线-工序配置
 *
 * @author kitex
 * @date 2025-09-05 17:36:15
 */
@Data
@TenantTable
@TableName("mdm_process_procedure_setting")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线-工序配置")
public class MdmProcessProcedureSettingEntity extends BaseEntity<MdmProcessProcedureSettingEntity> {

	/**
	 * 工艺路线编码
	 */
	@Schema(description = "工艺路线编码")
	private String routeCode;

	/**
	 * 路线内稳定节点身份；主工序重复出现时各次配置相互独立。
	 */
	@Schema(description = "工艺路线节点编号，由服务端解析真实节点")
	private String routeNodeCode;

	/**
	 * 工序编码
	 */
	@Schema(description = "工序编码")
	private String processCode;

	/**
	 * 是否通过重量报工
	 */
	@Schema(description = "是否通过重量报工")
	private Boolean isMatWeightConvert = Boolean.FALSE;

	/**
	 * 报工物料重量转换
	 */
	@Schema(description = "报工物料重量转换")
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private BigDecimal matWeightConvert;

	/**
	 * 是否报工倒冲投料
	 */
	@Schema(description = "是否报工倒冲投料")
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private Boolean isBackFlush = Boolean.FALSE;

	/**
	 * 是否通过式工序
	 */
	@Schema(description = "是否通过式工序")
	private Boolean isAutomate = Boolean.FALSE;

	/**
	 * 默认报工数量
	 */
	@Schema(description = "默认报工数量")
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private BigDecimal defaultReportNum;

	/**
	 * 是否控制转序时间
	 */
	@Schema(description = "是否控制转序时间")
	private Boolean timeCheck;

	/**
	 * 转序时间要求（分钟）
	 */
	@Schema(description = "转序时间要求（分钟）")
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private BigDecimal timeVal;

	/**
	 * 工厂编码
	 */
	@Schema(description = "工厂编码")
	@TableField(fill = FieldFill.INSERT)
	private String factoryCode;
	/**
	 * 逐物料策略激活版本；NULL沿旧统一开关，V2时旧开关必须NULL以保护旧执行端。
	 */
	@Schema(description = "逐物料耗料策略版本")
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
	private String materialConsumptionSchema;

}
