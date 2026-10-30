package com.kitex.mom.wms.service.inBill;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsNewInboundDetailPreparationTest {
    @Test
    void appDetailFillsMissingBatchBeforeInboundValidation() {
        var handler = new DefaultWmsInBillService();
        var policy = mock(WmsIdentifierPolicyService.class);
        ReflectionTestUtils.setField(handler, "identifierPolicyService", policy);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatUnit("EA").setMatNum(1d);

        handler.prepareNewInboundDetail("other_in", detail);

        var order = inOrder(policy);
        order.verify(policy).fillMissingIdentifiers("other_in", detail);
        order.verify(policy).validateInbound("other_in", detail);
    }

    @Test
    void draftEditGeneratesOnlyForNewDetailsAndRejectsForeignIds() {
        var handler = new DefaultWmsInBillService();
        var policy = mock(WmsIdentifierPolicyService.class);
        ReflectionTestUtils.setField(handler, "identifierPolicyService", policy);
        var saved = new WmsInBillDetailEntity().setMatCode("M1").setMatBarcode("SN-1");
        saved.setId(11L);
        var added = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d);

        handler.prepareDraftIdentifiers("other_in", List.of(saved, added), Set.of(11L));

        verify(policy, never()).fillMissingIdentifiers("other_in", saved);
        verify(policy).fillMissingIdentifiers("other_in", added);
        var foreign = new WmsInBillDetailEntity().setMatCode("M1");
        foreign.setId(22L);
        assertThrows(CheckedException.class,
                () -> handler.prepareDraftIdentifiers("other_in", List.of(foreign), Set.of(11L)));
    }

    @Test
    void appSinglePieceNeverMergesAnExistingDetail() {
        var handler = new DefaultWmsInBillService();
        var policy = mock(WmsIdentifierPolicyService.class);
        ReflectionTestUtils.setField(handler, "identifierPolicyService", policy);
        when(policy.resolveEffective("other_in", "M1"))
                .thenReturn(new WmsIdentifierPolicyEntity().setSerialManaged(true));
        var existing = List.of(new WmsInBillDetailEntity().setMatCode("M1").setMatBarcode("SN-1"));

        assertTrue(handler.shouldAddAppDetail("other_in", "M1", null, existing));
        assertThrows(CheckedException.class,
                () -> handler.shouldAddAppDetail("other_in", "M1", "SN-1", existing));
        assertTrue(handler.shouldAddAppDetail("other_in", "M1", "SN-2", List.of()));
    }

    @Test
    void appBulkDetailCanStillMergeWhenItsIdentityMatches() {
        var handler = new DefaultWmsInBillService();
        var policy = mock(WmsIdentifierPolicyService.class);
        ReflectionTestUtils.setField(handler, "identifierPolicyService", policy);

        assertFalse(handler.shouldAddAppDetail("other_in", "M1", null,
                List.of(new WmsInBillDetailEntity().setMatCode("M1"))));
    }
}
