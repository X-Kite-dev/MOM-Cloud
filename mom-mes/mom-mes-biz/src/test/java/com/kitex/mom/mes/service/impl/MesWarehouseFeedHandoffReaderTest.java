package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class MesWarehouseFeedHandoffReaderTest {
    private final MesWarehouseMaterialInBillMapper notices = mock(MesWarehouseMaterialInBillMapper.class);
    private final MesWarehousePickingBillMapper pickings = mock(MesWarehousePickingBillMapper.class);
    private final MesTaskMapper tasks = mock(MesTaskMapper.class);
    private final MesMaterialReceiptMapper receipts = mock(MesMaterialReceiptMapper.class);
    private final MesTaskArrangeMapper arrangements = mock(MesTaskArrangeMapper.class);
    private final MesWarehouseFeedCommandDTO.Context context = new MesWarehouseFeedCommandDTO.Context("1", "F1", "7", "MES_FEED_COMMAND_V1");
    private MesWarehouseFeedHandoffReader reader;
    private MesWarehouseMaterialInBillEntity notice;
    private MesWarehousePickingBillEntity picking;
    private MesTaskEntity task;
    private MesMaterialReceiptEntity receipt;

    @BeforeEach
    void setup() {
        for (var type : List.of(MesWarehouseMaterialInBillEntity.class, MesWarehousePickingBillEntity.class,
                MesTaskEntity.class, MesMaterialReceiptEntity.class, MesTaskArrangeEntity.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "feed-handoff"), type);
        notice = new MesWarehouseMaterialInBillEntity();
        notice.setId(42L); notice.setTenantId(1L); notice.setFactoryCode("F1"); notice.setBillId(50L);
        notice.setMatCode("M1"); notice.setMatBatch("B1"); notice.setMatBarcode("BC1");
        notice.setSupplierCode("SUP1"); notice.setReservationNo("RES1");
        notice.setNoticeInNum(new BigDecimal("10")); notice.setInNum(new BigDecimal("2")); notice.setReceiptBaselineQuantity(BigDecimal.ZERO);
        picking = new MesWarehousePickingBillEntity(); picking.setId(50L); picking.setTenantId(1L); picking.setFactoryCode("F1"); picking.setTaskCode("MT1");
        task = new MesTaskEntity(); task.setId(60L); task.setTenantId(1L); task.setFactoryCode("F1"); task.setCode("MT1");
        receipt = new MesMaterialReceiptEntity().setNoticeId(42L).setFactoryCode("F1").setOperationType("RECEIVE").setQuantity(new BigDecimal("2"));
        receipt.setId(70L); receipt.setTenantId(1L);
        when(notices.selectOne(any(Wrapper.class))).thenReturn(notice);
        when(pickings.selectOne(any(Wrapper.class))).thenReturn(picking);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        when(receipts.selectList(any(Wrapper.class))).thenReturn(List.of(receipt));
        reader = new MesWarehouseFeedHandoffReader(notices, pickings, tasks, receipts, arrangements, new MesPhysicalTransformCodec());
    }

    @Test
    void signedNoticeProvidesFreshHintsAndExactOriginalOrderWithoutAnAvailableQuantity() {
        var result = reader.receiptSeed(42L, context);
        assertNotNull(result);
        assertEquals("MT1", result.taskCode()); assertEquals("M1", result.materialCode()); assertEquals("B1", result.batchNo());
        assertEquals("BC1", result.barcode()); assertEquals("SUP1", result.supplierCode()); assertEquals("RES1", result.reservationNo());
        assertEquals("42", result.noticeId()); assertEquals(context, result.context());
        assertFalse(Set.of(result.getClass().getRecordComponents()).stream().anyMatch(component -> component.getName().toLowerCase().contains("quantity")));
        verify(notices, never()).updateById(any(MesWarehouseMaterialInBillEntity.class)); verify(receipts, never()).insert(any(MesMaterialReceiptEntity.class));
    }

    @Test
    void unsignedUnknownOrUnbalancedReceiptFactsCannotBecomeFeedHints() {
        notice.setInNum(BigDecimal.ZERO); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        notice.setInNum(new BigDecimal("2")); notice.setReceiptBaselineQuantity(null);
        assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        notice.setReceiptBaselineQuantity(BigDecimal.ZERO); when(receipts.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
    }

    @Test
    void sourceIdentityIsExplicitlyScopedEvenWhenAMapperReturnsAnUnexpectedRow() {
        notice.setFactoryCode("F2"); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        notice.setFactoryCode("F1"); notice.setTenantId(2L); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        notice.setTenantId(1L); notice.setId(43L); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
    }

    @Test
    void linkedOrdersMustExistInTheSameTenantFactoryAndCannotBeSilentlyDropped() {
        picking.setFactoryCode("F2"); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        picking.setFactoryCode("F1"); task.setTenantId(2L); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        task.setTenantId(1L); task.setCode("OTHER"); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
    }

    @Test
    void unlinkedFormalReceiptsKeepTheOrderUnspecifiedForExplicitSelection() {
        notice.setBillId(null);
        var result = reader.receiptSeed(42L, context);
        assertNotNull(result); assertNull(result.taskCode());
        verifyZeroInteractions(pickings, tasks);
    }

    @Test
    void foreignOrUnknownLedgerRowsAreNotTreatedAsValidPositiveReceipts() {
        receipt.setFactoryCode("F2"); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
        receipt.setFactoryCode("F1"); receipt.setOperationType("OTHER"); assertThrows(CheckedException.class, () -> reader.receiptSeed(42L, context));
    }

    @Test
    void candidatePaginationFiltersExactOrderActualFactoryAndOpenStatuses() {
        var arrange = new MesTaskArrangeEntity(); arrange.setId(101L); arrange.setTenantId(1L); arrange.setFactoryCode("F1");
        arrange.setCode("A1"); arrange.setTaskCode("MT1"); arrange.setStatus("RECEIVE"); arrange.setRouteName("装配");
        when(arrangements.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(call -> {
            AbstractWrapper<?, ?, ?> wrapper = call.getArgument(1); String sql = wrapper.getSqlSegment();
            assertTrue(sql.contains("task_code =")); assertFalse(sql.contains("task_code LIKE"));
            var values = wrapper.getParamNameValuePairs().values();
            assertTrue(values.containsAll(List.of(1L, "F1", "MT1", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE")));
            assertFalse(values.contains("FINISH"));
            return Page.<MesTaskArrangeEntity>of(2, 10, 33).setRecords(List.of(arrange));
        });
        var result = reader.candidates(2, 10, "MT1", "", context);
        assertNotNull(result); assertEquals(33, result.total()); assertEquals(2, result.current());
        assertEquals("101", result.records().get(0).arrangeId()); assertEquals("A1", result.records().get(0).arrangeCode());
    }

    @Test
    void invalidPagingAndTextAreRejectedBeforeCandidateQueries() {
        assertThrows(CheckedException.class, () -> reader.candidates(0, 10, "MT1", "", context));
        assertThrows(CheckedException.class, () -> reader.candidates(1, 101, "MT1", "", context));
        assertThrows(CheckedException.class, () -> reader.candidates(1, 10, " MT1", "", context));
        assertThrows(CheckedException.class, () -> reader.candidates(1, 10, null, "bad\ncode", context));
        verifyZeroInteractions(arrangements);
    }
}
