package com.kitex.mom.wms.service.organization;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Action;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class WmsOrganizationTransferRulesTest {
	@Test
	void sourceAndTargetCannotUseTheOtherSidesActionsOrUnrelatedStates() {
		assertEquals("READY_TO_SHIP", WmsOrganizationTransferRules.next("PENDING_CONFIRMATION", Action.CONFIRM, false));
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.next("PENDING_CONFIRMATION", Action.CONFIRM, true));
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.next("READY_TO_SHIP", Action.SHIP, false));
		assertThrows(CheckedException.class, () -> WmsOrganizationTransferRules.next("COMPLETED", Action.CANCEL, true));
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.next("RETURN_PENDING", Action.RECEIVE, false));
	}

	@Test
	void receivedReturnedAndExceptionQuantitiesCannotExceedIssuedOrInventOnHand() {
		assertEquals(new BigDecimal("3"),
				WmsOrganizationTransferRules.remaining(new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("2"),
						BigDecimal.ONE));
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.remaining(BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ZERO,
						BigDecimal.ZERO));
		assertEquals("PARTIALLY_RECEIVED", WmsOrganizationTransferRules.receiptState(new BigDecimal("3")));
		assertEquals("COMPLETED", WmsOrganizationTransferRules.receiptState(BigDecimal.ZERO));
		assertEquals("CLOSED_WITH_RETURN", WmsOrganizationTransferRules.returnState(BigDecimal.ZERO, BigDecimal.ONE));
		assertEquals("RETURNED", WmsOrganizationTransferRules.returnState(BigDecimal.ZERO, BigDecimal.ZERO));
	}

	@Test
	void crossCompanyRequiresBothPoliciesAgreementAndDifferentRepresentatives() {
		WmsOrganizationTransferRules.companyAgreement("A", "A", false, false, "", "same", "same");
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.companyAgreement("A", "B", true, false, "DOC-1", "a", "b"));
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.companyAgreement("A", "B", true, true, "", "a", "b"));
		assertThrows(CheckedException.class,
				() -> WmsOrganizationTransferRules.companyAgreement("A", "B", true, true, "DOC-1", "a", "a"));
		WmsOrganizationTransferRules.companyAgreement("A", "B", true, true, "DOC-1", "a", "b");
	}
}
