package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsStockMoveSlice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsAllocationMobileFlowTest {
    private final WmsAllocationBillServiceImpl service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
    private final List<WmsInoutFlowEntity> inserted = new ArrayList<>();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "wmsInoutFlowMapper", flows);
        when(flows.insert(any(WmsInoutFlowEntity.class))).thenAnswer(call -> {
            WmsInoutFlowEntity flow = call.getArgument(0);
            flow.setId((long) inserted.size() + 100);
            inserted.add(flow);
            return 1;
        });
    }

    @Test
    void mobileTransferFlowsKeepExactSerialSourceAndPairedReceipt() {
        var command = command();
        command.setSourceStockId(7L);
        command.setMatBarcode("SERIAL-7");

        service.recordImmediateTransferFlows(bill(), List.of(slice(7L, "SERIAL-7", true)), command);

        assertEquals(2, inserted.size());
        WmsInoutFlowEntity issue = inserted.get(0);
        WmsInoutFlowEntity receipt = inserted.get(1);
        assertEquals(InOutEnum.OUT.getCode(), issue.getOperateType());
        assertEquals(7L, issue.getSourceStockId());
        assertEquals("SERIAL-7", issue.getMatBarcode());
        assertEquals(Boolean.TRUE, issue.getSerialManaged());
        assertEquals(1d, issue.getMatNum());
        assertEquals("W1", issue.getWarehouseCode());
        assertEquals(issue.getId(), receipt.getSourceFlowId());
        assertEquals(7L, receipt.getSourceStockId());
        assertEquals("SERIAL-7", receipt.getMatBarcode());
        assertEquals("W2", receipt.getWarehouseCode());
        assertEquals("T1", receipt.getSiteCode());
    }

    @Test
    void ordinaryMobileTransferWritesOneLinkedFlowPairPerActualSourceRow() {
        var command = command();
        command.setOperateNum(2d);

        service.recordImmediateTransferFlows(bill(),
                List.of(slice(7L, null, false), slice(8L, null, false)), command);

        assertEquals(4, inserted.size());
        assertEquals(7L, inserted.get(0).getSourceStockId());
        assertEquals(inserted.get(0).getId(), inserted.get(1).getSourceFlowId());
        assertEquals(8L, inserted.get(2).getSourceStockId());
        assertEquals(inserted.get(2).getId(), inserted.get(3).getSourceFlowId());
        assertEquals(1d, inserted.get(0).getMatNum());
        assertEquals(1d, inserted.get(2).getMatNum());
    }

    @Test
    void failedReceiptInsertCannotReportCompletedMobileTransfer() {
        var command = command();
        command.setSourceStockId(7L);
        command.setMatBarcode("SERIAL-7");
        when(flows.insert(any(WmsInoutFlowEntity.class))).thenAnswer(call -> {
            WmsInoutFlowEntity flow = call.getArgument(0);
            if (inserted.isEmpty()) {
                flow.setId(100L);
                inserted.add(flow);
                return 1;
            }
            return 0;
        });

        assertThrows(CheckedException.class,
                () -> service.recordImmediateTransferFlows(bill(), List.of(slice(7L, "SERIAL-7", true)), command));
    }

    @Test
    void serialMobileTransferCannotRecordFractionalEvidence() {
        var command = command();
        command.setSourceStockId(7L);
        command.setMatBarcode("SERIAL-7");
        command.setOperateNum(0.5d);
        var stock = slice(7L, "SERIAL-7", true).sourceStock();

        assertThrows(CheckedException.class,
                () -> service.recordImmediateTransferFlows(bill(),
                        List.of(new WmsStockMoveSlice(stock, 70L, 0.5d)), command));
        assertTrue(inserted.isEmpty());
    }

    @Test
    void mobileRequestSnapshotDistinguishesTwoScannedSerials() {
        var first = command();
        first.setSourceStockId(7L);
        first.setMatBarcode("SERIAL-7");
        var second = command();
        second.setSourceStockId(8L);
        second.setMatBarcode("SERIAL-8");

        String firstSnapshot = ReflectionTestUtils.invokeMethod(service, "appRequestSnapshot", first);
        String secondSnapshot = ReflectionTestUtils.invokeMethod(service, "appRequestSnapshot", second);
        assertNotEquals(firstSnapshot, secondSnapshot);
    }

    private WmsAllocationBillEntity bill() {
        var bill = new WmsAllocationBillEntity().setBillNo("TR-1").setFactoryCode("F1");
        bill.setId(50L);
        return bill;
    }

    private WmsAllocationBillAppDTO command() {
        var command = new WmsAllocationBillAppDTO();
        command.setBillNo("TR-1");
        command.setOldWarehouseCode("W1");
        command.setOldSiteCode("S1");
        command.setNewWarehouseCode("W2");
        command.setNewWarehouseName("Target");
        command.setNewSiteCode("T1");
        command.setNewSiteName("Target site");
        command.setOperateNum(1d);
        command.setMatCode("M1");
        return command;
    }

    private WmsStockMoveSlice slice(Long sourceId, String barcode, boolean serial) {
        var stock = new WmsLiveStockEntity().setWarehouseCode("W1").setSiteCode("S1")
                .setMatCode("M1").setMatBarcode(barcode).setSerialManaged(serial)
                .setMatNum(1d).setFactoryCode("F1");
        stock.setId(sourceId);
        return new WmsStockMoveSlice(stock, 70L, 1d);
    }
}
