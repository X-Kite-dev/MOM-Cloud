package com.kitex.mom.mes.dto;

/**
 * 新制造实物声明、服务器编码及不可变出生回执合同；身份依据为操作者声明。
 */
public final class MesManufacturingPieceDTO {
	private MesManufacturingPieceDTO() {
	}

	/**
	 * 当前不可变协议版本；注释和格式整理不改变原序列化值。
	 */
	public static final String SCHEMA = "MES_MANUFACTURING_PIECE@1";

	/**
	 * 新实物的操作者声明命令。
	 *
	 * @param requestId                永久幂等请求号；同号异事实拒绝。
	 * @param arrangeId                原派工ID字符串，仅接受实际开工的原单件派工。
	 * @param newPhysicalPieceDeclared 操作者明确声明这是新制造实物；必须为true，不接受旧码追认。
	 */
	public record Command(String requestId, String arrangeId, boolean newPhysicalPieceDeclared) {
	}

	/**
	 * 服务端编码生成的可重放冻结依据。
	 *
	 * @param template    实际产品与工厂的单件编码模板原文快照。
	 * @param context     冻结的编码上下文或工单工程范围，按所属记录解释。
	 * @param sequence    服务端保留的唯一编码序号，十进制字符串。
	 * @param generatedAt 编码模板渲染时的服务端本地日期时间快照，不作为物理生产时间。
	 */
	public record Generation(
			String template,
			com.kitex.mom.common.identifier.IdentifierCodeTemplate.Context context,
			String sequence,
			String generatedAt) {
	}

	/**
	 * 同事务实际执行的原START关联。
	 *
	 * @param sessionId  实际START生成的原计时会话ID字符串。
	 * @param requestId  永久幂等请求号；同号异事实拒绝。
	 * @param capturedAt 原START回执中的服务端采集时间，沿计时协议解释。
	 */
	public record Start(String sessionId, String requestId, String capturedAt) {
	}

	/**
	 * 不可变制造出生原件；仅证明登记身份与责任声明，不证明机器鉴真。
	 *
	 * @param schemaVersion        不可变协议版本；用于解释原件而非重新计算历史。
	 * @param tenantId             经认证的实际租户ID，按十进制字符串传输。
	 * @param factoryCode          当前已授权实际工厂编码，与原来源一致。
	 * @param actorId              实际登记操作者ID，按字符串保存，不代表设备认证。
	 * @param command              实际提交并永久保留的制造声明命令。
	 * @param identifier           服务端按冻结模板生成的规范单件码，不接管任意历史码。
	 * @param orderId              实际工厂原制造工单ID字符串。
	 * @param orderCode            实际工厂精确原制造工单编码，不做模糊匹配。
	 * @param operationCode        实际原工序任务编码，区分重复主工序。
	 * @param routeNodeCode        原冻结路线的稳定节点编码，不以主工序编码代替。
	 * @param engineeringReleaseId 已下达原工程发布ID字符串。
	 * @param engineeringHash      已下达原工程内容SHA-256，不读取当前主数据重算。
	 * @param quantityUnit         原冻结BOM的产品数量单位；登记件数不据此换算。
	 * @param identityBasis        身份依据；OPERATOR_DECLARED_NEW_PIECE表示操作者声明，非机器鉴真。
	 * @param generation           可重放的原模板、上下文、序号与渲染时间。
	 * @param registeredAt         制造出生事务服务端UTC纪元毫秒字符串，非操作者回填时间。
	 * @param start                同事务执行的实际原START回执关联。
	 */
	public record Birth(
			String schemaVersion,
			String tenantId,
			String factoryCode,
			String actorId,
			Command command,
			String identifier,
			String orderId,
			String orderCode,
			String operationCode,
			String routeNodeCode,
			String engineeringReleaseId,
			String engineeringHash,
			String quantityUnit,
			String identityBasis,
			Generation generation,
			String registeredAt,
			Start start) {
	}

	/**
	 * 原请求回读结果，区别明确不存在与已执行。
	 *
	 * @param state   当前核实状态；按所属返回合同解释，未知不视为成功。
	 * @param receipt 原制造出生回执；未找到时可空，查询失败不能代替未找到。
	 */
	public record Resolution(String state, Receipt receipt) {
	}

	/**
	 * 制造出生原件及其规范指纹。
	 *
	 * @param birth       不可变新制造出生原件。
	 * @param fingerprint 当前原件规范序列化的SHA-256指纹；可空性由可用状态决定。
	 */
	public record Receipt(Birth birth, String fingerprint) {
	}
}
