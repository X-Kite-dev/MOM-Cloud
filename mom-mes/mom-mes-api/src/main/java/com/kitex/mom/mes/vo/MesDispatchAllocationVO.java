package com.kitex.mom.mes.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.kitex.mom.mes.dto.MesDispatchAllocationDTO;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 派工权威配额余额与永久原号回执；配额和实际GOOD/NG/质量数量分别观察。
 */
public final class MesDispatchAllocationVO {

	private MesDispatchAllocationVO() {
	}

	/** 一条真实冻结工序的权威配额余额。 */
	@Data
	public static class Balance {
		/** 生产工单编码。 */
		@Schema(description = "生产工单编码")
		private String taskCode;

		/** 真实工序任务编码。 */
		@Schema(description = "真实工序任务编码")
		private String produceTaskCode;

		/** 冻结工序名称。 */
		@Schema(description = "冻结工序名称")
		private String routeName;

		/** 工程冻结图中稳定路线节点标识。 */
		@Schema(description = "工程冻结图中稳定路线节点标识")
		private String sourceRouteNodeCode;

		/** 原冻结工序允许的工站编码CSV，与余额取自同一真实组织来源。 */
		@Schema(description = "原冻结工序允许的工站编码CSV，与余额取自同一真实组织来源")
		private String worksiteCodes;

		/** 原冻结工序工站名称CSV，与编码逐项对应。 */
		@Schema(description = "原冻结工序工站名称CSV，与编码逐项对应")
		private String worksiteNames;

		/** APS锁定的执行人员编码，未锁定为空。 */
		@Schema(description = "APS锁定的执行人员编码，未锁定为空")
		private String plannedWorkerCode;

		/** APS锁定人员名称快照，允许为空。 */
		@Schema(description = "APS锁定人员名称快照，允许为空")
		private String plannedWorkerName;

		/** APS锁定工站编码，未锁定为空。 */
		@Schema(description = "APS锁定工站编码，未锁定为空")
		private String plannedWorksiteCode;

		/** APS锁定工站名称快照，允许为空。 */
		@Schema(description = "APS锁定工站名称快照，允许为空")
		private String plannedWorksiteName;

		/** APS计划设备编码，仅用于提示原资源锁。 */
		@Schema(description = "APS计划设备编码，仅用于提示原资源锁")
		private String plannedDeviceCode;

		/** APS计划资源组编码，仅用于提示原资源锁。 */
		@Schema(description = "APS计划资源组编码，仅用于提示原资源锁")
		private String plannedResourceGroupCode;


		/** 原工序需求数量，单位沿原工序物料单位。 */
		@Schema(description = "原工序需求数量，单位沿原工序物料单位")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal demandQuantity;

		/** 永久配额账当前占用总量；原件不可核验时为空。 */
		@Schema(description = "永久配额账当前占用总量；原件不可核验时为空")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal occupiedQuantity;

		/** 需求减已占用；历史或来源不可核验时为空，不能按零处理。 */
		@Schema(description = "需求减已占用；历史或来源不可核验时为空，不能按零处理")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal availableQuantity;

		/** 存在无配额账的旧全量派工历史。 */
		@Schema(description = "存在无配额账的旧全量派工历史")
		private Boolean legacyPresent;

		/** 当前来源、状态与余额允许新配额派工。 */
		@Schema(description = "当前来源、状态与余额允许新配额派工")
		private Boolean allocationAllowed;

		/** 普通节点允许多行及部分配额；动态绑定区段保持原整组唯一全量合同。 */
		@Schema(description = "普通节点允许拆分；动态绑定区段只能首次整组唯一全量派工")
		private Boolean splitAllowed;

		/** 不可新派工的明确原因；允许时为空。 */
		@Schema(description = "不可新派工的明确原因；允许时为空")
		private String blockReason;
	}

	/** 已受理原命令的不可变逐行回执。 */
	@Data
	public static class LineResult {
		/** 原命令的稳定分配行标识。 */
		@Schema(description = "原命令的稳定分配行标识")
		private String allocationKey;

		/** 本行真实工序任务编码。 */
		@Schema(description = "本行真实工序任务编码")
		private String produceTaskCode;

		/** 同事务出生的真实派工主键，以字符串传输避免精度损失。 */
		@Schema(description = "同事务出生的真实派工主键，以字符串传输避免精度损失")
		private String arrangeId;

		/** 同事务出生的真实派工编码。 */
		@Schema(description = "同事务出生的真实派工编码")
		private String arrangeCode;

		/** 本行原始冻结配额；单位沿原工序物料单位。 */
		@Schema(description = "本行原始冻结配额；单位沿原工序物料单位")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal quantity;
	}

	/** 同组织同操作人原号的永久受理或作废结果。 */
	@Data
	public static class Receipt {
		/** ABSENT仅表示当前未查到；ACCEPTED为永久已受理；CANCELLED为未受理原号永久作废。 */
		@Schema(description = "ABSENT仅表示当前未查到；ACCEPTED为永久已受理；CANCELLED为未受理原号永久作废")
		private String status;

		/** 稳定原命令号。 */
		@Schema(description = "稳定原命令号")
		private String requestId;

		/** 原命令的生产工单编码；ABSENT时为空。 */
		@Schema(description = "原命令的生产工单编码；ABSENT时为空")
		private String taskCode;

		/** 原受理的逐行真实派工回执；取消或未查到时为空列表。 */
		@Schema(description = "原受理的逐行真实派工回执；取消或未查到时为空列表")
		private List<LineResult> lines;

		/** 永久原件中的完整规范命令；ABSENT时为空。 */
		@Schema(description = "永久原件中的完整规范命令；ABSENT时为空")
		private MesDispatchAllocationDTO.Command commandSnapshot;

		/** 永久原件实际租户主键，字符串传输；ABSENT为当前查证租户。 */
		@Schema(description = "永久原件实际租户主键，字符串传输")
		private String tenantId;

		/** 永久原件实际工厂；ABSENT为当前查证工厂。 */
		@Schema(description = "永久原件实际工厂")
		private String factoryCode;

		/** 永久原件实名操作人主键，字符串传输；ABSENT为当前查证人。 */
		@Schema(description = "永久原件实名操作人主键，字符串传输")
		private String actorId;

		/** 原操作人登录账号或工号，来源为已认证MomUser.username。 */
		@Schema(description = "原操作人登录账号或工号，来源为已认证MomUser.username")
		private String actorCode;

		/** 原号永久作废原因；ACCEPTED或ABSENT为空字符串。 */
		@Schema(description = "原号永久作废原因；ACCEPTED或ABSENT为空字符串")
		private String cancelReason;
	}
}
