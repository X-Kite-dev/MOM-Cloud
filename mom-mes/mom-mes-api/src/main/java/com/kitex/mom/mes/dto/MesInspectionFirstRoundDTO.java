package com.kitex.mom.mes.dto;

/**
 * 同件与冻结稳定检验节点的永久原首轮归属；归属本身不等于实测结果。
 */
public final class MesInspectionFirstRoundDTO {
	private MesInspectionFirstRoundDTO() {
	}

	/**
	 * 当前不可变协议版本；注释和格式整理不改变原序列化值。
	 */
	public static final String SCHEMA = "MES_INSPECTION_FIRST_ROUND@1";

	/**
	 * 原QMS采集请求对首轮归属的申请。
	 *
	 * @param source                实际MES原检验来源查询，绑定原指令、工序任务和规范单件码。
	 * @param qmsTaskId             原QMS检验任务ID字符串。
	 * @param qmsRequestId          原QMS样本采集请求号，永久绑定首轮归属。
	 * @param qmsRequestFingerprint 原QMS样本采集命令SHA-256，防止同号替换内容。
	 * @param qmsActorId            原QMS样本采集的实际操作者ID字符串。
	 */
	public record Claim(
			MesInspectionPieceEntryDTO.Query source,
			String qmsTaskId,
			String qmsRequestId,
			String qmsRequestFingerprint,
			String qmsActorId) {
	}

	/**
	 * 同件与稳定检验节点的唯一永久原归属。
	 *
	 * @param schemaVersion      不可变协议版本；用于解释原件而非重新计算历史。
	 * @param claim              永久保留的原QMS首轮归属申请。
	 * @param manufacturingPiece 经过原文指纹与索引核对的MES制造出生回执。
	 * @param entry              实际入站和冻结工程来源，不以缺少历史推定首轮。
	 */
	public record Assignment(
			String schemaVersion,
			Claim claim,
			MesManufacturingPieceDTO.Receipt manufacturingPiece,
			MesInspectionPieceEntryDTO.View entry) {
	}

	/**
	 * 已授权范围的查询结果，未知与不可用来源保留原意。
	 *
	 * @param schemaVersion 不可变协议版本；用于解释原件而非重新计算历史。
	 * @param availability  归属可用状态：ASSIGNED已归属、OTHER_INSPECTION其他原件、NOT_MANUFACTURING_ID未登记制造身份、SCHEMA_UNAVAILABLE结构未安装。
	 * @param assignment    永久首轮原归属；仅ASSIGNED时存在，不等于实测合格。
	 * @param fingerprint   当前原件规范序列化的SHA-256指纹；可空性由可用状态决定。
	 */
	public record View(
			String schemaVersion, String availability, Assignment assignment, String fingerprint) {
	}
}
