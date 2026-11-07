package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "工站当前实名派工及其冻结生产执行契约")
public class MesArrangeTaskVO {

	private Long id;

	private String arrangeCode;

	private String taskCode;

	private String produceTaskCode;

	@Schema(description = "物料编码")
	private String matCode;

	@Schema(description = "物料名称")
	private String matName;

	@Schema(description = "物料规格")
	private String matSpec;

	@Schema(description = "工艺路线编码")
	private String processCode;

	@Schema(description = "工序编码")
	private String routeCode;

	@Schema(description = "工序名称")
	private String routeName;

	@Schema(description = "实际派工冻结的路线节点，旧派工允许为空")
	private String sourceRouteNodeCode;

	@Schema(description = "冻结执行图协议")
	private String routeExecutionSchemaVersion;

	@Schema(description = "冻结前序节点CSV")
	private String predecessorNodeCodes;

	@Schema(description = "冻结后序节点CSV")
	private String successorNodeCodes;

	@Schema(description = "工序SOP")
	private String routeSop;

	@Schema(description = "工序类型")
	private String routeType;

	/**
	 * 派工冻结策略确定的标准报工模式，不能从工序类型或当前模板推测。
	 */
	@Schema(description = "标准报工模式，由派工冻结契约确定")
	private String reportMode;

	/**
	 * 与本次返回派工属于同一来源的执行契约；历史派工由服务端生成兼容契约。
	 */
	@Schema(description = "本派工的冻结执行契约，包括报工对象、触发来源、终端和数量控制")
	private MesExecutionContractDTO executionContract;

	@Schema(description = "工序工作中心")
	private String routeWorkCenter;

	@Schema(description = "是否最后一道工序")
	private Boolean lastRoute;

	@Schema(description = "工位编码")
	private String worksiteCodes;

	@Schema(description = "工位名称")
	private String worksiteNames;

	@Schema(description = "工位报工类型 自动 手动")
	private String worksiteTypes;

	/** 原生产工单下达总量，沿原基本单位；不能充当当前派工配额。 */
	@Schema(description = "生产工单下达总数量")
	@JsonSerialize(using = ToStringSerializer.class)
	private BigDecimal noticeNum;

	/** 当前真实派工的冻结需求或配额；旧全量派工保持原需求。 */
	@Schema(description = "本派工冻结需求或配额")
	@JsonSerialize(using = ToStringSerializer.class)
	private BigDecimal shiftNum;

	/** 本次精确派工的实际GOOD累计量，不用父工序总量冒充。 */
	@Schema(description = "本派工实际GOOD累计完成量")
	@JsonSerialize(using = ToStringSerializer.class)
	private BigDecimal dispatchCompleteNum;

	/** 本次精确派工的实际NG累计量，与GOOD配额独立观察。 */
	@Schema(description = "本派工实际NG累计量")
	@JsonSerialize(using = ToStringSerializer.class)
	private BigDecimal dispatchUnPassNum;

	/** 本次精确派工当前质量放行量，来源原QMS/MES质量数量账。 */
	@Schema(description = "本派工当前质量放行量")
	@JsonSerialize(using = ToStringSerializer.class)
	private BigDecimal dispatchQualityReleasedNum;

	@Schema(description = "已排产数量")
	private BigDecimal plannedNum;

	/** 原工序所有派工的GOOD实际累计；当前派工量及质量放行量分别观察。 */
	@Schema(description = "原工序全部派工GOOD实际累计")
	@JsonSerialize(using = ToStringSerializer.class)
	private BigDecimal completeNum;

	@Schema(description = "不合格品数量")
	private BigDecimal unPassNum;

	@Schema(description = "状态 待派工 待接收 已接收 生产中 已完成 暂停中")
	private String status;

	@Schema(description = "派工人工号")
	private String dispatcherCode;

	@Schema(description = "派工人姓名")
	private String dispatcherName;

	@Schema(description = "派工班次编码")
	private String shiftCode;

	@Schema(description = "派工班次名称")
	private String shiftName;

	@Schema(description = "派工时间")
	private Date dispatcherTime;

	@Schema(description = "上工序合格数量")
	private BigDecimal lastPassNum;

	@Schema(description = "车间编码")
	private String workshopCode;

	@Schema(description = "车间名称")
	private String workshopName;

	@Schema(description = "批次号")
	private String batchNo;

	@Schema(description = "包装规则编码")
	private String wrapRuleCode;

	@Schema(description = "包装箱码")
	private String wrapCode;

	@Schema(description = "箱码容量")
	private String wrapCapacity;

	@Schema(description = "已装箱个数")
	private Integer packageNum;

}
