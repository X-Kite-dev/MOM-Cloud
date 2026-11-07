package com.kitex.mom.qms.dto;

import java.util.List;

/**
 * 逐件原首轮资格和实测结果合同；未知数量保持null，不据批次放行授予资格。
 */
public final class QmsInspectionPieceFirstPassDTO {
	private QmsInspectionPieceFirstPassDTO() {
	}

	/**
	 * 当前不可变协议版本；注释和格式整理不改变原序列化值。
	 */
	public static final String SCHEMA = "QMS_INSPECTION_PIECE_FIRST_PASS@1";

	/**
	 * 首轮资格的冻结来源与服务端时间证据。
	 *
	 * @param schemaVersion             不可变协议版本；用于解释原件而非重新计算历史。
	 * @param availability              归属可用状态：ASSIGNED已归属、OTHER_INSPECTION其他原件、NOT_MANUFACTURING_ID未登记制造身份、SCHEMA_UNAVAILABLE结构未安装。
	 * @param fingerprint               当前原件规范序列化的SHA-256指纹；可空性由可用状态决定。
	 * @param manufacturingFingerprint  MES不可变制造出生原件SHA-256。
	 * @param identityBasis             身份依据；OPERATOR_DECLARED_NEW_PIECE表示操作者声明，非机器鉴真。
	 * @param qmsRequestId              原QMS样本采集请求号，永久绑定首轮归属。
	 * @param qmsRequestFingerprint     原QMS样本采集命令SHA-256，防止同号替换内容。
	 * @param qmsActorId                原QMS样本采集的实际操作者ID字符串。
	 * @param sourceRequestId           原MES检验指令请求号。
	 * @param engineeringHash           已下达原工程内容SHA-256，不读取当前主数据重算。
	 * @param manufacturingRegisteredAt 制造出生服务端UTC纪元毫秒字符串。
	 * @param sampleCapturedAt          QMS样本本次入库的服务端UTC纪元毫秒字符串，非物理测量时刻。
	 */
	public record Evidence(
			String schemaVersion,
			String availability,
			String fingerprint,
			String manufacturingFingerprint,
			String identityBasis,
			String qmsRequestId,
			String qmsRequestFingerprint,
			String qmsActorId,
			String sourceRequestId,
			String engineeringHash,
			String manufacturingRegisteredAt,
			String sampleCapturedAt) {
	}

	/**
	 * 已授权范围的查询结果，未知与不可用来源保留原意。
	 *
	 * @param schemaVersion              不可变协议版本；用于解释原件而非重新计算历史。
	 * @param tenantId                   经认证的实际租户ID，按十进制字符串传输。
	 * @param factoryCode                当前已授权实际工厂编码，与原来源一致。
	 * @param taskId                     当前实际工厂QMS检验任务ID字符串。
	 * @param pieceId                    当前实际工厂逐件样本ID字符串。
	 * @param identifier                 服务端按冻结模板生成的规范单件码，不接管任意历史码。
	 * @param basisFingerprint           原FULL检验依据指纹，防止按当前标准重算。
	 * @param qualification              ELIGIBLE_FIRST_ROUND已核实原首轮、REINSPECTION复检、NOT_ORIGINAL_ASSIGNMENT其他归属、UNVERIFIED未知。
	 * @param firstPassEligible          是否同时核实新制造身份、原归属、冻结FULL样本及采集来源；未知为false。
	 * @param measuredQualified          该逐件FULL原样本是否全部合格；单独为true不授予首轮资格。
	 * @param eligibleQuantity           已核实首轮登记身份数，单件为1、明确排除为0、未知为null。
	 * @param firstPassQualifiedQuantity 已核实原首轮且FULL样本合格的登记身份数；未知为null。
	 * @param quantityUnit               原冻结BOM的产品数量单位；登记件数不据此换算。
	 * @param routeNodeCode              原冻结路线的稳定节点编码，不以主工序编码代替。
	 * @param dataGaps                   未核实来源或覆盖限制的稳定代码列表。
	 * @param reinspectionState          原复检链核实状态，可空；复检不得回写原首轮结果。
	 * @param originalInspection         采集时冻结的首轮归属及制造来源证据，可空。
	 */
	public record View(
			String schemaVersion,
			String tenantId,
			String factoryCode,
			String taskId,
			String pieceId,
			String identifier,
			String basisFingerprint,
			String qualification,
			boolean firstPassEligible,
			boolean measuredQualified,
			String eligibleQuantity,
			String firstPassQualifiedQuantity,
			String quantityUnit,
			String routeNodeCode,
			List<String> dataGaps,
			String reinspectionState,
			Evidence originalInspection) {
		public View {
			dataGaps = List.copyOf(dataGaps);
		}
	}
}
