package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.pms.api.feign.RemotePmsPurchaseInboundPostingService;
import com.kitex.mom.pms.dto.PmsPurchaseInboundPosting;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillSourceEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.mapper.WmsInBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsPurchaseInboundPostingMapper;
import com.kitex.mom.wms.mapper.WmsPurchaseInboundPostingMapper.Posting;
import com.kitex.mom.wms.service.bill.WmsInBillAccess;
import com.kitex.mom.wms.service.bill.WmsInBillAccess.Action;
import com.kitex.mom.wms.service.bill.WmsInBillAccess.Family;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 将已过账采购入库事实与库存同事务冻结，提交后查询并投递 PMS 永久回执。
 * 续投只处理原单台账，不再经过入库审核或库存写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsPurchaseInboundPostingService {

	private static final BigDecimal V1_QUANTITY_LIMIT = new BigDecimal("1000000000000000000");

	private final WmsPurchaseInboundPostingMapper postings;
	private final WmsInBillMapper bills;
	private final WmsInBillDetailMapper billDetails;
	private final WmsInNoticeMapper notices;
	private final WmsInNoticeDetailMapper noticeDetails;
	private final WmsInoutFlowMapper flows;
	private final WmsInBillAccess billAccess;
	private final RemotePmsPurchaseInboundPostingService pms;
	private final PlatformTransactionManager transactionManager;
	private final ObjectMapper json;

	@Value("${mom.wms.purchase-inbound-posting.enabled:false}")
	private boolean enabled;

	@Value("${mom.wms.purchase-inbound-posting.mobile-enabled:false}")
	private boolean mobileEnabled;

	/**
	 * 仅供首次保存采购单使用。明确不适用 V1 的来源走旧模式，无法核验时阻止新建。
	 *
	 * @param bill 尚未插入的采购入库单
	 * @return 是否冻结 V1
	 */
	public boolean shouldFreezeV1(WmsInBillEntity bill) {
		return shouldFreezeV1(bill, false);
	}

	/**
	 * 仅供已进入原号收货事务的 PDA 首扫使用。校验未来要收货的全部来源行，
	 * 避免中途才发现历史通知缺少精确批次，导致整张入库单无法完成。
	 *
	 * @param bill 尚未插入且已由服务端标记为移动收货的采购入库单
	 * @return 是否冻结 V1
	 */
	public boolean shouldFreezeMobileV1(WmsInBillEntity bill) {
		return mobileEnabled && shouldFreezeV1(bill, true);
	}

	private boolean shouldFreezeV1(WmsInBillEntity bill, boolean mobile) {
		if (!enabled || bill.getNoticeId() == null) {
			return false;
		}
		Scope scope = scope();
		WmsInNoticeEntity notice = notice(scope, bill.getNoticeId(), bill.getWarehouseCode());
		if (!DocumentEnum.PURCHASE_RECEIPT.getCode().equals(notice.getDocumentTable())) {
			return false;
		}
		require(notice.getDocumentId() != null && notice.getDocumentId() > 0, "采购入库通知缺少原收料单");
		var sources = sourceDetails(scope, notice.getId());
		require(bill.getDetails() != null && !bill.getDetails().isEmpty(), "采购入库明细不能为空");
		for (WmsInBillDetailEntity detail : bill.getDetails()) {
			WmsInNoticeDetailEntity source = detail == null ? null : sources.get(detail.getNoticeDetailId());
			require(source != null, "采购入库明细必须关联原收料通知明细");
			if (source.getSourceReceiptId() == null || source.getSourceReceiptDetailId() == null
					|| source.getSourceOrderDetailId() == null) {
				// 历史通知可能缺少精确来源，仍沿旧合同办理，不生成无法验证的事件。
				return false;
			}
			require(Objects.equals(notice.getDocumentId(), source.getSourceReceiptId())
							&& Objects.equals(detail.getSourceReceiptDetailId(), source.getSourceReceiptDetailId())
							&& Objects.equals(detail.getSourceOrderDetailId(), source.getSourceOrderDetailId()),
					"采购入库明细的原批次或订单行不一致");
			if (mobile) {
				require(Objects.equals(detail.getSourceReceiptId(), source.getSourceReceiptId())
								&& Objects.equals(detail.getMatCode(), source.getMatCode())
								&& Objects.equals(detail.getMatBatch(), source.getMatBatch())
								&& Objects.equals(detail.getMatUnit(), source.getMatUnit()),
						"收货物料、批次或单位与原通知明细不一致");
			}
		}
		if (mobile) {
			var openSources = sources.values().stream().filter(source -> source.getNotInNum() != null
					&& source.getNotInNum() > 0).toList();
			if (openSources.size() > 1000 || openSources.stream().anyMatch(source ->
					!Objects.equals(notice.getDocumentId(), source.getSourceReceiptId())
							|| !positiveId(source.getSourceReceiptDetailId())
							|| !positiveId(source.getSourceOrderDetailId())
							|| !sourceText(source.getMatCode(), 200)
							|| !sourceText(source.getMatBatch(), 200)
							|| !sourceText(source.getMatUnit(), 100)
							|| !validOpenQuantity(source.getNotInNum()))) {
				return false;
			}
		}
		R<Boolean> result = pms.eligible(notice.getDocumentId(), notice.getWarehouseCode());
		require(result != null && result.isOk() && result.getData() != null,
				"无法核验采购收料回写方式，请稍后重新办理");
		boolean eligible = Boolean.TRUE.equals(result.getData());
		if (eligible && mobile) {
			require(postings.mobileGuardReady(), "移动采购回写尚未完成部署，请联系仓储管理员");
		}
		return eligible;
	}

	/**
	 * 只接受同事务内已正式过账且具备原逐行入库流水的 V1 单据。提交后才尝试远程写入。
	 *
	 * @param bill 本次 Web 审核或 PDA 收齐后过账的实际入库单
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void enqueue(WmsInBillEntity bill) {
		Scope scope = scope();
		WmsInBillEntity original = bill(scope, bill.getId());
		require(Objects.equals(original.getPurchasePostingVersion(), 1) && isPosted(original),
				"仅已过账的新版采购入库单可登记回写");
		WmsInNoticeEntity notice = notice(scope, original.getNoticeId(), original.getWarehouseCode());
		require(DocumentEnum.PURCHASE_RECEIPT.getCode().equals(notice.getDocumentTable()),
				"采购回写缺少原收料来源");
		PmsPurchaseInboundPosting command = new PmsPurchaseInboundPosting(scope.factory(), original.getWarehouseCode(),
				original.getId(), original.getBillNo(), notice.getId(), notice.getDocumentId(), lines(scope, original, notice));
		String fingerprint = command.fingerprint();
		int inserted = postings.insert(scope.tenant(), scope.factory(), original.getId(), notice.getDocumentId(),
				fingerprint, encode(command));
		if (inserted == 0) {
			Posting existing = postings.find(scope.tenant(), scope.factory(), original.getId());
			require(existing != null && Objects.equals(existing.fingerprint(), fingerprint),
					"原入库单已冻结不同的采购回写内容");
			return;
		}
		require(inserted == 1 && TransactionSynchronizationManager.isSynchronizationActive(),
				"采购回写台账未登记在库存事务内");
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					dispatch(scope, original.getId());
				} catch (RuntimeException exception) {
					log.warn("采购入库单 {} 回写未确认，请按原单续投", original.getId());
				}
			}
		});
	}

	/** 按原仓查看权限读取投递状态，历史单保持明确的旧模式。 */
	public View status(Long billId) {
		billAccess.originals(Family.WAREHOUSE, List.of(billId), Action.VIEW, false);
		Scope scope = scope();
		WmsInBillEntity original = bill(scope, billId);
		Posting row = postings.find(scope.tenant(), scope.factory(), billId);
		if (row != null) {
			return view(row);
		}
		if (!Objects.equals(original.getPurchasePostingVersion(), 1)) {
			return new View("LEGACY", 0, null, null);
		}
		require(!isPosted(original), "已过账采购单的回写台账缺失");
		return new View("NOT_POSTED", 0, null, null);
	}

	/** 按原仓审核权限和实际工厂写权续投，认证后释放原单锁再执行远程调用。 */
	public View retry(Long billId) {
		Scope scope = scope();
		newTransaction().executeWithoutResult(status -> {
			billAccess.originals(Family.WAREHOUSE, List.of(billId), Action.REVIEW, true);
			WmsInBillEntity original = bill(scope, billId);
			require(Objects.equals(original.getPurchasePostingVersion(), 1) && isPosted(original),
					"只有已过账的新版采购入库单允许续投");
		});
		return view(dispatch(scope, billId));
	}

	/** 已提交的 V1 原单才有证明，PMS 无法以旧原单或尚未过账的单据生成永久受理记录。 */
	public String proof(Long billId) {
		Scope scope = scope();
		WmsInBillEntity original = bill(scope, billId);
		require(Objects.equals(original.getPurchasePostingVersion(), 1) && isPosted(original),
				"当前原单没有采购入库V1过账证明");
		Posting row = postings.find(scope.tenant(), scope.factory(), billId);
		require(row != null, "当前原单没有已提交的采购回写记录");
		return row.fingerprint();
	}

	/** Web 以审核通过为过账状态，PDA 则必须是移动来源且整单收齐。 */
	private static boolean isPosted(WmsInBillEntity bill) {
		if (InOutBillSourceEnum.APP.getCode().equals(bill.getSource())) {
			return InOutBillStateEnum.FINISHED.getCode().equals(bill.getState());
		}
		return InOutBillStateEnum.PASSED.getCode().equals(bill.getState());
	}

	private static boolean positiveId(Long id) {
		return id != null && id > 0;
	}

	private static boolean sourceText(String value, int maxLength) {
		return value != null && !value.isBlank() && value.length() <= maxLength && value.equals(value.trim());
	}

	private static boolean validOpenQuantity(double value) {
		if (!Double.isFinite(value)) {
			return false;
		}
		BigDecimal quantity = BigDecimal.valueOf(value).stripTrailingZeros();
		return quantity.scale() <= 6 && quantity.compareTo(V1_QUANTITY_LIMIT) < 0;
	}

	private List<PmsPurchaseInboundPosting.Line> lines(Scope scope, WmsInBillEntity bill, WmsInNoticeEntity notice) {
		var sources = sourceDetails(scope, notice.getId());
		var postedRows = billDetails.selectList(Wrappers.<WmsInBillDetailEntity>lambdaQuery()
				.eq(WmsInBillDetailEntity::getTenantId, scope.tenant())
				.eq(WmsInBillDetailEntity::getFactoryCode, scope.factory())
				.eq(WmsInBillDetailEntity::getBillId, bill.getId()));
		var proofRows = flows.selectList(Wrappers.<WmsInoutFlowEntity>lambdaQuery()
				.eq(WmsInoutFlowEntity::getTenantId, scope.tenant())
				.eq(WmsInoutFlowEntity::getFactoryCode, scope.factory())
				.eq(WmsInoutFlowEntity::getBillId, bill.getId())
				.eq(WmsInoutFlowEntity::getStockProofType, "INBOUND"));
		var proofs = new HashMap<Long, WmsInoutFlowEntity>();
		for (WmsInoutFlowEntity proof : proofRows) {
			require(proofs.putIfAbsent(proof.getBillDetailId(), proof) == null, "同一入库明细存在多份过账流水");
		}
		require(!postedRows.isEmpty() && postedRows.size() == proofs.size(), "入库明细和过账流水不一致");
		var result = new ArrayList<PmsPurchaseInboundPosting.Line>(postedRows.size());
		for (WmsInBillDetailEntity row : postedRows) {
			WmsInNoticeDetailEntity source = sources.get(row.getNoticeDetailId());
			WmsInoutFlowEntity proof = proofs.get(row.getId());
			require(source != null && proof != null && Objects.equals(proof.getSourceInBillDetailId(), row.getId())
							&& Objects.equals(proof.getSourceInBillId(), bill.getId())
							&& Objects.equals(proof.getMatNum(), row.getMatNum())
							&& Objects.equals(proof.getMatCode(), row.getMatCode())
							&& Objects.equals(proof.getMatBatch(), row.getMatBatch())
							&& Objects.equals(proof.getMatUnit(), row.getMatUnit())
							&& Objects.equals(proof.getWarehouseCode(), bill.getWarehouseCode())
							&& Objects.equals(row.getWarehouseCode(), bill.getWarehouseCode())
							&& Objects.equals(source.getSourceReceiptId(), notice.getDocumentId())
							&& Objects.equals(row.getSourceReceiptDetailId(), source.getSourceReceiptDetailId())
							&& Objects.equals(row.getSourceOrderDetailId(), source.getSourceOrderDetailId())
							&& Objects.equals(row.getMatCode(), source.getMatCode())
							&& Objects.equals(row.getMatBatch(), source.getMatBatch())
							&& Objects.equals(row.getMatUnit(), source.getMatUnit())
							&& row.getMatNum() != null && Double.isFinite(row.getMatNum()),
					"入库明细、过账流水或采购收料原行不一致");
			result.add(new PmsPurchaseInboundPosting.Line(row.getId(), source.getSourceReceiptDetailId(),
					source.getSourceOrderDetailId(), row.getMatCode(), row.getMatBatch(), row.getMatUnit(),
					BigDecimal.valueOf(row.getMatNum())));
		}
		return result;
	}

	private HashMap<Long, WmsInNoticeDetailEntity> sourceDetails(Scope scope, Long noticeId) {
		var sourceRows = noticeDetails.selectList(Wrappers.<WmsInNoticeDetailEntity>lambdaQuery()
				.eq(WmsInNoticeDetailEntity::getTenantId, scope.tenant())
				.eq(WmsInNoticeDetailEntity::getFactoryCode, scope.factory())
				.eq(WmsInNoticeDetailEntity::getNoticeId, noticeId));
		var sources = new HashMap<Long, WmsInNoticeDetailEntity>();
		for (WmsInNoticeDetailEntity row : sourceRows) {
			sources.put(row.getId(), row);
		}
		return sources;
	}

	private Posting dispatch(Scope scope, Long billId) {
		UUID token = UUID.randomUUID();
		TransactionTemplate tx = newTransaction();
		Posting claimed = tx.execute(status -> postings.claim(scope.tenant(), scope.factory(), billId, token) == 1
				? postings.find(scope.tenant(), scope.factory(), billId) : null);
		if (claimed != null) {
			try {
				PmsPurchaseInboundPosting command = decode(claimed.payload());
				require(Objects.equals(command.wmsBillId(), billId)
								&& Objects.equals(command.factoryCode(), scope.factory())
								&& Objects.equals(command.purchaseReceiptId(), claimed.receiptId())
								&& Objects.equals(command.fingerprint(), claimed.fingerprint()),
						"采购回写冻结原文已损坏");
				R<PmsPurchaseInboundPosting.Receipt> response = pms.receipt(billId);
				require(response != null && response.isOk(), "未能读取 PMS 原回执");
				if (response.getData() == null) {
					response = pms.accept(command);
				}
				validateReceipt(response, command, claimed);
				tx.executeWithoutResult(status -> postings.delivered(scope.tenant(), scope.factory(), billId, token));
			} catch (RuntimeException exception) {
				tx.executeWithoutResult(status -> postings.failed(scope.tenant(), scope.factory(), billId, token,
						"PMS 回写未确认，请先核对原采购收料，再按原单续投"));
			}
		}
		// 从独立事务读当前状态。提交后回调仍可能持有原事务资源。
		Posting current = tx.execute(status -> postings.find(scope.tenant(), scope.factory(), billId));
		require(current != null, "原入库单没有新版采购回写记录");
		return current;
	}

	private static void validateReceipt(R<PmsPurchaseInboundPosting.Receipt> response,
	                                    PmsPurchaseInboundPosting command, Posting claimed) {
		var receipt = response == null ? null : response.getData();
		require(response != null && response.isOk() && receipt != null
						&& Objects.equals(receipt.wmsBillId(), command.wmsBillId())
						&& Objects.equals(receipt.purchaseReceiptId(), claimed.receiptId())
						&& Objects.equals(receipt.fingerprint(), claimed.fingerprint())
						&& receipt.lineCount() == command.lines().size(),
				"PMS 未返回匹配的采购入库永久回执");
	}

	private WmsInBillEntity bill(Scope scope, Long id) {
		WmsInBillEntity bill = bills.selectOne(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(WmsInBillEntity::getTenantId, scope.tenant())
				.eq(WmsInBillEntity::getFactoryCode, scope.factory())
				.eq(WmsInBillEntity::getId, id)
				.eq(WmsInBillEntity::getBillType, InBillTypeEnum.PURCHASE.getCode()));
		require(bill != null, "当前工厂的原采购入库单不存在");
		return bill;
	}

	private WmsInNoticeEntity notice(Scope scope, Long id, String warehouse) {
		WmsInNoticeEntity notice = notices.selectOne(Wrappers.<WmsInNoticeEntity>lambdaQuery()
				.eq(WmsInNoticeEntity::getTenantId, scope.tenant())
				.eq(WmsInNoticeEntity::getFactoryCode, scope.factory())
				.eq(WmsInNoticeEntity::getWarehouseCode, warehouse)
				.eq(WmsInNoticeEntity::getBillType, InBillTypeEnum.PURCHASE.getCode())
				.eq(WmsInNoticeEntity::getId, id));
		require(notice != null, "原采购入库通知的组织或仓库不一致");
		return notice;
	}

	private String encode(PmsPurchaseInboundPosting command) {
		try {
			return json.writeValueAsString(command);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("采购回写原文冻结失败", exception);
		}
	}

	private PmsPurchaseInboundPosting decode(String payload) {
		try {
			return json.readValue(payload, PmsPurchaseInboundPosting.class);
		} catch (JsonProcessingException exception) {
			throw new CheckedException("采购回写原文无法读取", exception);
		}
	}

	private TransactionTemplate newTransaction() {
		var template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	private static Scope scope() {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		var actor = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && factory != null && !factory.isBlank()
						&& actor != null && Objects.equals(actor.getTenantId(), tenant)
						&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
				"采购回写需要真实登录账号、租户和工厂");
		return new Scope(tenant, factory);
	}

	private static View view(Posting row) {
		return new View(row.status(), row.attemptCount(), row.lastError(), row.deliveredAt());
	}

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

	private record Scope(Long tenant, String factory) {
	}

	/**
	 * 采购单的当前跨域回写结果。
	 *
	 * @param status 旧模式、未过账或新版台账状态
	 * @param attemptCount 原单累计投递次数
	 * @param lastError 最近一次受控异常，未发生时为空
	 * @param deliveredAt 取得 PMS 永久回执的确认时间
	 */
	public record View(String status, int attemptCount, String lastError, LocalDateTime deliveredAt) {
	}
}
