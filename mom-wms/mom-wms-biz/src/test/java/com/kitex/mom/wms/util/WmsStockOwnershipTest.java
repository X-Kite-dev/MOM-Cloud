package com.kitex.mom.wms.util;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WmsStockOwnershipTest {
	@Test
	void ordinaryInboundDefaultsToCompanyOwnership() {
		var detail = new WmsInBillDetailEntity();

		WmsStockOwnership.prepareInbound("purchase", detail);

		assertEquals("OWNED", detail.getStockOwnerType());
	}

	@Test
	void externalInboundRequiresOwnerAndSource() {
		var detail = new WmsInBillDetailEntity().setStockOwnerType("CUSTOMER_SUPPLIED");
		assertThrows(CheckedException.class, () -> WmsStockOwnership.prepareInbound("other_in", detail));

		detail.setStockOwnerCode("CUSTOMER-1").setSourceBusinessNo("CUSTOMER-ORDER-1");
		WmsStockOwnership.prepareInbound("other_in", detail);
		assertEquals("CUSTOMER_SUPPLIED", detail.getStockOwnerType());
	}

	@Test
	void normalReceiptCannotSilentlyCreateExternallyOwnedStock() {
		var detail = new WmsInBillDetailEntity().setStockOwnerType("CONSIGNED")
				.setStockOwnerCode("SUPPLIER-1").setSourceBusinessNo("CONSIGNMENT-1");

		assertThrows(CheckedException.class, () -> WmsStockOwnership.prepareInbound("purchase", detail));
	}

	@Test
	void ordinaryIssueCannotUseExternallyOwnedStock() {
		var stock = new WmsLiveStockEntity().setStockOwnerType("CUSTOMER_SUPPLIED")
				.setStockOwnerCode("CUSTOMER-1").setStatus(0);

		assertFalse(WmsStockOwnership.isCompanyOwned(stock));
		assertFalse(WmsStockAvailability.isUsable(stock));
		assertTrue(WmsStockAvailability.isPhysicallyUsable(stock));
		assertThrows(CheckedException.class, () -> WmsStockOwnership.validateOutbound(
				"produce", new WmsOutBillDetailEntity(), stock));
	}

	@Test
	void externalIssueRequiresExactStockAndMatchingOwner() {
		var stock = new WmsLiveStockEntity().setStockOwnerType("CONSIGNED")
				.setStockOwnerCode("SUPPLIER-1").setSourceBusinessNo("CONSIGNMENT-1");
		stock.setId(7L);
		var detail = new WmsOutBillDetailEntity().setSourceStockId(7L)
				.setStockOwnerType("CONSIGNED").setStockOwnerCode("SUPPLIER-1");
		assertDoesNotThrow(() -> WmsStockOwnership.validateOutbound("other_out", detail, stock));

		detail.setStockOwnerCode("SUPPLIER-2");
		assertThrows(CheckedException.class, () -> WmsStockOwnership.validateOutbound("other_out", detail, stock));
		detail.setStockOwnerCode("SUPPLIER-1").setSourceStockId(null);
		assertThrows(CheckedException.class, () -> WmsStockOwnership.validateOutbound("other_out", detail, stock));
	}

	@Test
	void exactExternalIssueUsesItsOriginalInboundSource() {
		var stock = new WmsLiveStockEntity().setStockOwnerType("CUSTOMER_SUPPLIED")
				.setStockOwnerCode("CUSTOMER-1").setSourceBusinessNo("RECEIPT-1");
		stock.setId(7L);
		var detail = new WmsOutBillDetailEntity().setSourceStockId(7L)
				.setStockOwnerType("CUSTOMER_SUPPLIED").setStockOwnerCode("CUSTOMER-1");

		WmsStockOwnership.validateOutbound("other_out", detail, stock);

		assertEquals("RECEIPT-1", detail.getSourceBusinessNo());
		detail.setSourceBusinessNo("OTHER-RECEIPT");
		assertThrows(CheckedException.class, () -> WmsStockOwnership.validateOutbound("other_out", detail, stock));
	}

	@Test
	void externalStockWithoutProvenanceCannotBeIssued() {
		var stock = new WmsLiveStockEntity().setStockOwnerType("CONSIGNED")
				.setStockOwnerCode("SUPPLIER-1");
		stock.setId(7L);
		var detail = new WmsOutBillDetailEntity().setSourceStockId(7L)
				.setStockOwnerType("CONSIGNED").setStockOwnerCode("SUPPLIER-1");

		assertThrows(CheckedException.class, () -> WmsStockOwnership.validateOutbound("other_out", detail, stock));
	}

	@Test
	void companyOwnedIssueKeepsItsOwnOutboundSource() {
		var stock = new WmsLiveStockEntity().setStockOwnerType("OWNED")
				.setSourceBusinessNo("PURCHASE-1");
		stock.setId(7L);
		var detail = new WmsOutBillDetailEntity().setSourceStockId(7L)
				.setStockOwnerType("OWNED").setSourceBusinessNo("SALES-1");

		WmsStockOwnership.validateOutbound("sales", detail, stock);

		assertEquals("SALES-1", detail.getSourceBusinessNo());
	}
}
