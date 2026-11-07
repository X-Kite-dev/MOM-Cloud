package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 普通派工配额命令合同；只接受执行资源与配额，不接受可回写的派工实体。
 */
public final class MesDispatchAllocationDTO {

	private MesDispatchAllocationDTO() {
	}

	/** 完整稳定原命令。 */
	@Data
	public static class Command {
		/** 8至100位稳定原命令号；同组织、同操作人同号必须保持正文一致。 */
		@Schema(description = "8至100位稳定原命令号；同组织、同操作人同号必须保持正文一致")
		private String requestId;

		/** 当前实际租户工厂的生产工单编码。 */
		@Schema(description = "当前实际租户工厂的生产工单编码")
		private String taskCode;

		/** 本工单1至200条真实派工配额行；每行独立出生一条派工。 */
		@Schema(description = "本工单1至200条真实派工配额行；每行独立出生一条派工")
		private List<Line> lines;
	}

	/** 一条真实派工的资源及数量输入。 */
	@Data
	public static class Line {
		/** 命令内稳定且唯一的分配行标识，恢复时不得重建。 */
		@Schema(description = "命令内稳定且唯一的分配行标识，恢复时不得重建")
		private String allocationKey;

		/** 本工单冻结图中的真实工序任务编码，不以工序主数据编码代替。 */
		@Schema(description = "本工单冻结图中的真实工序任务编码，不以工序主数据编码代替")
		private String produceTaskCode;

		/** 实际派工班次编码；各行可采用不同班次。 */
		@Schema(description = "实际派工班次编码；各行可采用不同班次")
		private String shiftCode;

		/** 实际派工班次名称快照。 */
		@Schema(description = "实际派工班次名称快照")
		private String shiftName;

		/** 实际执行人员工号集合；APS已锁定人员时须与原计划一致。 */
		@Schema(description = "实际执行人员工号集合；APS已锁定人员时须与原计划一致")
		private String workerCode;

		/** 实际执行人员姓名快照。 */
		@Schema(description = "实际执行人员姓名快照")
		private String workerName;

		/** 实际授权工位编码集合，逗号分隔；APS已锁定工位时不可调整。 */
		@Schema(description = "实际授权工位编码集合，逗号分隔；APS已锁定工位时不可调整")
		private String worksiteCodes;

		/** 正配额，单位沿原工序物料单位，最多六位小数；不是实际产量。 */
		@Schema(description = "正配额，单位沿原工序物料单位，最多六位小数；不是实际产量")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal quantity;
	}

	/** 永久作废尚未受理的完整原命令；不能撤销已受理派工。 */
	@Data
	public static class Cancel {
		/** 待作废的完整稳定原命令。 */
		@Schema(description = "待作废的完整稳定原命令")
		private Command command;

		/** 1至500字作废原因，只在首次墓碑中冻结。 */
		@Schema(description = "1至500字作废原因")
		private String reason;
	}
}
