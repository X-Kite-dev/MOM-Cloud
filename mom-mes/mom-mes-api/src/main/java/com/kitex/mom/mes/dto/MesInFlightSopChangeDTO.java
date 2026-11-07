package com.kitex.mom.mes.dto;

import lombok.Data;

/** 未执行稳定节点的正式SOP变更，客户端只指定版本身份和依据。 */
public final class MesInFlightSopChangeDTO {
	private MesInFlightSopChangeDTO() {
	}

	/** 首次接收前的单节点SOP申请；客户端仅指定身份与依据。 */
	@Data
	public static class Proposal {
		/** 稳定原申请号；8至100位字母、数字、下划线、点、冒号或连字符，重试不换号。 */
		private String requestId;
		/** 当前租户工厂的实际工序记录ID文本，须能唯一定位未接收稳定节点。 */
		private String processId;
		/** 目标正式生产工程发布ID文本，由服务端实名精确取包。 */
		private String releaseId;
		/** 目标工程原始UTF-8正文SHA256；与releaseId共同组成版本对。 */
		private String contentHash;
		/** 申请依据，1至1000字；未知结果时保留原文用于核原号。 */
		private String reason;
	}

	/** 异人复核命令；批准前重新检查原节点、来源和范围。 */
	@Data
	public static class Review {
		/** 待审原执行范围SHA256；范围变化时拒绝批准。 */
		private String expectedFingerprint;
		/** 异人复核依据，1至1000字；终态重放须与原文一致。 */
		private String reason;
		/** 明确复核决定：APPROVE批准并生效，REJECT驳回。 */
		private String decision;
	}
}
