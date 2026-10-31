package com.kitex.mom.wms.service.inBill;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WmsInBillDraftIntegrityTest {

	@Test
	void invalidDetailDoesNotConsumeIdentifierRuleBeforeSave() {
		WmsIdentifierPolicyService policy = mock(WmsIdentifierPolicyService.class);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "identifierPolicyService", policy);
		WmsInBillEntity bill = bill();
		bill.getDetails().get(0).setMatNum(0D);

		assertThrows(CheckedException.class, () -> service.beforeSaveDeep(bill));

		verify(policy, never()).fillMissingIdentifiers(anyString(), any(WmsInBillDetailEntity.class));
	}

	@Test
	void invalidMobileDetailDoesNotConsumeIdentifierRule() {
		WmsIdentifierPolicyService policy = mock(WmsIdentifierPolicyService.class);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "identifierPolicyService", policy);
		WmsInBillDetailEntity detail = bill().getDetails().get(0);
		detail.setMatNum(Double.NaN);

		assertThrows(CheckedException.class, () -> service.prepareNewInboundDetail("purchase", detail));

		verify(policy, never()).fillMissingIdentifiers(anyString(), any(WmsInBillDetailEntity.class));
	}

	@Test
	void rejectsBlankIdentityAndInvalidQuantity() {
		DefaultWmsInBillService service = service();
		WmsInBillEntity blankMaterial = bill();
		blankMaterial.getDetails().get(0).setMatCode(" ");
		assertThrows(CheckedException.class, () -> service.checkData(blankMaterial));

		WmsInBillEntity blankUnit = bill();
		blankUnit.getDetails().get(0).setMatUnit(" ");
		assertThrows(CheckedException.class, () -> service.checkData(blankUnit));

		for (double quantity : List.of(-1D, Double.NaN, Double.POSITIVE_INFINITY)) {
			WmsInBillEntity invalid = bill();
			invalid.getDetails().get(0).setMatNum(quantity);
			assertThrows(CheckedException.class, () -> service.checkData(invalid));
		}
		WmsInBillEntity missingQuantity = bill();
		missingQuantity.getDetails().get(0).setMatNum(null);
		assertThrows(CheckedException.class, () -> service.checkData(missingQuantity));
	}

	@Test
	void completeInboundLineStillPassesBasicValidation() {
		assertDoesNotThrow(() -> service().checkData(bill()));
	}

	private static DefaultWmsInBillService service() {
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "identifierPolicyService", mock(WmsIdentifierPolicyService.class));
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(com.kitex.mom.wms.service.WmsWarehouseBusinessGuard.class));
		return service;
	}

	private static WmsInBillEntity bill() {
		WmsInBillDetailEntity detail = new WmsInBillDetailEntity();
		detail.setMatCode("M1");
		detail.setMatUnit("EA");
		detail.setMatNum(1D);
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setBillType("purchase");
		bill.setWarehouseCode("W1");
		bill.setDetails(List.of(detail));
		return bill;
	}
}
