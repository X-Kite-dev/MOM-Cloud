package com.kitex.mom.mes.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import com.kitex.mom.mes.vo.MesDispatchAllocationVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 过程组合只描述职责与实际单据绑定，不新增生产实绩。
 */
public final class MesProcessCompositionDTO {

	private MesProcessCompositionDTO() {
	}

	@Data
	public static class Definition {
		private List<Level> levels;
	}

	@Data
	public static class Level {
		private String key;
		private String label;
		/**
		 * ORDER归属、TASK工单、OPERATION工艺、ASSIGNMENT资源派工。
		 */
		private List<String> bindings;
	}

	@Data
	public static class Draft {
		private String id;
		private Long revisionNo;
		private String code;
		private String name;
		private Definition definition;
	}

	@Data
	public static class Template {
		private String id;
		private String code;
		private String name;
		private Integer versionNo;
		private Long revisionNo;
		private String status;
		private Definition definition;
	}

	@Data
	public static class Run {
		private String requestId;
		private String templateId;
		private Long expectedRevisionNo;
		private List<MesTaskArrangeDTO> dispatches;
	}

	/** 原组合模板下的一对多配额运行命令；旧Run原字段和指纹保持。 */
	@Data
	public static class RunAllocated {
		/** 稳定外部运行原号，所有工单子命令与本运行同事务。 */
		@Schema(description = "稳定外部运行原号")
		private String requestId;
		/** 实际工厂的精确已发布模板ID文本。 */
		@Schema(description = "精确已发布模板ID文本")
		private String templateId;
		/** 本次读取的已发布模板修订号，不以今日默认版替换。 */
		@Schema(description = "已发布模板修订号")
		private Long expectedRevisionNo;
		/** 1至20个不同原工单及各自完整分配行。 */
		@Schema(description = "1至20个原工单及完整分配")
		private List<AllocationDispatch> allocationDispatches;
	}

	/** 一个原工单的配额运行输入，子原号由服务端从外原号和工单确定。 */
	@Data
	public static class AllocationDispatch {
		/** 真实原工单编码。 */
		@Schema(description = "真实原工单编码")
		private String taskCode;
		/** 每行对应一个真实派工，保留稳定allocationKey。 */
		@Schema(description = "真实派工分配行")
		private List<MesDispatchAllocationDTO.Line> lines;
	}

	@Data
	public static class Node {
		private String id;
		private String parentId;
		private String levelKey;
		private String label;
		private List<String> bindings;
		private String sourceCode;
		private String taskId;
		private String produceTaskId;
		private String arrangeId;
		private String routeNodeCode;
		/** 配额协议节点对应的原分配行；旧运行为空。 */
		@Schema(description = "原稳定分配行标识，旧运行为空")
		private String allocationKey;
		/** 本派工原配额十进制文本，避免与父工序需求重复累计；旧运行为空。 */
		@Schema(description = "本派工原配额十进制文本")
		private String allocatedQuantity;
	}

	@Data
	public static class Progress {
		private String taskCode;
		private String materialCode;
		private BigDecimal plannedQuantity;
		private BigDecimal completedQuantity;
		private String status;
	}

	@Data
	public static class View {
		private String id;
		private String requestId;
		private String templateId;
		private Integer templateVersion;
		private String templateName;
		private Definition definition;
		private List<Node> nodes;
		private List<Progress> progress;
		private String quantityBasis;
		/** V2原规范完整命令，须与永久父指纹和全部子命令一致；旧运行为空。 */
		@Schema(description = "V2原规范完整命令JSON，旧运行为空")
		private String allocatedCommandSnapshot;
		/** 仅原人员核对V2时交付各原工单永久子回执；不由今日输入重建。 */
		@Schema(description = "V2原工单永久配额子回执")
		private List<MesDispatchAllocationVO.Receipt> allocationReceipts;
	}

	@Data
	public static class Receipt {
		/**
		 * ABSENT不证明请求从未到达；APPLIED为原运行，CANCELLED为持久作废凭证。
		 */
		private String status;
		private View run;
	}
}
