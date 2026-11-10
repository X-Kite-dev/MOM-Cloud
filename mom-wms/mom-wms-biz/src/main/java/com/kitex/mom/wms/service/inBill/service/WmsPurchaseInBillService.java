package com.kitex.mom.wms.service.inBill.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.pms.api.feign.RemotePurchaseReceiptService;
import com.kitex.mom.pms.dto.WmsPurchaseReceiptInNumDTO;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.service.WmsPurchaseInboundPostingService;
import com.kitex.mom.wms.service.inBill.AbstractWmsInBillHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 办理采购入库，按原入库单冻结的回写版本回写采购收料单。
 */
@Component
@RequiredArgsConstructor
public class WmsPurchaseInBillService extends AbstractWmsInBillHandler {

	private final RemotePurchaseReceiptService remotePurchaseReceiptService;
	private final WmsPurchaseInboundPostingService postingService;

	/** {@inheritDoc} */
	@Override
	public Boolean support(String billType) {
		return InBillTypeEnum.PURCHASE.getCode().equals(billType);
	}

	/** 新采购单按来源及切换配置冻结协议，后续审核或补扫不重新选择。 */
	@Override
	protected void freezePurchasePostingVersion(WmsInBillEntity bill) {
		if (postingService.shouldFreezeV1(bill)) {
			bill.setPurchasePostingVersion(1);
		}
	}

	/** 仅原号移动入口的新采购单可按独立开关冻结永久回写。 */
	@Override
	protected void freezeMobilePurchasePostingVersion(WmsInBillEntity bill) {
		if (postingService.shouldFreezeMobileV1(bill)) {
			bill.setPurchasePostingVersion(1);
		}
	}

	/** Web 审核按已冻结的原单版本，在库存过账事务中形成事件或沿用旧回写。 */
	@Override
	public void handleDocumentBizWhenAdd(WmsInBillEntity bill) {
		WmsInBillEntity original = originalPostingBill(bill);
		if (usesPostingV1(original)) {
			postingService.enqueue(bill);
			return;
		}
		updatePurchaseReceipt(original.getNoticeId(), bill.getDetails());
	}

	/** 新版移动原单在整单过账时登记永久回写；历史原单继续逐次回写本次实收量。 */
	@Override
	protected void handleDocumentBizAfterMobileReceipt(WmsInBillEntity bill,
			List<WmsInBillDetailEntity> receivedDetails) {
		WmsInBillEntity original = originalPostingBill(bill);
		if (usesPostingV1(original)) {
			if (InOutBillStateEnum.FINISHED.getCode().equals(original.getState())) {
				postingService.enqueue(original);
			}
			return;
		}
		updatePurchaseReceipt(original.getNoticeId(), receivedDetails);
	}

	/**
	 * 在过账事务内重读原单归属及冻结版本，避免以当前配置或客户端回传决定回写方式。
	 *
	 * @param bill 本次事务保存或办理的采购入库单
	 * @return 实际持久化的采购入库单
	 */
	private WmsInBillEntity originalPostingBill(WmsInBillEntity bill) {
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		if (bill == null || bill.getId() == null || tenant == null || tenant <= 0 || StrUtil.isBlank(factory)
				|| Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
			throw new CheckedException("采购回写必须具有实际租户、工厂和原入库单");
		}
		WmsInBillEntity original = wmsInBillMapper.selectOne(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(WmsInBillEntity::getId, bill.getId())
				.eq(WmsInBillEntity::getTenantId, tenant)
				.eq(WmsInBillEntity::getFactoryCode, factory)
				.eq(WmsInBillEntity::getBillType, InBillTypeEnum.PURCHASE.getCode()));
		if (original == null || !Objects.equals(bill.getWarehouseCode(), original.getWarehouseCode())
				|| !Objects.equals(bill.getNoticeId(), original.getNoticeId())) {
			throw new CheckedException("原采购入库单的业务、组织或来源已变化，请重新核对");
		}
		return original;
	}

	private boolean usesPostingV1(WmsInBillEntity bill) {
		Integer version = bill.getPurchasePostingVersion();
		if (version == null) {
			return false;
		}
		if (version != 1) {
			throw new CheckedException("采购入库单的回写版本不支持，请核对原单");
		}
		return true;
	}

	/**
	 * 沿原通知找到采购收料来源。首条远程回写前核完全部明细，未确认时提示核对双方原单。
	 *
	 * @param noticeId 原入库通知 ID，没有关联通知时无采购收料回写
	 * @param details 本次回写增量，数量为物料标准单位
	 */
	private void updatePurchaseReceipt(Long noticeId, List<WmsInBillDetailEntity> details) {
		if (noticeId == null) {
			return;
		}
		WmsInNoticeEntity notice = wmsInNoticeMapper.selectById(noticeId);
		if (notice == null || notice.getDocumentId() == null
				|| !DocumentEnum.PURCHASE_RECEIPT.getCode().equals(notice.getDocumentTable())) {
			return;
		}
		if (details == null || details.isEmpty()) {
			throw new CheckedException("采购入库明细为空，无法回写收料单");
		}
		List<WmsPurchaseReceiptInNumDTO> updates = new ArrayList<>(details.size());
		for (WmsInBillDetailEntity detail : details) {
			if (detail == null || StrUtil.isBlank(detail.getMatCode()) || detail.getMatBatch() == null
					|| detail.getMatNum() == null || !Double.isFinite(detail.getMatNum()) || detail.getMatNum() <= 0) {
				throw new CheckedException("采购入库明细的物料、批次或数量无效，无法回写收料单");
			}
			WmsPurchaseReceiptInNumDTO body = new WmsPurchaseReceiptInNumDTO();
			body.setDocumentId(notice.getDocumentId());
			body.setOrderDetailId(detail.getSourceOrderDetailId());
			body.setMatCode(detail.getMatCode());
			body.setMatBatch(detail.getMatBatch());
			body.setMatNum(detail.getMatNum());
			updates.add(body);
		}
		for (WmsPurchaseReceiptInNumDTO update : updates) {
			var response = remotePurchaseReceiptService.addMatNum(update);
			if (response == null || !response.isOk() || !Boolean.TRUE.equals(response.getData())) {
				throw new CheckedException("采购回写结果未确认，请先核对原入库单和采购收料单");
			}
		}
	}
}
