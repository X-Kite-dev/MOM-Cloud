package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.repository.WmsProductionNoticeAcceptanceRepository;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 受理凭据与真实通知写端使用同一关系库和Spring事务，不用计数mock冒充原子性。
 */
class WmsProductionNoticeTransactionTest {
    JdbcTemplate db;
    WmsProductionNoticeAcceptanceRepository repo;
    WmsProductionNoticeAcceptanceService service;
    final WmsProductionNoticeAccess.Scope scope = new WmsProductionNoticeAccess.Scope(1L, "F1", "operator", 7L);
    final AtomicLong ids = new AtomicLong(100);
    boolean failSecond;
    boolean failReceipt;
    boolean mutateSubstitution;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:production-notice-" + UUID.randomUUID() +
                ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_wms");
        db.execute("SET SCHEMA mom_wms");
        db.execute(
                "CREATE TABLE mom_wms.wms_out_notice(id bigint primary key,tenant_id bigint,factory_code varchar,document_id bigint,document_table varchar,source_acceptance_revision integer,warehouse_code varchar,warehouse_name varchar,bill_type varchar,state varchar,notice_out_num numeric(30,6),all_out_num numeric,del_flag varchar)");
        db.execute(
                "CREATE TABLE mom_wms.wms_out_notice_detail(id bigint primary key,tenant_id bigint,factory_code varchar,notice_id bigint,document_id bigint,document_table varchar,warehouse_code varchar,mat_code varchar,mat_unit varchar,notice_out_num numeric(30,6),source_stock_id bigint,reservation_no varchar,stock_owner_type varchar,stock_owner_code varchar,requirement_mat_num numeric(20,8),substitute_ratio numeric(20,8),del_flag varchar)");
        db.execute(
                "CREATE TABLE mom_wms.wms_out_bill(id bigint,tenant_id bigint,factory_code varchar,notice_id bigint,del_flag varchar)");
        String sql = Files.readString(Path.of("../database/pending/wms-production-notice-acceptance-pg.sql"));
        var matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS[\\s\\S]*?;").matcher(sql);
        while (matcher.find()) db.execute(matcher.group());
        db.execute("CREATE TABLE mom_wms.request_lock(lock_key varchar primary key)");
        repo = spy(new WmsProductionNoticeAcceptanceRepository(db));
        doAnswer(call -> {
            String key = call.getArgument(0) + ":" + call.getArgument(1);
            db.update("MERGE INTO mom_wms.request_lock KEY(lock_key) VALUES(?)", key);
            db.queryForObject("SELECT lock_key FROM mom_wms.request_lock WHERE lock_key=? FOR UPDATE", String.class,
                    key);
            return null;
        }).when(repo).lock(anyLong(), anyString());
        doAnswer(call -> {
            if (failReceipt) throw new CheckedException("模拟原请求回执写入失败");
            return call.callRealMethod();
        }).when(repo).insertOperation(any(), anyString(), anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString());
        var access = mock(WmsProductionNoticeAccess.class);
        when(access.scope(anyString(), anyBoolean(), anyBoolean())).thenReturn(scope);
        var writer = new DefaultWmsOutNoticeService();
        var notices = mock(WmsOutNoticeMapper.class);
        var details = mock(WmsOutNoticeDetailMapper.class);
        ReflectionTestUtils.setField(writer, "wmsOutNoticeMapper", notices);
        ReflectionTestUtils.setField(writer, "wmsOutNoticeDetailMapper", details);
        ReflectionTestUtils.setField(writer, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));
        when(notices.insert(any(WmsOutNoticeEntity.class))).thenAnswer(call -> {
            WmsOutNoticeEntity n = call.getArgument(0);
            n.setId(ids.incrementAndGet());
            db.update("INSERT INTO mom_wms.wms_out_notice VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", n.getId(),
                    n.getTenantId(), n.getFactoryCode(), n.getDocumentId(), n.getDocumentTable(),
                    n.getSourceAcceptanceRevision(), n.getWarehouseCode(), n.getWarehouseName(), n.getBillType(),
                    n.getState(), n.getNoticeOutNum(), 0, "0");
            return 1;
        });
        when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenAnswer(call -> {
            WmsOutNoticeDetailEntity n = call.getArgument(0);
            if (failSecond && "W2".equals(n.getWarehouseCode())) throw new CheckedException("第二仓模拟失败");
            n.setId(ids.incrementAndGet());
            db.update("INSERT INTO mom_wms.wms_out_notice_detail VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", n.getId(),
                    n.getTenantId(), n.getFactoryCode(), n.getNoticeId(), n.getDocumentId(), n.getDocumentTable(),
                    n.getWarehouseCode(), n.getMatCode(), n.getMatUnit(), n.getNoticeOutNum(), n.getSourceStockId(),
                    n.getReservationNo(), n.getStockOwnerType(), n.getStockOwnerCode(), n.getRequirementMatNum(),
                    n.getSubstituteRatio(), "0");
            if (mutateSubstitution) db.update(
                    "UPDATE mom_wms.wms_out_notice_detail SET requirement_mat_num=requirement_mat_num+0.01,substitute_ratio=substitute_ratio+0.01 WHERE id=?",
                    n.getId());
            return 1;
        });
        var billMapper = mock(WmsOutBillMapper.class);
        ReflectionTestUtils.setField(writer, "wmsOutBillMapper", billMapper);
        when(billMapper.selectCount(any())).thenReturn(0L);
        when(notices.lockNotice(anyLong())).thenAnswer(
                call -> db.queryForObject("SELECT id FROM mom_wms.wms_out_notice WHERE id=? FOR UPDATE", Long.class,
                        (Long) call.getArgument(0)));
        when(notices.selectById(anyLong())).thenAnswer(
                call -> db.queryForObject("SELECT * FROM mom_wms.wms_out_notice WHERE id=?",
                        new org.springframework.jdbc.core.BeanPropertyRowMapper<>(WmsOutNoticeEntity.class),
                        (Long) call.getArgument(0)));
        when(notices.update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WmsOutNoticeEntity>) call.getArgument(
                    0);
            wrapper.getSqlSegment();
            Long id = wrapper.getParamNameValuePairs().values().stream().filter(v -> v instanceof Long).map(
                    v -> (Long) v).findFirst().orElseThrow();
            return db.update("UPDATE mom_wms.wms_out_notice SET state='3' WHERE id=? AND state='0'", id);
        });
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "production-notice"),
                WmsOutNoticeEntity.class);
        service = new WmsProductionNoticeAcceptanceService(repo, access, writer);
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (WmsProductionNoticeAcceptanceService) factory.getProxy();
        com.kitex.common.data.tenant.TenantContextHolder.setTenantId(1L);
        com.kitex.common.data.orgFilter.FactoryContextHolder.setFactoryCode("F1");
    }

    @AfterEach
    void clear() {
        com.kitex.common.data.tenant.TenantContextHolder.clear();
        com.kitex.common.data.orgFilter.FactoryContextHolder.clear();
    }

    Command command(String request, String warehouse) {
        var c = WmsProductionNoticeCanonicalTest.command();
        c.setRequestId(request);
        c.getNotice().setWarehouseCode(warehouse);
        if ("W2".equals(warehouse)) c.getNotice().getDetailList().get(0).setSourceDocumentDetailId(82L);
        return c;
    }

    Cancel cancel(String request, int revision) {
        var c = new Cancel();
        c.setRequestId(request);
        c.setTenantId(1L);
        c.setFactoryCode("F1");
        c.setDocumentId(88L);
        c.setSourceRevision(revision);
        c.setReason("原领料来源撤回");
        return c;
    }

    int count(String table) {
        return db.queryForObject("SELECT count(*) FROM mom_wms." + table, Integer.class);
    }

    @Test
    void sameRequestAndSameSourceAliasReturnOnePersistedNoticeWithRealDetailIds() {
        var c = command("PN-original-request-0001", "W1");
        var receipt = service.accept(c);
        assertEquals(receipt, service.accept(c));
        c.setRequestId("PN-alias-request-00002");
        var alias = service.accept(c);
        assertEquals(receipt.noticeId(), alias.noticeId());
        assertEquals(receipt.requestId(), alias.originalRequestId());
        assertNotNull(alias.lines().get(0).noticeDetailId());
        assertEquals(81L, alias.lines().get(0).sourceDocumentDetailId());
        assertEquals(1, count("wms_out_notice"));
        assertEquals(2, count("wms_production_notice_operation"));
    }

    @Test
    void changedContentOrReusedRequestCannotCreateAnotherNotice() {
        var c = command("PN-original-request-0001", "W1");
        service.accept(c);
        c.getNotice().getDetailList().get(0).setNoticeOutNum(2d);
        var changed = c;
        assertThrows(CheckedException.class, () -> service.accept(changed));
        c.setRequestId("PN-alias-request-00002");
        assertThrows(CheckedException.class, () -> service.accept(changed));
        c = command("PN-original-request-0001", "W2");
        var other = c;
        assertThrows(CheckedException.class, () -> service.accept(other));
        assertEquals(1, count("wms_out_notice"));
    }

    @Test
    void multihouseFailureRollsBackAllNewNoticesAndReceipts() {
        failSecond = true;
        assertThrows(CheckedException.class, () -> service.acceptBatch(
                List.of(command("PN-original-request-0001", "W1"), command("PN-second-request-00002", "W2"))));
        assertEquals(0, count("wms_out_notice"));
        assertEquals(0, count("wms_production_notice_acceptance"));
        assertEquals(0, count("wms_production_notice_operation"));
    }

    @Test
    void priorSingleWarehouseAcceptanceSurvivesLaterBatchFailureAndCanBeQueried() {
        var first = service.accept(command("PN-original-request-0001", "W1"));
        failSecond = true;
        assertThrows(CheckedException.class, () -> service.acceptBatch(
                List.of(command("PN-original-request-0001", "W1"), command("PN-second-request-00002", "W2"))));
        assertEquals(first, service.source("W1", 88L, 1).acceptance());
        assertEquals("NOT_FOUND", service.source("W2", 88L, 1).acceptanceStatus());
        assertEquals(1, count("wms_out_notice"));
    }

    @Test
    void unknownSourceCancellationFencesLateOldRevisionAndRequiresExplicitNewRevision() {
        service.cancel(cancel("PN-cancel-request-0001", 1));
        assertThrows(CheckedException.class, () -> service.accept(command("PN-original-request-0001", "W1")));
        var next = command("PN-revision-two-000001", "W1");
        next.setSourceRevision(2);
        next.setExpectedPreviousRevision(1);
        assertEquals(2, service.accept(next).sourceRevision());
        assertEquals(1, count("wms_out_notice"));
    }

    @Test
    void cancellationKeepsOriginalAcceptanceProofAndDoesNotClaimPhysicalOutput() {
        var first = service.accept(command("PN-original-request-0001", "W1"));
        service.cancel(cancel("PN-cancel-request-0001", 1));
        assertEquals(first, service.lookup("W1", first.requestId()).receipt());
        assertEquals("3", service.source("W1", 88L, 1).noticeState());
        assertEquals("CANCELLED", service.source("W1", 88L, 1).sourceStatus());
        var next = command("PN-revision-two-000001", "W1");
        next.setSourceRevision(2);
        next.setExpectedPreviousRevision(1);
        service.accept(next);
        assertEquals(2, count("wms_out_notice"));
        assertEquals(first, service.lookup("W1", first.requestId()).receipt());
    }

    @Test
    void associatedOutBillBlocksWithdrawalWithoutPartialCancellation() {
        var a = service.accept(command("PN-original-request-0001", "W1"));
        service.accept(command("PN-second-request-00002", "W2"));
        db.update("INSERT INTO mom_wms.wms_out_bill VALUES(1,1,'F1',?,'0')", a.noticeId());
        assertThrows(CheckedException.class, () -> service.cancel(cancel("PN-cancel-request-0001", 1)));
        assertEquals(2,
                db.queryForObject("SELECT count(*) FROM mom_wms.wms_out_notice WHERE state='0'", Integer.class));
    }

    @Test
    void historicSourceWithoutReceiptRequiresReviewInsteadOfGuessingOriginalContent() {
        db.update(
                "INSERT INTO mom_wms.wms_out_notice VALUES(1,1,'F1',88,'mes_picking',null,'W1','原辅仓','produce','0',1,0,'0')");
        assertThrows(CheckedException.class, () -> service.accept(command("PN-original-request-0001", "W1")));
        assertEquals("LEGACY_REVIEW_REQUIRED", service.source("W1", 88L, 1).acceptanceStatus());
        assertEquals(1, count("wms_out_notice"));
    }

    @Test
    void databaseQuantityRoundingRollsBackInsteadOfIssuingFalseReceipt() {
        var c = command("PN-original-request-0001", "W1");
        c.getNotice().getDetailList().get(0).setNoticeOutNum(0.0000001);
        assertThrows(CheckedException.class, () -> service.accept(c));
        assertEquals(0, count("wms_out_notice"));
    }

    @Test
    void concurrentSameOriginalHasOnePhysicalNotice() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = executor.submit(() -> {
                start.await();
                return concurrentAccept();
            });
            var two = executor.submit(() -> {
                start.await();
                return concurrentAccept();
            });
            start.countDown();
            assertEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("wms_out_notice"));
        assertEquals(1, count("wms_production_notice_operation"));
    }

    Receipt concurrentAccept() {
        com.kitex.common.data.tenant.TenantContextHolder.setTenantId(1L);
        com.kitex.common.data.orgFilter.FactoryContextHolder.setFactoryCode("F1");
        try {
            return service.accept(command("PN-original-request-0001", "W1"));
        } finally {
            clear();
        }
    }

    @Test
    void oneOriginalMesLineCannotBeOfferedAgainAtAnotherWarehouse() {
        service.accept(command("PN-original-request-0001", "W1"));
        var duplicate = command("PN-second-request-00002", "W2");
        duplicate.getNotice().getDetailList().get(0).setSourceDocumentDetailId(81L);
        assertThrows(CheckedException.class, () -> service.accept(duplicate));
        assertEquals(1, count("wms_out_notice"));
    }

    @Test
    void finalReceiptFailureRollsBackNotificationAndAllEvidence() {
        failReceipt = true;
        assertThrows(CheckedException.class, () -> service.accept(command("PN-original-request-0001", "W1")));
        assertEquals(0, count("wms_out_notice"));
        assertEquals(0, count("wms_out_notice_detail"));
        assertEquals(0, count("wms_production_notice_acceptance"));
    }

    @Test
    void legacyRetryWithWhitespaceCannotReviveANoticeCancelledIndividually() {
        var n = WmsProductionNoticeCanonicalTest.command().getNotice();
        n.setWarehouseCode(" W1 ");
        n.getDetailList().get(0).setSourceDocumentDetailId(null);
        assertTrue(service.acceptLegacy(n));
        assertTrue(service.acceptLegacy(n));
        assertEquals(1, count("wms_out_notice"));
        db.update("UPDATE mom_wms.wms_out_notice SET state='3' WHERE id=?", n.getId());
        assertThrows(CheckedException.class, () -> service.acceptLegacy(n));
        assertEquals(1, count("wms_out_notice"));
    }

    @Test
    void unboundWarehouseCannotLearnAnotherWarehousesSourceLifecycle() {
        service.accept(command("PN-original-request-0001", "W1"));
        var other = service.source("W2", 88L, 1);
        assertNull(other.currentRevision());
        assertEquals("NOT_FOUND", other.sourceStatus());
        assertTrue(other.legacyNoticeIds().isEmpty());
    }

    @Test
    void cancellationAndLateAcceptanceSerializeOnTheSource() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var accepting = executor.submit(() -> {
                start.await();
                try {
                    return concurrentAccept();
                } catch (CheckedException cancelled) {
                    return null;
                }
            });
            var cancelling = executor.submit(() -> {
                start.await();
                return service.cancel(cancel("PN-cancel-request-0001", 1));
            });
            start.countDown();
            accepting.get(10, TimeUnit.SECONDS);
            assertEquals("CANCELLED", cancelling.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(0,
                db.queryForObject("SELECT count(*) FROM mom_wms.wms_out_notice WHERE state<>'3'", Integer.class));
        assertEquals("CANCELLED",
                db.queryForObject("SELECT state FROM mom_wms.wms_production_notice_source WHERE document_id=88",
                        String.class));
    }

    @Test
    void historicalSourceAtAnotherWarehouseCannotBeMisclassifiedAsANewSource() {
        db.update(
                "INSERT INTO mom_wms.wms_out_notice VALUES(1,1,'F1',88,'mes_picking',null,'W1','原辅仓','produce','0',1,0,'0')");
        assertThrows(CheckedException.class, () -> service.accept(command("PN-second-request-00002", "W2")));
        assertEquals(1, count("wms_out_notice"));
        assertEquals(0, count("wms_production_notice_source"));
    }

    @Test
    void ninthDecimalOrRoundedZeroCannotProduceSuccessfulAcceptance() {
        for (int kind = 0; kind < 2; kind++) {
            var c = command("PN-decimal-review-0000" + kind, "W1");
            var line = c.getNotice().getDetailList().get(0);
            if (kind == 0) line.setRequirementMatNum(new BigDecimal("0.000000001"));
            else line.setSubstituteRatio(new BigDecimal("1.123456789"));
            assertThrows(CheckedException.class, () -> service.accept(c));
            assertEquals(0, count("wms_out_notice"));
            assertEquals(0, count("wms_out_notice_detail"));
            assertEquals(0, count("wms_production_notice_source"));
            assertEquals(0, count("wms_production_notice_acceptance"));
            assertEquals(0, count("wms_production_notice_line"));
            assertEquals(0, count("wms_production_notice_operation"));
        }
    }

    @Test
    void validPrecisionStillRequiresActualStoredSubstitutionFactsToMatch() {
        var c = command("PN-decimal-review-00001", "W1");
        var line = c.getNotice().getDetailList().get(0);
        line.setRequirementMatNum(new BigDecimal("1.25"));
        line.setSubstituteRatio(new BigDecimal("1"));
        mutateSubstitution = true;
        assertThrows(CheckedException.class, () -> service.accept(c));
        assertEquals(0, count("wms_out_notice"));
        assertEquals(0, count("wms_out_notice_detail"));
        assertEquals(0, count("wms_production_notice_source"));
        assertEquals(0, count("wms_production_notice_acceptance"));
        assertEquals(0, count("wms_production_notice_operation"));
    }

    @Test
    void eightDecimalSubstitutionBoundaryIsPreservedWithoutRounding() {
        var c = command("PN-decimal-review-00001", "W1");
        var line = c.getNotice().getDetailList().get(0);
        line.setNoticeOutNum(112345678d);
        line.setRequirementMatNum(new BigDecimal("100000000"));
        line.setSubstituteRatio(new BigDecimal("1.12345678"));
        var proof = service.accept(c);
        assertEquals(0, new BigDecimal("1.12345678").compareTo(
                db.queryForObject("SELECT substitute_ratio FROM mom_wms.wms_out_notice_detail WHERE notice_id=?",
                        BigDecimal.class, proof.noticeId())));
        assertEquals(0, new BigDecimal("100000000").compareTo(
                db.queryForObject("SELECT requirement_mat_num FROM mom_wms.wms_out_notice_detail WHERE notice_id=?",
                        BigDecimal.class, proof.noticeId())));
    }

}
