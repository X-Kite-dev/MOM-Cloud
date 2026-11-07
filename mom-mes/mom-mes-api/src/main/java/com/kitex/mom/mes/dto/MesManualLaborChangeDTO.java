package com.kitex.mom.mes.dto;

/**
 * 独立手工工单标准工时变更命令；主键使用十进制文本，不接收客户端工程正文。
 */
public final class MesManualLaborChangeDTO {
	private MesManualLaborChangeDTO() {
	}

	/**
	 * 首次接收前申请；相同原号、申请人和完整正文只能得到同一永久回执。
	 */
	public record Proposal(
			/** 当前组织内永久申请号，8至100个字母、数字或_.:-字符。 */String requestId,
			/** 原实际工序任务正整数ID文本，不能用主工序编码替代稳定节点。 */String processId,
			/** MDM正式目标工程发布ID文本。 */String releaseId,
			/** 目标工程完整UTF-8原件SHA256，64位小写十六进制。 */String contentHash,
			/** 1至1000字业务变更依据。 */String reason) {
	}

	/**
	 * 异人批准或驳回；批准必须仍为申请时完整未执行范围。
	 */
	public record Review(
			/** APPROVE批准、REJECT驳回；终态不允许改变。 */String decision,
			/** 原申请来源范围SHA256，防止复核另一份范围。 */String expectedFingerprint,
			/** 1至1000字异人复核依据。 */String reason) {
	}

	/**
	 * 原工程与本节点拟采用来源的精确标准差异；数值文本不经过浏览器浮点。
	 */
	public record Comparison(
			/** 同一原工单工程联合包SHA256。 */String originalEngineeringHash,
			/** 标准来源目标完整工程联合包SHA256。 */String targetEngineeringHash,
			/** 目标稳定节点RN-id。 */String routeNodeCode,
			/** 原主工序编码。 */String processCode,
			/** 原批准工程的标准原值、单位与基本批量。 */Standard original,
			/** 拟采用的本节点标准，不代表采用目标整包。 */Standard target) {
	}

	/**
	 * 小型标准对照卡的数据合同；缺证保留UNKNOWN，不填默认单位或基本批量。
	 */
	public record Standard(
			/** 原活动基本批量的精确十进制文本及原单位。 */MesEngineeringLaborStandardDTO.Basis basis,
			/** 准备、人工、机器、拆卸四类标准时间值与单位。 */
			java.util.List<MesEngineeringLaborStandardDTO.Measure> measures) {
	}

}
