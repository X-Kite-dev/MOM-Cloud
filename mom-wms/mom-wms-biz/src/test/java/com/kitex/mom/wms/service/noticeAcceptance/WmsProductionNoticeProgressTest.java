package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;
import com.kitex.mom.wms.repository.WmsProductionNoticeAcceptanceRepository;
import com.kitex.mom.wms.repository.WmsProductionNoticeProgressRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeCanonical.write;

/**
 * 真正执行作用域SQL；分配与单据进度不冒充实物出库证据。
 */
class WmsProductionNoticeProgressTest {
    JdbcTemplate db;
    WmsProductionNoticeProgressService service;
    WmsProductionNoticeProgressRepository repository;
    PermissionService permissions;
    final WmsProductionNoticeAccess.Scope scope = new WmsProductionNoticeAccess.Scope(1L, "F1", "worker", 7L);

    @BeforeEach
    void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:progress-" + UUID.randomUUID() +
                ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE SCHEMA mom_wms");
        db.execute(
                "CREATE TABLE mom_wms.wms_out_notice(id bigint,tenant_id bigint,factory_code varchar,warehouse_code varchar,warehouse_name varchar,bill_type varchar,state varchar,document_id bigint,document_table varchar,source_acceptance_revision int,del_flag varchar)");
        db.execute(
                "CREATE TABLE mom_wms.wms_out_notice_detail(id bigint,tenant_id bigint,factory_code varchar,warehouse_code varchar,notice_id bigint,mat_code varchar,mat_name varchar,mat_unit varchar,notice_out_num numeric(30,8),out_num numeric(30,8),not_out_num numeric(30,8),del_flag varchar)");
        db.execute(
                "CREATE TABLE mom_wms.wms_out_bill(id bigint,tenant_id bigint,factory_code varchar,warehouse_code varchar,bill_type varchar,notice_id bigint,bill_no varchar,state varchar,create_time timestamp,del_flag varchar)");
        db.execute(
                "CREATE TABLE mom_wms.wms_production_notice_acceptance(id bigint,tenant_id bigint,factory_code varchar,document_id bigint,source_revision int,warehouse_code varchar,notice_id bigint,notice_payload varchar,receipt_payload varchar)");
        db.update(
                "INSERT INTO mom_wms.wms_out_notice VALUES(100,1,'F1','W1','原料仓','produce','1',900,'mes_picking',1,'0')");
        line(101, 1, "F1", "W1", "0", "10.00000001", "4", "6.00000001");
        bill(201, 1, "F1", "W1", "produce", "0");
        var access = mock(WmsProductionNoticeAccess.class);
        when(access.scope("W1", false, false)).thenReturn(scope);
        when(access.scope("W2", false, false)).thenThrow(new CheckedException("无仓库权限"));
        permissions = mock(PermissionService.class);
        when(permissions.hasPermission("wms_bill_view_out", "wms_bill_view_out_W1")).thenReturn(true);
        repository = spy(new WmsProductionNoticeProgressRepository(db));
        service = new WmsProductionNoticeProgressService(access, permissions, repository,
                new WmsProductionNoticeAcceptanceRepository(db));
    }

    void line(long id, long tenant, String factory, String warehouse, String deleted, String plan, String allocated,
              String remaining) {
        db.update("INSERT INTO mom_wms.wms_out_notice_detail VALUES(?,?,?,?,100,'MAT','电线','m',?,?,?,?)",
                id, tenant, factory, warehouse, new BigDecimal(plan), new BigDecimal(allocated),
                new BigDecimal(remaining), deleted);
    }

    void bill(long id, long tenant, String factory, String warehouse, String type, String deleted) {
        db.update("INSERT INTO mom_wms.wms_out_bill VALUES(?,?,?,?,?,100,?,'0',CURRENT_TIMESTAMP,?)",
                id, tenant, factory, warehouse, type, "OUT-" + id, deleted);
    }

    void accepted(Long noticeId, String quantity) {
        acceptedLines(noticeId, List.of(new Line(901L, 101L, "MAT", "m", new BigDecimal(quantity))));
    }

    void acceptedLines(Long noticeId, List<Line> lines) {
        var proof = new Receipt(1, "ACCEPT", "ACCEPTED", "REQ-1", "REQ-1", 1L, "F1", "mes_picking", 900L, 1,
                "W1", noticeId, lines, "hash", "{}",
                LocalDateTime.of(2026, 10, 31, 10, 0), "planner", List.of(), "");
        db.update("DELETE FROM mom_wms.wms_production_notice_acceptance");
        db.update("INSERT INTO mom_wms.wms_production_notice_acceptance VALUES(1,1,'F1',900,1,'W1',?,'{}',?)", noticeId,
                write(proof));
    }

    @Test
    void invalidSourceLineIdsFailInsteadOfBeingSilentlyDisplayedAsAccepted() {
        for (long sourceId : List.of(0L, -1L)) {
            acceptedLines(100L, List.of(new Line(sourceId, 101L, "MAT", "m", new BigDecimal("10.00000001"))));
            assertThrows(CheckedException.class, () -> service.progress("W1", 100L), "sourceId=" + sourceId);
        }
    }

    @Test
    void sameSourceLineCannotBeAttachedToTwoDifferentNoticeRows() {
        line(102, 1, "F1", "W1", "0", "2", "0", "2");
        acceptedLines(100L, List.of(new Line(901L, 101L, "MAT", "m", new BigDecimal("10.00000001")),
                new Line(901L, 102L, "MAT", "m", new BigDecimal("2"))));
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
    }

    @Test
    void aMissingLegacySourceLineCannotMaskAnotherInvalidSourceLine() {
        line(102, 1, "F1", "W1", "0", "2", "0", "2");
        acceptedLines(100L, List.of(new Line(null, 101L, "MAT", "m", new BigDecimal("10.00000001")),
                new Line(-1L, 102L, "MAT", "m", new BigDecimal("2"))));
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
    }

    @Test
    void sourceSummaryDistinguishesUnrecordedRecordedAndWarehouseCreatedNotices() {
        var unrecorded = service.progress("W1", 100L).sourceLinks();
        assertEquals("UNRECORDED", unrecorded.status());
        assertEquals(0, unrecorded.linkedLines());
        assertEquals(1, unrecorded.totalLines());
        accepted(100L, "10.00000001");
        var recorded = service.progress("W1", 100L).sourceLinks();
        assertEquals("RECORDED", recorded.status());
        assertEquals(1, recorded.linkedLines());
        db.update(
                "UPDATE mom_wms.wms_out_notice SET document_table=NULL,document_id=NULL,source_acceptance_revision=NULL");
        var manual = service.progress("W1", 100L);
        assertEquals("NOT_APPLICABLE", manual.sourceLinks().status());
        assertEquals(0, manual.sourceLinks().linkedLines());
        assertEquals(1, manual.sourceLinks().totalLines());
        assertEquals("", manual.lines().get(0).sourceLineId());
    }

    @Test
    void acceptedLegacyMissingSourceLineRetainsReceiptAndExistingBillVisibility() {
        acceptedLines(100L, List.of(new Line(null, 101L, "MAT", "m", new BigDecimal("10.00000001"))));
        var progress = service.progress("W1", 100L);
        assertEquals("ACCEPTED", progress.acceptanceStatus());
        assertEquals("REQ-1", progress.receipt().requestId());
        assertEquals("INCOMPLETE", progress.sourceLinks().status());
        assertEquals(0, progress.sourceLinks().linkedLines());
        assertEquals(1, progress.sourceLinks().totalLines());
        assertEquals("", progress.lines().get(0).sourceLineId());
        assertTrue(progress.canViewBills());
        assertEquals("201", progress.bills().get(0).id());
    }

    @Test
    void partialSourceSummaryCountsOnlyExplicitLinksAndDoesNotGuessByMatchingMaterial() {
        line(102, 1, "F1", "W1", "0", "2", "0", "2");
        acceptedLines(100L, List.of(new Line(901L, 101L, "MAT", "m", new BigDecimal("10.00000001")),
                new Line(null, 102L, "MAT", "m", new BigDecimal("2"))));
        var progress = service.progress("W1", 100L);
        assertEquals("INCOMPLETE", progress.sourceLinks().status());
        assertEquals(1, progress.sourceLinks().linkedLines());
        assertEquals(2, progress.sourceLinks().totalLines());
        assertEquals("901", progress.lines().get(0).sourceLineId());
        assertEquals("", progress.lines().get(1).sourceLineId());
    }

    @Test
    void sourceIdsBeyondBrowserIntegerPrecisionAreKeptAsExactStrings() {
        line(102, 1, "F1", "W1", "0", "2", "0", "2");
        acceptedLines(100L, List.of(new Line(Long.MAX_VALUE, 101L, "MAT", "m", new BigDecimal("10.00000001")),
                new Line(9007199254740993L, 102L, "MAT", "m", new BigDecimal("2"))));
        var progress = service.progress("W1", 100L);
        assertEquals("RECORDED", progress.sourceLinks().status());
        assertEquals(2, progress.sourceLinks().linkedLines());
        assertEquals("9223372036854775807", progress.lines().get(0).sourceLineId());
        assertEquals("9007199254740993", progress.lines().get(1).sourceLineId());
    }

    @Test
    void actualReadKeepsDecimalPrecisionAndSeparatesLegacyAcceptanceFromAllocation() {
        var progress = service.progress("W1", 100L);
        assertEquals("100", progress.noticeId());
        assertEquals("LEGACY_REVIEW_REQUIRED", progress.acceptanceStatus());
        assertNull(progress.receipt());
        assertEquals("10.00000001", progress.lines().get(0).planned());
        assertEquals("4.00000000", progress.lines().get(0).allocated());
        assertEquals("6.00000001", progress.lines().get(0).remaining());
        assertEquals("201", progress.bills().get(0).id());
        assertEquals("1", progress.noticeState());
    }

    @Test
    void cancelledNoticeStillShowsOriginalAcceptanceWithoutClaimingCurrentExecution() {
        accepted(100L, "10.00000001");
        db.update("UPDATE mom_wms.wms_out_notice SET state='3'");
        var progress = service.progress("W1", 100L);
        assertEquals("ACCEPTED", progress.acceptanceStatus());
        assertEquals("REQ-1", progress.receipt().requestId());
        assertEquals("901", progress.lines().get(0).sourceLineId());
        assertEquals("3", progress.noticeState());
    }

    @Test
    void allReadsExcludeOtherTenantFactoryWarehouseDeletedAndBusinessType() {
        line(102, 2, "F1", "W1", "0", "9", "0", "9");
        line(103, 1, "F2", "W1", "0", "9", "0", "9");
        line(104, 1, "F1", "W2", "0", "9", "0", "9");
        line(105, 1, "F1", "W1", "1", "9", "0", "9");
        bill(202, 2, "F1", "W1", "produce", "0");
        bill(203, 1, "F2", "W1", "produce", "0");
        bill(204, 1, "F1", "W2", "produce", "0");
        bill(205, 1, "F1", "W1", "sales", "0");
        bill(206, 1, "F1", "W1", "produce", "1");
        assertEquals(1, service.progress("W1", 100L).lines().size());
        assertEquals(1, service.progress("W1", 100L).bills().size());
        for (String mutation : List.of("tenant_id=2", "factory_code='F2'", "warehouse_code='W2'", "del_flag='1'",
                "bill_type='sales'")) {
            db.update("UPDATE mom_wms.wms_out_notice SET " + mutation);
            assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
            db.update(
                    "UPDATE mom_wms.wms_out_notice SET tenant_id=1,factory_code='F1',warehouse_code='W1',del_flag='0',bill_type='produce'");
        }
        assertThrows(CheckedException.class, () -> service.progress("W2", 100L));
    }

    @Test
    void noticeReadPermissionDoesNotReadOrRevealBillsWithoutSeparateBillPermission() {
        when(permissions.hasPermission("wms_bill_view_out", "wms_bill_view_out_W1")).thenReturn(false);
        var progress = service.progress("W1", 100L);
        assertFalse(progress.canViewBills());
        assertTrue(progress.bills().isEmpty());
        verify(repository, never()).bills(any(), anyString(), anyLong());
    }

    @Test
    void receiptMustMatchActualNoticeAndEveryLine() {
        accepted(999L, "10.00000001");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
        db.update("DELETE FROM mom_wms.wms_production_notice_acceptance");
        accepted(100L, "10");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
        db.update("DELETE FROM mom_wms.wms_production_notice_acceptance");
        accepted(100L, "10.00000001");
        db.update("UPDATE mom_wms.wms_out_notice_detail SET mat_code='CHANGED'");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
    }

    @Test
    void invalidCountersStatesOrMissingLinesNeverBecomeHealthyProgress() {
        db.update("UPDATE mom_wms.wms_out_notice_detail SET out_num=5");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
        db.update("UPDATE mom_wms.wms_out_notice_detail SET out_num=4");
        db.update("UPDATE mom_wms.wms_out_notice SET state='unknown'");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
        db.update("UPDATE mom_wms.wms_out_notice SET state='1'");
        db.update("DELETE FROM mom_wms.wms_out_notice_detail");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
    }

    @Test
    void manualNoticeDoesNotInventSourceReceiptAndDatabaseFailurePropagates() {
        db.update(
                "UPDATE mom_wms.wms_out_notice SET document_table=NULL,document_id=NULL,source_acceptance_revision=NULL");
        assertEquals("WAREHOUSE_CREATED", service.progress("W1", 100L).acceptanceStatus());
        db.execute("DROP TABLE mom_wms.wms_out_notice_detail");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.progress("W1", 100L));
    }

    @Test
    void unknownBusinessSourceCannotBeLabelledAsWarehouseCreated() {
        db.update("UPDATE mom_wms.wms_out_notice SET document_table='external_plan'");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
    }

    @Test
    void boundedReadRejectsTruncationAndServiceUsesReadOnlyRepeatableRead() throws Exception {
        for (int i = 0; i < 1000; i++) line(1000 + i, 1, "F1", "W1", "0", "1", "0", "1");
        assertThrows(CheckedException.class, () -> service.progress("W1", 100L));
        var transaction = WmsProductionNoticeProgressService.class.getMethod("progress", String.class,
                Long.class).getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
    }
}
