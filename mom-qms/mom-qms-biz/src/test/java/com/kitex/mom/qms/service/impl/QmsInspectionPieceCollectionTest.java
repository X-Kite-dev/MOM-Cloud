package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kitex.mom.qms.dto.QmsInspectionPieceDTO.*;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.mapper.QmsInspectionPieceMapper;
import com.kitex.mom.qms.service.*;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.*;
import java.util.*;

/** 真正的父任务/样本/计数/原请求事务；只适配外部工厂授权。 */
class QmsInspectionPieceCollectionTest {
    QmsProcedureInspectionIntegrityTest fixture;
    JdbcTemplate db;
    InspectionProcedureTaskService tasks;
    QmsInspectionPieceService pieces;
    long task, detail;
    @BeforeEach void setup() throws Exception {
        fixture=new QmsProcedureInspectionIntegrityTest(); fixture.setup();
        db=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"db");
        var sessions=(SqlSessionTemplate)ReflectionTestUtils.getField(fixture,"sessions");
        sessions.getConfiguration().addMapper(QmsInspectionPieceMapper.class);
        Path cwd=Path.of("").toAbsolutePath(), sql=null;
        for(Path base=cwd; base!=null && sql==null; base=base.getParent())
            for(String relative:List.of("Code/docs/database/pending/qms-inspection-piece-collection.sql","docs/database/pending/qms-inspection-piece-collection.sql"))
                if(Files.isRegularFile(base.resolve(relative))) { sql=base.resolve(relative); break; }
        assertNotNull(sql,"实际待升级SQL必须可读取");
        var ddl=Files.readString(sql).split("-- portable_piece_tables_begin")[1].split("-- portable_piece_tables_end")[0];
        for(var statement:ddl.split(";")) if(!statement.isBlank()) db.execute(statement);
        db.execute("CREATE ALIAS mom_qms.mom_inspection_piece_ready_v1 FOR 'com.kitex.mom.qms.service.impl.QmsInspectionHistoryTransactionTest.installedVersion'");
        tasks=(InspectionProcedureTaskService)ReflectionTestUtils.getField(fixture,"service");
        var target=AopTestUtils.getTargetObject(tasks);
        var raw=new QmsInspectionPieceService(sessions.getMapper(QmsInspectionPieceMapper.class),
            (QmsInspectionHistorySupport)ReflectionTestUtils.getField(target,"inspectionAccess"),
            (QmsInspectionBasisService)ReflectionTestUtils.getField(target,"inspectionBasis"),
            (QmsInspectionProcedureTaskDetailService)ReflectionTestUtils.getField(target,"procedureTaskDetailService"));
        var history=ReflectionTestUtils.getField(fixture,"historyFixture");
        var proxy=new ProxyFactory(raw); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor((org.springframework.transaction.PlatformTransactionManager)ReflectionTestUtils.getField(history,"transactions"),new AnnotationTransactionAttributeSource()));
        pieces=(QmsInspectionPieceService)proxy.getProxy(); ReflectionTestUtils.setField(target,"inspectionPieces",pieces);
        task=tasks.create(ReflectionTestUtils.invokeMethod(fixture,"command"));
        detail=db.queryForObject("SELECT id FROM mom_qms.qms_inspection_procedure_task_detail WHERE task_id=?",Long.class,task);
    }
    @AfterEach void close() { if(fixture!=null)fixture.close(); }
    Command command(String request,String code,String number) { return new Command(request,Long.toString(task),code,List.of(new Result(Long.toString(detail),number,null))); }
    Command command() { return command("piece-original-001","SN-0001","0"); }
    long count(String table) { return db.queryForObject("SELECT count(*) FROM mom_qms."+table+" WHERE task_id=?",Long.class,task); }
    void unchanged() { assertEquals(0,count("qms_inspection_piece")); assertEquals(0,count("qms_inspection_piece_submission")); assertEquals(0,count("qms_inspection_piece_sample")); assertEquals(0,count("qms_inspection_procedure_item_result_record")); }
    @Test void zeroValuePersistsActualSampleAndOriginalRequestInSameTransaction() {
        var receipt=tasks.collectPiece(command()); assertEquals("COMPLETED",receipt.state());
        assertEquals(1,count("qms_inspection_piece")); assertEquals(1,count("qms_inspection_piece_sample"));
        var view=pieces.view(Long.toString(task),1); assertEquals("1",view.collectedPieces()); assertFalse(view.firstPassEligible());
        assertEquals("OPERATOR_RECORDED",view.evidenceKind()); assertEquals("0",view.pieces().get(0).samples().get(0).valueText());
        assertTrue(view.pieces().get(0).samples().get(0).qualified());
        assertEquals(1,db.queryForObject("SELECT finish_num FROM mom_qms.qms_inspection_procedure_task_detail WHERE id=?",Integer.class,detail));
    }
    @Test void originalReplayDoesNotCreateAnotherSampleAndBodyDriftIsRejected() {
        var first=tasks.collectPiece(command()); assertEquals(first,tasks.collectPiece(command()));
        assertThrows(RuntimeException.class,()->tasks.collectPiece(command("piece-original-001","SN-0002","1")));
        assertEquals(1,count("qms_inspection_piece_sample"));
    }
    @Test void duplicateIdentifierCannotConsumeAnotherRequiredSample() {
        tasks.collectPiece(command()); assertThrows(RuntimeException.class,()->tasks.collectPiece(command("piece-original-002","SN-0001","1")));
        assertEquals(1,count("qms_inspection_piece_sample"));
    }
    @Test void finalPieceUsesOriginalJudgmentAndCompletedReplayStillWorks() {
        tasks.collectPiece(command()); var last=command("piece-original-002","SN-0002","11");
        var receipt=tasks.collectPiece(last); assertEquals("COMPLETED",receipt.state());
        assertEquals("UN_QUALIFIED",db.queryForObject("SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=?",String.class,task));
        assertEquals(receipt,tasks.collectPiece(last));
        assertEquals(2,count("qms_inspection_piece_sample"));
    }
    @Test void cancellationTombstonePreventsLateRequestWithoutReversingCompletedOne() {
        assertEquals("NOT_FOUND",pieces.request(Long.toString(task),command().requestId()).state());
        assertEquals("CANCELED",pieces.cancel(command()).state());
        assertEquals("CANCELED",tasks.collectPiece(command()).state());
        assertEquals(0,count("qms_inspection_piece_sample"));
        var next=command("piece-original-002","SN-0001","0"); tasks.collectPiece(next);
        assertEquals("COMPLETED",pieces.cancel(next).state()); assertEquals(1,count("qms_inspection_piece_sample"));
    }
    @Test void cancellationOfUnknownRequestWorksAfterSoftDeletion() {
        db.update("UPDATE mom_qms.qms_inspection_procedure_task SET del_flag='1' WHERE id=?",task);
        assertEquals("CANCELED",pieces.cancel(command()).state()); assertEquals(0,count("qms_inspection_piece"));
    }
    @Test void unknownRequestIsReadOnlyAndActualFactoryMustMatch() {
        assertEquals("NOT_FOUND",pieces.request(Long.toString(task),command().requestId()).state()); unchanged();
        FactoryContextHolder.setFactoryCode("OTHER"); assertThrows(RuntimeException.class,()->pieces.view(Long.toString(task),1));
        assertThrows(RuntimeException.class,()->pieces.cancel(command()));
    }
    @Test void actualFactoryWriteRevocationBlocksCollection() {
        ReflectionTestUtils.setField(fixture,"authorized",false); assertThrows(RuntimeException.class,()->tasks.collectPiece(command())); unchanged();
    }
    @Test void anonymousCollectionCannotMixAfterIdentifiedSample() {
        tasks.collectPiece(command()); assertThrows(RuntimeException.class,()->tasks.temporarilySave(new InspectionCheckResultParam().setDetailId(detail).setRecord(new InspectionItemResultRecord().setDoubleValue(1D))));
        assertEquals(1,count("qms_inspection_procedure_item_result_record"));
    }
    @Test void existingAnonymousSamplesCannotBeRetroactivelyAssignedIdentity() {
        tasks.temporarilySave(new InspectionCheckResultParam().setDetailId(detail).setRecord(new InspectionItemResultRecord().setDoubleValue(1D)));
        assertThrows(RuntimeException.class,()->tasks.collectPiece(command())); assertEquals(0,count("qms_inspection_piece"));
        assertEquals("ANONYMOUS_STARTED",pieces.view(Long.toString(task),1).availability());
    }
    @ParameterizedTest @ValueSource(strings={"0.10000000000000001","9007199254740993","NaN","1e3"," 1","1.","01"})
    void numericProtocolRejectsPrecisionLossAndNonDecimalInput(String raw) {
        assertThrows(RuntimeException.class,()->tasks.collectPiece(command("piece-original-001","SN-0001",raw))); unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"insertPiece","insertSubmission","insertSample"})
    void eachNecessaryEvidenceWriteReturningZeroRollsBackEverything(String name) {
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(fixture,"fault"),"lose","QmsInspectionPieceMapper."+name);
        assertThrows(RuntimeException.class,()->tasks.collectPiece(command())); unchanged();
        assertEquals(0,db.queryForObject("SELECT finish_num FROM mom_qms.qms_inspection_procedure_task_detail WHERE id=?",Integer.class,detail));
    }
    @Test void zeroCounterWriteRollsBackIdentityLedgerAndSampleLinks() {
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(fixture,"fault"),"lose","QmsInspectionProcedureTaskDetailMapper.updateById");
        assertThrows(RuntimeException.class,()->tasks.collectPiece(command())); unchanged();
    }
    @Test void oldDatabaseWithoutAnyPieceTableRetainsAnonymousWorkflow() {
        db.execute("DROP TABLE mom_qms.qms_inspection_piece_sample"); db.execute("DROP TABLE mom_qms.qms_inspection_piece_submission"); db.execute("DROP TABLE mom_qms.qms_inspection_piece");
        tasks.temporarilySave(new InspectionCheckResultParam().setDetailId(detail).setRecord(new InspectionItemResultRecord().setDoubleValue(0D)));
        assertEquals(1,count("qms_inspection_procedure_item_result_record"));
    }
    @Test void partialMigrationCannotFallBackToAnonymousCollection() {
        db.execute("DROP TABLE mom_qms.qms_inspection_piece_sample");
        assertThrows(RuntimeException.class,()->tasks.temporarilySave(new InspectionCheckResultParam().setDetailId(detail).setRecord(new InspectionItemResultRecord().setDoubleValue(0D))));
        assertEquals(0,count("qms_inspection_procedure_item_result_record"));
    }
}
