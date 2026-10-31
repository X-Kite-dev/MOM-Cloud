package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.Command;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WmsProductionNoticeCanonicalTest {
    final WmsProductionNoticeAccess.Scope scope = new WmsProductionNoticeAccess.Scope(1L, "F1", "operator", 7L);

    static Command command() {
        var n = new WmsOutNoticeEntity();
        n.setWarehouseCode("W1");
        n.setWarehouseName("原辅仓");
        n.setDocumentTable("mes_picking");
        n.setDocumentId(88L);
        n.setBillType("produce");
        var a = new WmsOutNoticeDetailEntity().setMatCode("M1").setMatUnit("KG").setNoticeOutNum(
                1.25).setSourceDocumentDetailId(81L);
        n.setDetailList(List.of(a));
        var c = new Command();
        c.setTenantId(1L);
        c.setFactoryCode("F1");
        c.setRequestId("PN-original-request-0001");
        c.setSourceRevision(1);
        c.setNotice(n);
        return c;
    }

    @Test
    void technicalIdsAuditTimesAndInputOrderDoNotChangeBusinessIdentity() {
        var a = command();
        var before = WmsProductionNoticeCanonical.prepare(a, scope, false);
        a.getNotice().setId(999L);
        a.getNotice().setCreateTime(LocalDateTime.now());
        a.getNotice().getDetailList().get(0).setId(998L);
        assertEquals(before.noticePayload(), WmsProductionNoticeCanonical.prepare(a, scope, false).noticePayload());
        assertNull(before.notice().getId());
        assertEquals(88L, before.notice().getDetailList().get(0).getDocumentId());
    }

    @Test
    void quantitySourceStockReservationOwnershipAndSubstitutionAreFrozen() {
        var original = WmsProductionNoticeCanonical.prepare(command(), scope, false).noticePayload();
        for (int kind = 0; kind < 6; kind++) {
            var c = command();
            var l = c.getNotice().getDetailList().get(0);
            switch (kind) {
                case 0 -> l.setNoticeOutNum(2d);
                case 1 -> l.setSourceStockId(44L);
                case 2 -> l.setReservationNo("RES-1");
                case 3 -> l.setSourceDocumentDetailId(82L);
                case 4 -> l.setRequirementMatNum(new BigDecimal("3"));
                case 5 -> {
                    l.setSubstituteFlag(true);
                    l.setSubstituteRatio(new BigDecimal("2"));
                }
            }
            assertNotEquals(original, WmsProductionNoticeCanonical.prepare(c, scope, false).noticePayload());
        }
    }

    @Test
    void rejectsNonFiniteQuantitiesForgedScopeAndDuplicateSourceLines() {
        var c = command();
        c.getNotice().getDetailList().get(0).setNoticeOutNum(Double.NaN);
        var invalid = c;
        assertThrows(CheckedException.class, () -> WmsProductionNoticeCanonical.prepare(invalid, scope, false));
        c = command();
        c.setFactoryCode("OTHER");
        var forged = c;
        assertThrows(CheckedException.class, () -> WmsProductionNoticeCanonical.prepare(forged, scope, false));
        c = command();
        c.getNotice().setDetailList(
                List.of(c.getNotice().getDetailList().get(0), c.getNotice().getDetailList().get(0)));
        var duplicate = c;
        assertThrows(CheckedException.class, () -> WmsProductionNoticeCanonical.prepare(duplicate, scope, false));
    }

    @Test
    void normalizedMaterialCodesAndUnitsMatchTheActualPersistedInput() {
        var c = command();
        c.getNotice().getDetailList().get(0).setMatCode(" M1 ");
        c.getNotice().getDetailList().get(0).setMatUnit(" KG ");
        var prepared = WmsProductionNoticeCanonical.prepare(c, scope, false);
        assertEquals("M1", prepared.notice().getDetailList().get(0).getMatCode());
        assertEquals("KG", prepared.notice().getDetailList().get(0).getMatUnit());
        assertEquals(WmsProductionNoticeCanonical.prepare(command(), scope, false).noticePayload(),
                prepared.noticePayload());
    }

}
