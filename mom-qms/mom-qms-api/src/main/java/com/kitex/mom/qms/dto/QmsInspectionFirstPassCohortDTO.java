package com.kitex.mom.qms.dto;

import java.util.List;

/**
 * 服务端权威登记总体的原首轮核实统计及证据下钻，明确覆盖范围和两侧观察时间。
 */
public final class QmsInspectionFirstPassCohortDTO {
	private QmsInspectionFirstPassCohortDTO() {
	}

	/**
	 * 当前不可变协议版本；注释和格式整理不改变原序列化值。
	 */
	public static final String SCHEMA = "QMS_INSPECTION_FIRST_PASS_COHORT@1";

	/**
	 * 经过MES核对的原工单、检验节点及UTC周期。
	 *
	 * @param orderCode          实际工厂精确原制造工单编码，不做模糊匹配。
	 * @param inspectionNodeCode 本次明确选择的原冻结检验稳定节点，与出生初始节点分别保留。
	 * @param registeredFrom     登记总体起点，UTC ISO-8601毫秒精度，包含该时刻。
	 * @param registeredTo       登记总体终点，UTC ISO-8601毫秒精度，不包含该时刻。
	 */
	public record Scope(
			String orderCode,
			String inspectionNodeCode,
			String registeredFrom,
			String registeredTo) {
	}

	/**
	 * 当前合同范围内的稳定路线节点信息。
	 *
	 * @param routeNodeCode 原冻结路线的稳定节点编码，不以主工序编码代替。
	 * @param processCode   稳定节点对应的主工序编码。
	 * @param processName   主工序名称快照，可空。
	 * @param initial       该原冻结节点是否无前序；仅说明出生初始节点资格。
	 */
	public record Node(
			String routeNodeCode, String processCode, String processName, boolean initial) {
	}

	/**
	 * 已下达原工程和实际组织范围。
	 *
	 * @param schemaVersion        不可变协议版本；用于解释原件而非重新计算历史。
	 * @param tenantId             经认证的实际租户ID，按十进制字符串传输。
	 * @param factoryCode          当前已授权实际工厂编码，与原来源一致。
	 * @param orderId              实际工厂原制造工单ID字符串。
	 * @param orderCode            实际工厂精确原制造工单编码，不做模糊匹配。
	 * @param productCode          原工程或当前路线配置的产品编码。
	 * @param productName          产品名称，可空。
	 * @param engineeringReleaseId 已下达原工程发布ID字符串。
	 * @param engineeringHash      已下达原工程内容SHA-256，不读取当前主数据重算。
	 * @param quantityUnit         原冻结BOM的产品数量单位；登记件数不据此换算。
	 * @param nodes                原已下达工程完整稳定节点列表，不读取今日路线代替。
	 */
	public record Context(
			String schemaVersion,
			String tenantId,
			String factoryCode,
			String orderId,
			String orderCode,
			String productCode,
			String productName,
			String engineeringReleaseId,
			String engineeringHash,
			String quantityUnit,
			List<Node> nodes) {
		public Context {
			nodes = List.copyOf(nodes);
		}
	}

	/**
	 * 不可变制造登记身份的统计成员。
	 *
	 * @param identifier               服务端按冻结模板生成的规范单件码，不接管任意历史码。
	 * @param manufacturingFingerprint MES不可变制造出生原件SHA-256。
	 * @param registeredAt             制造出生事务服务端UTC纪元毫秒字符串，非操作者回填时间。
	 * @param birthInitialNodeCode     制造出生的原冻结初始节点编码，非本次检验节点。
	 * @param manufacturingRequestId   原制造出生永久请求号。
	 * @param manufacturingArrangeId   原制造出生派工ID字符串。
	 */
	public record Member(
			String identifier,
			String manufacturingFingerprint,
			String registeredAt,
			String birthInitialNodeCode,
			String manufacturingRequestId,
			String manufacturingArrangeId) {
	}

	/**
	 * 完整且有上限的服务端登记总体，不允许客户端挑选成员。
	 *
	 * @param schemaVersion 不可变协议版本；用于解释原件而非重新计算历史。
	 * @param context       冻结的编码上下文或工单工程范围，按所属记录解释。
	 * @param query         服务端实际执行的完整精确总体条件。
	 * @param members       服务端完整登记身份集合，超过1000拒绝，不截断。
	 */
	public record Collection(
			String schemaVersion, Context context, Scope query, List<Member> members) {
		public Collection {
			members = List.copyOf(members);
		}
	}

	/**
	 * MES权威总体及其指纹和观察时刻。
	 *
	 * @param collection     已核实范围及原件集合，指纹覆盖全部成员和查询条件。
	 * @param collectionHash 完整MES总体规范序列化SHA-256；下钻时必须一致。
	 * @param observedAt     MES服务端完成本次读取的UTC ISO-8601时间，非跨服务快照。
	 */
	public record Manufacturing(Collection collection, String collectionHash, String observedAt) {
	}

	/**
	 * 关联原样本的核实摘要。
	 *
	 * @param taskId        当前实际工厂QMS检验任务ID字符串。
	 * @param pieceId       当前实际工厂逐件样本ID字符串。
	 * @param qualification ELIGIBLE_FIRST_ROUND已核实原首轮、REINSPECTION复检、NOT_ORIGINAL_ASSIGNMENT其他归属、UNVERIFIED未知。
	 * @param routeNodeCode 原冻结路线的稳定节点编码，不以主工序编码代替。
	 * @param issue         该条原样本来源未核实的原因代码，可空。
	 */
	public record Inspection(
			String taskId,
			String pieceId,
			String qualification,
			String routeNodeCode,
			String issue) {
	}

	/**
	 * 单个登记身份的互斥统计分类及来源限制。
	 *
	 * @param manufacturing 服务端取得的MES登记总体或对应成员，按所属记录解释。
	 * @param state         当前核实状态；按所属返回合同解释，未知不视为成功。
	 * @param inspections   该登记身份关联的QMS原样本核实记录；不得由客户端挑选统计分母。
	 * @param dataGaps      未核实来源或覆盖限制的稳定代码列表。
	 */
	public record Item(
			Member manufacturing,
			String state,
			List<Inspection> inspections,
			List<String> dataGaps) {
		public Item {
			inspections = List.copyOf(inspections);
			dataGaps = List.copyOf(dataGaps);
		}
	}

	/**
	 * 登记身份数量与已核实原首轮样本指标，不代表全厂合格率。
	 *
	 * @param registeredCount         完整登记身份数的十进制文本，不换算产品单位。
	 * @param eligibleComplete        已核实原首轮完成身份数，等于passed加failed。
	 * @param passed                  已核实原首轮FULL样本全部合格的身份数。
	 * @param failed                  已核实原首轮FULL样本含不合格项的身份数，复检合格不覆盖。
	 * @param pending                 该检验节点尚无可计原首轮样本且没有未知来源的身份数。
	 * @param unknown                 原身份或样本资格尚未核实的身份数，不归零或计合格。
	 * @param excludedReinspection    仅核实到该节点复检、未有可计原首轮的身份数，与其他分类互斥。
	 * @param excludedSampleCount     该节点已核实复检记录数，可与有原首轮的同一身份并存。
	 * @param coverage                已核实原首轮完成数除登记数，十进制文本；登记数0时为null。
	 * @param verifiedFirstRoundYield 合格原首轮数除已核实原首轮完成数，十进制文本；分母0为null。
	 */
	@com.fasterxml.jackson.annotation.JsonInclude(
			com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
	public record Counts(
			String registeredCount,
			String eligibleComplete,
			String passed,
			String failed,
			String pending,
			String unknown,
			String excludedReinspection,
			String excludedSampleCount,
			String coverage,
			String verifiedFirstRoundYield) {
	}

	/**
	 * 已授权范围的查询结果，未知与不可用来源保留原意。
	 *
	 * @param schemaVersion              不可变协议版本；用于解释原件而非重新计算历史。
	 * @param manufacturing              服务端取得的MES登记总体或对应成员，按所属记录解释。
	 * @param qmsObservedAt              QMS服务端本次读取完成的UTC ISO-8601时间。
	 * @param crossServiceAtomicSnapshot 固定false；MES与QMS各自读取不能声明为同一原子快照。
	 * @param counts                     本次完整登记总体的守恒分类及有限覆盖指标。
	 * @param items                      完整登记身份逐项分类，页面筛选不重算指标。
	 * @param dataGaps                   未核实来源或覆盖限制的稳定代码列表。
	 */
	@com.fasterxml.jackson.annotation.JsonInclude(
			com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
	public record View(
			String schemaVersion,
			Manufacturing manufacturing,
			String qmsObservedAt,
			boolean crossServiceAtomicSnapshot,
			Counts counts,
			List<Item> items,
			List<String> dataGaps) {
		public View {
			items = List.copyOf(items);
			dataGaps = List.copyOf(dataGaps);
		}
	}

	/**
	 * 单条原逐件样本的完整证据下钻。
	 *
	 * @param taskId    当前实际工厂QMS检验任务ID字符串。
	 * @param pieceId   当前实际工厂逐件样本ID字符串。
	 * @param firstPass 该原样本的实际首轮资格及冻结证据，无法核实为null。
	 * @param samples   该样本完整FULL原检验值和判定，不取当前批次放行代替。
	 * @param issue     该条原样本来源未核实的原因代码，可空。
	 */
	public record PieceProof(
			String taskId,
			String pieceId,
			QmsInspectionPieceFirstPassDTO.View firstPass,
			QmsInspectionPieceDTO.Piece samples,
			String issue) {
	}

	/**
	 * 在重新核对完整MES集合后返回的单件证据。
	 *
	 * @param schemaVersion      不可变协议版本；用于解释原件而非重新计算历史。
	 * @param collectionHash     完整MES总体规范序列化SHA-256；下钻时必须一致。
	 * @param manufacturing      服务端取得的MES登记总体或对应成员，按所属记录解释。
	 * @param inspectionNodeCode 本次明确选择的原冻结检验稳定节点，与出生初始节点分别保留。
	 * @param mesObservedAt      MES本次重新读取完整总体的UTC时间，与QMS观察时间分列。
	 * @param qmsObservedAt      QMS服务端本次读取完成的UTC ISO-8601时间。
	 * @param inspections        该登记身份关联的QMS原样本核实记录；不得由客户端挑选统计分母。
	 * @param dataGaps           未核实来源或覆盖限制的稳定代码列表。
	 */
	@com.fasterxml.jackson.annotation.JsonInclude(
			com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
	public record Detail(
			String schemaVersion,
			String collectionHash,
			Member manufacturing,
			String inspectionNodeCode,
			String mesObservedAt,
			String qmsObservedAt,
			List<PieceProof> inspections,
			List<String> dataGaps) {
		public Detail {
			inspections = List.copyOf(inspections);
			dataGaps = List.copyOf(dataGaps);
		}
	}
}
