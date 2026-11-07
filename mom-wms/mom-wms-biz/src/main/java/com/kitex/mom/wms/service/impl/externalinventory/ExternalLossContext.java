package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.common.security.util.SecurityUtils;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * 仅真实OA1 POST同线程同顶层事务可开立的精确分配能力；调用端无法设置许可。
 */
public final class ExternalLossContext {
	private ExternalLossContext() {
	}

	record Line(WmsOutBillDetailEntity detail, Source source, ExternalLossProvenance.Physical physical,
	            BigDecimal quantity) {
	}

	private record Permit(ExternalLossStore store, ExternalLossCodec.Parsed parsed, String xid, long approval,
	                      WmsOutBillEntity head, Map<Long, Line> lines) {
	}

	private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();

	static <T> T post(ExternalLossStore store, ExternalLossCodec.Parsed parsed, long approval, WmsOutBillEntity bill,
	                  Map<Long, Line> lines, Supplier<T> work) {
		require(CURRENT.get() == null && "OA1".equals(parsed.domain()) && "POST".equals(parsed.action()) &&
						Long.toString(approval).equals(((ApprovalCommand) parsed.typed()).post().approvalId()),
				"受托过账范围无效");
		String xid = store.jdbc.queryForObject("select pg_current_xact_id()::text", String.class);
		CURRENT.set(new Permit(store, parsed, xid, approval, bill, Map.copyOf(lines)));
		try {
			checkBill(bill);
			return work.get();
		} finally {
			CURRENT.remove();
		}
	}

	/**
	 * 仅用来收紧当前过账的容器历史更新条件，不授予任何其他业务写权。
	 */
	public static boolean inPost() {
		return active();
	}

	private static boolean active() {
		return CURRENT.get() != null;
	}

	private static Permit permit() {
		var p = CURRENT.get();
		require(p != null && TransactionSynchronizationManager.isActualTransactionActive(),
				"外部盘亏必须在原受托事务办理");
		var s = p.parsed.scope();
		require(Objects.equals(factory(), s.factoryCode()) &&
				Objects.equals(TenantContextHolder.getTenantId().toString(), s.tenantId()) &&
				SecurityUtils.getUser() != null &&
				Objects.equals(s.actorId(), SecurityUtils.getUser().getId().toString()), "受托过账身份已变化");
		var row = p.store.one("""
				select pg_current_xact_id()::text as xid,current_setting('mom_wms.external_loss_request',true) as req,
				       current_setting('mom_wms.external_loss_action',true) as action,current_setting('mom_wms.external_loss_actor',true) as actor
				""");
		require(p.xid.equals(row.get("xid")) && p.parsed.envelope().requestId().equals(row.get("req")) &&
				"POST".equals(row.get("action")) && s.actorId().equals(row.get("actor")), "受托过账事务意向不符");
		return p;
	}

	/**
	 * 专用出库桥及两个真实单头阶段核对同一头、原计划明细和完整源行集合。
	 */
	public static void checkBill(WmsOutBillEntity head) {
		var p = permit();
		var s = p.parsed.scope();
		require(head == p.head && ("IC-" + s.planDetailId()).equals(head.getBillNo()) &&
				"other_inventory".equals(head.getBillType()) &&
				WmsInventoryAdjustmentContext.DOCUMENT.equals(head.getDocumentTable()) &&
				Long.valueOf(s.planDetailId()).equals(head.getDocumentId()) &&
				s.factoryCode().equals(head.getFactoryCode()) && s.warehouseCode().equals(head.getWarehouseCode()) &&
				head.getNoticeId() == null &&
				head.getDetails() != null && head.getDetails().size() == p.lines.size(), "外部盘亏单头或明细集合不符");
		var seen = new HashSet<Long>();
		for (var d : head.getDetails()) {
			var line = p.lines.get(d.getSourceStockId());
			require(line != null && d == line.detail && seen.add(d.getSourceStockId()) &&
					d.getReservationNo() == null &&
					d.getNoticeDetailId() == null && d.getMatNum() != null &&
					line.quantity.compareTo(BigDecimal.valueOf(d.getMatNum())) == 0, "指定盘亏明细被替换或数量不一致");
		}
	}

	/**
	 * 未打开专用能力时保留普通出库；已打开时阻断其他单头、FIFO、预留捷径。
	 */
	public static void guardHead(WmsOutBillEntity bill) {
		if (active()) {
			checkBill(bill);
		}
	}

	/**
	 * 实际扣库之前再次核行已经在同一单头下取得真实ID，阻止先进入预留/FIFO分支。
	 */
	public static void guardReduction(WmsOutBillDetailEntity detail) {
		if (active()) {
			var p = permit();
			checkBill(p.head);
			var line = p.lines.get(detail.getSourceStockId());
			require(line != null && line.detail == detail && detail.getId() != null && p.head.getId() != null &&
					p.head.getId().equals(detail.getBillId()), "扣库只能使用受托单头实际落地行");
		}
	}

	/**
	 * 纯普通业务不获许可；两阶段完整核受托行、原实物、当前余量、物性和原始来源。
	 */
	public static boolean exact(String billType, WmsOutBillDetailEntity detail, WmsLiveStockEntity stock) {
		if (!"other_inventory".equals(billType) || !active()) {
			return false;
		}
		var p = permit();
		checkBill(p.head);
		var line = p.lines.get(stock.getId());
		require(line != null && line.detail == detail &&
						p.parsed.scope().tenantId().equals(stock.getTenantId().toString()) &&
						p.parsed.scope().factoryCode().equals(stock.getFactoryCode()) &&
						line.physical.equals(ExternalLossProvenance.physical(stock)) &&
						line.source.inBillId().equals(Objects.toString(stock.getSourceInBillId(), null)) &&
						line.source.inBillDetailId().equals(Objects.toString(stock.getSourceInBillDetailId(), null)) &&
						new BigDecimal(line.source.currentQuantity()).compareTo(BigDecimal.valueOf(stock.getMatNum())) == 0 &&
						Objects.equals(stock.getSourceBusinessNo(), detail.getSourceBusinessNo()) &&
						Objects.equals(stock.getStockOwnerType(), detail.getStockOwnerType()) &&
						Objects.equals(stock.getStockOwnerCode(), detail.getStockOwnerCode()) &&
						Objects.equals(stock.getMatProduceDate(), detail.getMatProduceDate()) &&
						Objects.equals(stock.getMatValidEndDate(), detail.getMatValidEndDate()) &&
						Objects.equals(stock.getMatValidPeriod(), detail.getMatValidPeriod()) &&
						Objects.equals(stock.getSupplierName(), detail.getSupplierName()),
				"受托精确行与冻结实物或原始实收来源不一致");
		com.kitex.mom.wms.util.WmsOutboundStockIdentity.requireMatches(detail, stock, stock.getTenantId(),
				stock.getFactoryCode());
		var balance = BigDecimal.valueOf(stock.getMatNum()).subtract(line.quantity);
		require(balance.signum() >= 0 && Double.isFinite(balance.doubleValue()) &&
						BigDecimal.valueOf(balance.doubleValue()).compareTo(balance) == 0,
				"扣减后余额无法无损落库");
		return true;
	}
}
