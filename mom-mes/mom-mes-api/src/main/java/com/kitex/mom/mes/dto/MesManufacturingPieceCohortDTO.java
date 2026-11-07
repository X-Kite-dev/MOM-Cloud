package com.kitex.mom.mes.dto;

import java.time.Instant;
import java.util.List;

/**
 * 明确工单与UTC登记周期内的完整不可变身份总体，不代表整厂产品总体。
 */
public final class MesManufacturingPieceCohortDTO {
	private MesManufacturingPieceCohortDTO() {
	}

	/**
	 * 当前不可变协议版本；注释和格式整理不改变原序列化值。
	 */
	public static final String SCHEMA = "MES_MANUFACTURING_PIECE_COHORT@1";

	/**
	 * 校验并规范化UTC毫秒半开周期；空范围、非稳定节点、回退周期或更细时间精度均拒绝。
	 *
	 * @param q 精确工单、检验节点与登记周期
	 * @return 不改变范围语义的规范UTC查询
	 */
	public static Query normalize(Query q) {
		if (q == null
				|| q.orderCode() == null
				|| q.orderCode().isBlank()
				|| q.orderCode().length() > 100
				|| !q.orderCode().equals(q.orderCode().strip())
				|| q.inspectionNodeCode() == null
				|| !q.inspectionNodeCode().matches("RN-[1-9][0-9]{0,18}")
				|| q.registeredFrom() == null
				|| q.registeredTo() == null
				|| !q.registeredFrom().endsWith("Z")
				|| !q.registeredTo().endsWith("Z")) {
			throw new IllegalArgumentException("请选择精确工单、检验稳定节点和明确UTC登记周期");
		}
		try {
			Instant from = Instant.parse(q.registeredFrom()), to = Instant.parse(q.registeredTo());
			if (from.getNano() % 1000000 != 0
					|| to.getNano() % 1000000 != 0
					|| from.toEpochMilli() < 0
					|| !from.isBefore(to)) {
				throw new IllegalArgumentException();
			}
			return new Query(q.orderCode(), q.inspectionNodeCode(), from.toString(), to.toString());
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("登记周期必须为UTC毫秒精度且开始早于结束", e);
		}
	}

	/**
	 * 精确原工单、检验稳定节点和服务端登记UTC半开周期。
	 *
	 * @param orderCode          实际工厂精确原制造工单编码，不做模糊匹配。
	 * @param inspectionNodeCode 本次明确选择的原冻结检验稳定节点，与出生初始节点分别保留。
	 * @param registeredFrom     登记总体起点，UTC ISO-8601毫秒精度，包含该时刻。
	 * @param registeredTo       登记总体终点，UTC ISO-8601毫秒精度，不包含该时刻。
	 */
	public record Query(
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
			String schemaVersion, Context context, Query query, List<Member> members) {
		public Collection {
			members = List.copyOf(members);
		}
	}

	/**
	 * 已授权范围的查询结果，未知与不可用来源保留原意。
	 *
	 * @param collection     已核实范围及原件集合，指纹覆盖全部成员和查询条件。
	 * @param collectionHash 完整MES总体规范序列化SHA-256；下钻时必须一致。
	 * @param observedAt     MES服务端完成本次读取的UTC ISO-8601时间，非跨服务快照。
	 */
	public record View(Collection collection, String collectionHash, String observedAt) {
	}
}
