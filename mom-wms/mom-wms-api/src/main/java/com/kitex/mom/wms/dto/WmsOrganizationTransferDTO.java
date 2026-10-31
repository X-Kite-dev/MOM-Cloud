package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.organization.WmsOrganizationTransferBill;
import com.kitex.mom.wms.entity.organization.WmsOrganizationTransferPolicy;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存组织协同合同；公司/工厂/货权均由已确认策略和单据决定，不接受动作参数改写。
 */
public final class WmsOrganizationTransferDTO {
	private WmsOrganizationTransferDTO() {
	}

	public enum Action {
		CREATE, CONFIRM, SHIP, RECEIVE, CANCEL, REQUEST_RETURN, RETURN,
		PROPOSE_EXCEPTION, APPROVE_EXCEPTION, REJECT_EXCEPTION
	}

	@Data
	public static class PolicyCommand {
		private Long tenantId;
		private Long operatorId;
		private String factoryCode;
		private String warehouseCode;
		private String companyCode;
		private String companyName;
		private Boolean enabled;
		private Boolean crossCompany;
		private Integer expectedVersion;
	}

	@Data
	public static class Command {
		private Long tenantId;
		private Long operatorId;
		private String factoryCode;
		private String requestId;
		private Action action;
		private Long billId;
		private Integer expectedVersion;
		private String warehouseCode;
		private Long targetPolicyId;
		private String agreementRef;
		private String reason;
		private String evidence;
		private List<InputLine> lines;
	}

	@Data
	public static class InputLine {
		private Long lineId;
		private Long stockId;
		private BigDecimal quantity;
		private String siteCode;
		private String containerCode;
		private String barcode;
	}

	public record Context(int contractVersion, Long tenantId, String factoryCode, String operatorCode,
	                      String warehouseCode,
	                      List<Action> permissions, boolean canConfigure, Long operatorId) {
	}

	public record LineView(Long id, Long sourceStockId, String materialCode, String materialName, String batch,
	                       String unit, String barcode, boolean serialManaged, String qualityStatus,
	                       String qualityResult,
	                       BigDecimal quantity, BigDecimal received, BigDecimal returned, BigDecimal exception,
	                       BigDecimal remaining) {
	}

	public record Effect(Long lineId, String factoryCode, String warehouseCode, String action, Long stockId,
	                     Long flowId, BigDecimal quantity, LocalDateTime time) {
	}

	public record History(String requestId, Action action, String factoryCode, String operatorCode,
	                      String state, String reason, String evidence, LocalDateTime time) {
	}

	public record Detail(WmsOrganizationTransferBill bill, List<LineView> lines, List<Effect> effects,
	                     List<History> history, List<Action> actions, boolean effectsTruncated,
	                     boolean historyTruncated) {
	}

	public record StockPage(java.util.List<com.kitex.mom.wms.entity.WmsLiveStockEntity> records, Long nextCursor) {
	}

	public record Page(List<WmsOrganizationTransferBill> records, Long total) {
	}

	public record Receipt(int contractVersion, Long tenantId, String factoryCode, String operatorCode,
	                      String warehouseCode,
	                      String requestId, Action action, String status, Long billId, String billNo, String state,
	                      Integer version,
	                      String requestPayload, List<Effect> effects, LocalDateTime completedTime, String reason,
	                      Long operatorId) {
	}

	public record Operation(Long tenantId, String requestId, String factoryCode, String warehouseCode,
	                        String operatorCode, Action action, Long billId, String commandPayload,
	                        String resultPayload, String status,
	                        LocalDateTime completedTime, Long operatorId) {
	}
}
