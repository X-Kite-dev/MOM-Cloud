package com.kitex.mom.wms.dto;

import java.util.List;
import java.util.Map;

/**
 * 特殊收发跨端合同：标识和数量始终以文本传输，库存事实由服务端冻结。
 */
public final class WmsSpecialMovementDTO {
	private WmsSpecialMovementDTO() {
	}

	public enum Kind {SPARE, FREE, OUTSOURCE}

	public enum Direction {IN, OUT}

	public enum Action {CREATE, APPROVE, REJECT, CANCEL, EXECUTE, RECEIVE_OUTPUT, RETURN, CLOSE}

	public record Rule(Kind kind, Boolean enabled, Boolean requireReference, Boolean requireParty,
	                   Boolean allowReturns,
	                   Boolean quarantineReturns) {
		public Rule(Kind kind, Boolean enabled, Boolean reference, Boolean party, Boolean returns) {
			this(kind, enabled, reference, party, returns, true);
		}
	}

	public record PolicySnapshot(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                             String version, List<Rule> rules) {
	}

	public record PolicySave(String warehouseCode, String expectedVersion, List<Rule> rules) {
	}

	public record Line(String lineId, String materialCode, String materialName, String unit, String quantity) {
	}

	public record Plan(Kind kind, Direction direction, String partyCode, String reference, String reason,
	                   List<Line> lines, List<Line> outputs) {
	}

	public record ExecutionLine(String lineId, String stockId, String sourceFlowId, String quantity,
	                            String siteCode,
	                            String containerCode, String batch, String barcode, String producedAt,
	                            String expiresAt,
	                            Boolean originalConditionConfirmed) {
		public ExecutionLine(String lineId, String stockId, String sourceFlowId, String quantity,
		                     String siteCode,
		                     String containerCode, String batch, String barcode, String producedAt,
		                     String expiresAt) {
			this(lineId, stockId, sourceFlowId, quantity, siteCode, containerCode, batch, barcode, producedAt,
					expiresAt, null);
		}
	}

	/**
	 * 关闭时分别声明原发出流水的在外处置量和交付计划行的短交处置量，不互相换算。
	 */
	public record CloseLine(String sourceFlowId, String outputLineId, String quantity) {
	}

	public record Command(String requestId, String warehouseCode, Action action, String orderId,
	                      String expectedVersion,
	                      Plan plan, List<ExecutionLine> lines, String reason, String evidenceReference,
	                      List<CloseLine> closeLines) {
	}

	public record End(Command original, String reason) {
	}

	public record OrderDocument(String id, String tenantId, String factoryCode, String warehouseCode, Plan plan,
	                            Rule rule, String policyVersion, String state, String version, String createdBy,
	                            String reviewedBy, String createdAt) {
	}

	public record Flow(String id, String lineId, Direction direction, String quantity, String sourceFlowId,
	                   String stockId, String billDetailId, Map<String, String> identity) {
	}

	public record Receipt(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                      String operatorCode, String requestId, String status, Action action, Command original,
	                      String orderId, String orderVersion, String billId, String billNo, List<Flow> flows,
	                      String endReason) {
	}

	public record Balance(String lineId, String materialCode, String materialName, String unit, String planned,
	                      String executed, String returned, String net, String remaining, boolean output) {
	}

	/**
	 * 永久操作记录的只读审计投影，不修改原命令及已保存的结果JSON。
	 *
	 * @param requestId    与本申请某条实际办理凭据一一对应的原请求号
	 * @param registeredAt 原操作表created_at无时区时间文本，不表示实物交接或事务提交时刻
	 */
	public record OperationAudit(String requestId, String registeredAt) {
	}

	/**
	 * @param order      当前实际申请原件及冻结计划
	 * @param balances   按原计划基本单位核算的收发、退回和剩余数量
	 * @param operations 原申请全部已登记办理凭据，沿永久记录顺序返回
	 * @param audit      与办理凭据一一对应的登记时间，不用申请版本或首次入库时间代替
	 */
	public record View(OrderDocument order, List<Balance> balances, List<Receipt> operations,
	                   List<OperationAudit> audit) {
	}

	public record Context(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                      String operatorCode, boolean mobileEnabled, List<String> permissions,
	                      PolicySnapshot policy,
	                      boolean warehouseEnabled, String warehouseName) {
		public Context(int version, String tenant, String factory, String warehouse, String operator,
		               boolean mobile,
		               List<String> permissions, PolicySnapshot policy) {
			this(version, tenant, factory, warehouse, operator, mobile, permissions, policy, true, warehouse);
		}
	}

	public record MaterialOption(String code, String name, String unit) {
	}

	public record MaterialPage(List<MaterialOption> records, long total) {
	}

	public record LocationOption(String code, String name) {
	}

	public record StockCandidate(String stockId, String materialCode, String materialName, String unit,
	                             String quantity,
	                             String available, String siteCode, String containerCode, String batch,
	                             String barcode) {
	}

	public record StockPage(List<StockCandidate> records, String nextCursor) {
	}

	public record Page(List<View> records, int page, int size, long total) {
	}
}
