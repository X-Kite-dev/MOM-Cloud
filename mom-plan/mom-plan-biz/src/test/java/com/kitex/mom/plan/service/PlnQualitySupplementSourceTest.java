package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.plan.dto.PlanQualityLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnMpsCommand;
import com.kitex.mom.plan.dto.PlanMrpSupplyWithdrawalDTO;
import com.kitex.mom.plan.dto.PlnDemandReplanEvidenceDTO;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.Map;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.*;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 公开审批入口、实际MyBatis/租户插件/事务与本域持久化。
 * MDM响应和MRP编排端口为受控替身；不声称真实MRP、PG咨询锁或现场端到端通过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlnQualitySupplementSourceTest {

    @Mock private PlnProductDemandService demands;
    @Mock private PlanMatMrpService mrp;
    @Mock private RemoteMaterialService materials;
    @InjectMocks private PlnQualityLocalReplanServiceImpl target;

    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private DataSourceTransactionManager transactions;
    private PlnMpsService mpsService;
    private PlnMpsRevisionExecutor mpsExecutor;
    private PlnQualitySupplementWithdrawalService withdrawalService;
    private boolean failRelease;
    private final List<String> releaseKeys = new ArrayList<>();
    private PlnProductDemandMapper demandRows;
    private PlanMatMrpMapper mrpRows;
    private PlnIssueMapper issueRows;
    private PlnQualitySupplementDemandMapper supplements;
    private PlnQualityLocalReplanService service;
    private PlanQualityLocalReplanDTO.SupplementReviewCommand command;
    private MdmMaterialMeasureDTO measure;
    private PlnQualitySupplementPlanningProjector projector;
    private PlnQualitySupplementProgressReader progress;
    private boolean failAudit;
    private boolean failMrp;
    private int mrpCalls;
    private int lockCalls;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        login("reviewer", 1L);
        var source = new DriverManagerDataSource("jdbc:h2:mem:quality-supplement-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        var mapperTypes = List.of(PlnProductDemandMapper.class, PlanMatMrpMapper.class, PlnIssueMapper.class,
                PlnQualitySupplementDemandMapper.class, PlnQualityReplanCaseMapper.class,
                PlnQualityReplanOperationMapper.class, PlnQualitySupplementWithdrawalMapper.class,
                PlnFormalPlanContractMapper.class, PlnMpsLineMapper.class, PlnMpsVersionMapper.class,
                PlnMpsPolicyMapper.class, PlnMpsOperationMapper.class, PlnProductDemandChangeMapper.class,
                PlnSchedulingOperationMapper.class, PlnSchedulingTaskDetailMapper.class,
                PlnSchedulingBatchMapper.class, PlnSchedulingBatchAllocationMapper.class,
                PlnQualitySupplementWithdrawalOperationMapper.class);
        mapperTypes.forEach(config::addMapper);
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() {
                return new LongValue(TenantContextHolder.getTenantId());
            }
        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(plugins);
        sessions = new SqlSessionTemplate(factory.getObject());
        transactions = new DataSourceTransactionManager(source);
        for (Class<?> entity : List.of(PlnProductDemandEntity.class, PlanMatMrpEntity.class, PlnIssueEntity.class,
                PlnQualitySupplementDemandEntity.class, PlnQualityReplanCaseEntity.class,
                PlnQualityReplanOperationEntity.class, PlnQualitySupplementWithdrawalEntity.class,
                PlnFormalPlanContractEntity.class, PlnMpsLineEntity.class, PlnMpsVersionEntity.class,
                PlnMpsPolicyEntity.class, PlnMpsOperationEntity.class, PlnProductDemandChangeEntity.class,
                PlnSchedulingOperationEntity.class, PlnSchedulingTaskDetailEntity.class,
                PlnSchedulingBatchEntity.class, PlnSchedulingBatchAllocationEntity.class,
                PlnQualitySupplementWithdrawalOperationEntity.class)) {
            createTable(entity);
        }
        demandRows = sessions.getMapper(PlnProductDemandMapper.class);
        mrpRows = sessions.getMapper(PlanMatMrpMapper.class);
        issueRows = sessions.getMapper(PlnIssueMapper.class);
        supplements = sessions.getMapper(PlnQualitySupplementDemandMapper.class);
        ReflectionTestUtils.setField(target, "supplementMapper", adapt(PlnQualitySupplementDemandMapper.class, supplements));
        ReflectionTestUtils.setField(target, "caseMapper", adapt(PlnQualityReplanCaseMapper.class,
                sessions.getMapper(PlnQualityReplanCaseMapper.class)));
        ReflectionTestUtils.setField(target, "issueMapper", issueRows);
        ReflectionTestUtils.setField(target, "operationMapper", adapt(PlnQualityReplanOperationMapper.class,
                sessions.getMapper(PlnQualityReplanOperationMapper.class)));
        ReflectionTestUtils.setField(target, "withdrawalMapper", sessions.getMapper(PlnQualitySupplementWithdrawalMapper.class));
        ReflectionTestUtils.setField(target, "objectMapper", new ObjectMapper().findAndRegisterModules());
        var guard = new PlnQualitySupplementSourceGuard(supplements, materials);
        progress = new PlnQualitySupplementProgressReader(supplements);
        projector = new PlnQualitySupplementPlanningProjector(supplements,
                sessions.getMapper(PlnQualitySupplementWithdrawalMapper.class), issueRows, guard, new PlnMpsRootPlanService(issueRows), progress);
        ReflectionTestUtils.setField(target, "supplementSourceGuard", guard);
        ReflectionTestUtils.setField(target, "supplementPlanning", projector);
        ReflectionTestUtils.setField(target, "supplementProgress", progress);
        ReflectionTestUtils.setField(target, "publicationLock", mock(PlnMpsCapacityPublicationLock.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        service = (PlnQualityLocalReplanService) proxy.getProxy();

        when(demands.savePlan(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            PlnProductDemandEntity demand = call.getArgument(0);
            demand.setId(201L);
            demand.setDemandNo("SUP-DEMAND-201");
            demand.setPlanState("0");
            return demandRows.insert(demand) == 1;
        });
        when(demands.receive(any())).thenAnswer(call -> {
            assertArrayEquals(new Long[]{201L}, call.getArgument(0));
            var demand = demandRows.selectById(201L);
            assertEquals("0", demand.getPlanState());
            demand.setPlanState("1");
            return demandRows.updateById(demand) == 1;
        });
        when(demands.mrp(any())).thenAnswer(call -> {
            assertArrayEquals(new Long[]{201L}, call.getArgument(0));
            mrpCalls++;
            var demand = demandRows.selectById(201L);
            assertEquals("1", demand.getPlanState());
            projector.validateForMrp(demand);
            var buckets = mpsService == null ? List.<PlnMpsLineEntity>of() : mpsService.forMrp(demand);
            demand.setPlanState("2");
            demand.setDemandWarehouseName("补产仓");
            demandRows.updateById(demand);
            db.update("delete from plan_mat_mrp where plan_demand_no=?", demand.getDemandNo());
            var lines = new ArrayList<PlanMatMrpEntity>();
            for (int i = 0; i < Math.max(1, buckets.size()); i++) {
                var line = new PlanMatMrpEntity();
                line.setId(301L + i);
                line.setPlanDemandNo(demand.getDemandNo());
                line.setMatCode("M1");
                line.setFactoryCode("F1");
                line.setNum(BigDecimal.ONE);
                line.setStatus(false);
                if (buckets.isEmpty()) line.setPlanningSourceKey("DEMAND-201");
                else {
                    var bucket = buckets.get(i);
                    line.setMpsLineId(bucket.getId());
                    line.setMpsVersionId(bucket.getVersionId());
                    line.setPlanningSourceKey(bucket.getBucketKey());
                }
                mrpRows.insert(line);
                lines.add(line);
            }
            if (failMrp) return R.failed("模拟MRP失败");
            projector.onMrpReady(demand, lines, buckets);
            return R.ok();
        });
        when(demands.getById(any())).thenAnswer(call -> demandRows.selectById((Long) call.getArgument(0)));
        when(mrp.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(call -> mrpRows.selectList(call.getArgument(0)));
        measure = new MdmMaterialMeasureDTO();
        measure.setMaterialId("11");
        measure.setTenantId("1");
        measure.setFactoryCode("F1");
        measure.setMaterialCode("P1");
        measure.setBasicUnit("kg");
        measure.setEnabled(true);
        when(materials.productionMeasure("P1")).thenReturn(R.ok(measure));
        db.update("insert into pln_product_demand(id,tenant_id,factory_code,del_flag,demand_no,product_code,product_unit) "
                + "values(101,1,'F1','0','OLD-DEMAND','P1','kg')");
        db.update("insert into pln_issue(id,tenant_id,factory_code,del_flag,issue_no,source_demand_no,product_code,"
                + "product_name,num,scrap_num,material_attribute,planning_source_key,mps_line_id,source_mrp_id) "
                + "values(11,1,'F1','0','OLD-ISSUE','OLD-DEMAND','P1','产品',10,2,'mes','OLD-MPS',99,98)");
        db.update("insert into pln_quality_replan_case(id,tenant_id,factory_code,del_flag,case_no,issue_id,issue_no,"
                + "product_code,status) values(21,1,'F1','0','QC-21',11,'OLD-ISSUE','P1','SUPPLEMENT_REQUIRED')");
        var supplement = new PlnQualitySupplementDemandEntity().setSupplementNo("SUP-31").setCaseId(21L)
                .setSourceIssueId(11L).setSourceIssueNo("OLD-ISSUE").setProductCode("P1").setProductName("产品")
                .setSourceRequiredQuantity(new BigDecimal("2")).setRequestQuantity(BigDecimal.ONE)
                .setQualityEvidenceFingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest("11|OLD-ISSUE|F1|P1|10.00000000|2.00000000".getBytes(StandardCharsets.UTF_8))))
                .setDemandDate(LocalDate.of(2026, 11, 1)).setDemandWarehouseCode("SUP-WH")
                .setStatus("SUBMITTED").setApplicantCode("applicant").setFactoryCode("F1")
                .setRequestFingerprint("a".repeat(64));
        supplement.setId(31L);
        supplements.insert(supplement);
        command = new PlanQualityLocalReplanDTO.SupplementReviewCommand();
        command.setSupplementId(31L);
        command.setRequestId("SUP-REVIEW-1");
        command.setApproved(true);
        command.setExpectedRequestFingerprint("a".repeat(64));
        command.setReason("质量报废补产复核");
    }

    @AfterEach
    void cleanup() {
        if (db != null) db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void approvalCommitsReceivedDemandWithoutInvokingTheMpsMrpGate() {
        failMrp = true;
        var result = service.reviewSupplement(command);
        assertEquals("DEMAND_CREATED", result.getStatus());
        assertEquals("1", demandRows.selectById(result.getProductDemandId()).getPlanState());
        assertNull(result.getSupplementIssueId());
        assertEquals(0, mrpCalls);
        assertEquals(1, count("pln_issue"));
        assertEquals(1, supplementDemandCount());
    }

    @Test
    void approvedSupplementHasItsOwnDemandSourceAndActualProductUnit() {
        var result = approveAndPlan();
        var demand = demandRows.selectById(result.getProductDemandId());
        var issue = issueRows.selectById(result.getSupplementIssueId());
        assertAll(
                () -> assertEquals("MRP_GENERATED", result.getStatus()),
                () -> assertEquals("kg", demand.getProductUnit()),
                () -> assertEquals("QUALITY_SUPPLEMENT", demand.getSourceType()),
                () -> assertEquals(31L, demand.getSourceId()),
                () -> assertEquals("SUP-31", demand.getSourceNo()),
                () -> assertEquals("SUP-DEMAND-201", issue.getSourceDemandNo()),
                () -> assertEquals("DEMAND-201", issue.getPlanningSourceKey()),
                () -> assertNull(issue.getMpsLineId()),
                () -> assertNull(issue.getSourceMrpId()),
                () -> assertEquals(0, BigDecimal.ONE.compareTo(issue.getNum())),
                () -> assertTrue(lockCalls >= 2));
        assertEquals(0, new BigDecimal("2").compareTo(issueRows.selectById(11L).getScrapNum()));
    }

    @Test
    void identicalReviewReplayDoesNotCreateAnotherDemandOrRunMrpAgain() {
        var first = approveAndPlan();
        var second = service.reviewSupplement(command);
        assertEquals(first.getProductDemandId(), second.getProductDemandId());
        assertEquals(first.getSupplementIssueId(), second.getSupplementIssueId());
        assertEquals(1, mrpCalls);
        assertEquals(1, supplementDemandCount());
        assertEquals(2, count("pln_issue"));
    }

    @Test
    void reviewReplayInAnotherFactoryCannotReadTheOriginalReceipt() {
        approveAndPlan();
        FactoryContextHolder.setFactoryCode("F2");

        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));

        assertEquals(1, mrpCalls);
        assertEquals(1, supplementDemandCount());
        assertEquals(2, count("pln_issue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "factory", "product", "unit", "disabled", "missing", "failed", "exception", "identity", "unknownState"})
    void invalidMaterialEvidenceCannotProduceSupplementDemand(String problem) {
        switch (problem) {
            case "tenant" -> measure.setTenantId("2");
            case "factory" -> measure.setFactoryCode("F2");
            case "product" -> measure.setMaterialCode("OTHER");
            case "unit" -> measure.setBasicUnit(" ");
            case "disabled" -> measure.setEnabled(false);
            case "missing" -> when(materials.productionMeasure("P1")).thenReturn(R.ok(null));
            case "failed" -> when(materials.productionMeasure("P1")).thenReturn(R.failed("unavailable"));
            case "exception" -> when(materials.productionMeasure("P1")).thenThrow(new IllegalStateException("offline"));
            case "identity" -> measure.setMaterialId(null);
            case "unknownState" -> measure.setEnabled(null);
        }
        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));
        assertPristine();
    }

    @ParameterizedTest
    @ValueSource(strings = {"noTenant", "tenantSkip", "noFactory", "factorySkip", "noActor", "otherTenantActor", "unauthenticated"})
    void approvalRequiresAnActualTenantFactoryAndReviewer(String problem) {
        switch (problem) {
            case "noTenant" -> TenantContextHolder.clear();
            case "tenantSkip" -> TenantContextHolder.setTenantSkip();
            case "noFactory" -> FactoryContextHolder.clear();
            case "factorySkip" -> FactoryContextHolder.setFactorySkip();
            case "noActor" -> SecurityContextHolder.clearContext();
            case "otherTenantActor" -> login("reviewer", 2L);
            case "unauthenticated" -> SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        }
        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));
        assertPristine();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseFactory", "caseIssue", "caseProduct", "sourceNo", "product"})
    void mismatchedSupplementCaseOrSourceCannotCreateNewSupply(String problem) {
        switch (problem) {
            case "caseFactory" -> db.update("update pln_quality_replan_case set factory_code='F2' where id=21");
            case "caseIssue" -> db.update("update pln_quality_replan_case set issue_id=999 where id=21");
            case "caseProduct" -> db.update("update pln_quality_replan_case set product_code='OTHER' where id=21");
            case "sourceNo" -> db.update("update pln_quality_supplement_demand set source_issue_no='OTHER' where id=31");
            case "product" -> db.update("update pln_quality_supplement_demand set product_code='OTHER' where id=31");
        }
        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));
        assertPristine();
    }

    @Test
    void applicantCannotReviewTheirOwnRequest() {
        login("applicant", 1L);
        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));
        assertPristine();
    }

    @Test
    void mrpFailurePreservesApprovedDemandAndSamePlanRequestCanRetry() {
        service.reviewSupplement(command);
        failMrp = true;
        assertThrows(CheckedException.class, () -> service.planSupplement(planCommand()));
        assertEquals("DEMAND_CREATED", supplements.selectById(31L).getStatus());
        assertEquals("1", demandRows.selectById(201L).getPlanState());
        assertEquals(0, count("plan_mat_mrp"));
        assertEquals(1, count("pln_issue"));
        assertEquals(1, count("pln_quality_replan_operation"));
        failMrp = false;
        assertEquals("MRP_GENERATED", service.planSupplement(planCommand()).getStatus());
        assertEquals("MRP_GENERATED", service.planSupplement(planCommand()).getStatus());
        assertEquals(2, mrpCalls);
        assertEquals(1, supplementDemandCount());
        assertEquals(2, count("pln_issue"));
    }

    @Test
    void auditFailureRollsBackDemandMrpIssueAndApproval() {
        failAudit = true;
        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));
        assertPristine();
    }

    @Test
    void changedScrapEvidenceMarksRequestStaleWithoutCreatingSupply() {
        db.update("update pln_issue set scrap_num=1 where id=11");
        assertEquals("STALE", service.reviewSupplement(command).getStatus());
        assertEquals(0, supplementDemandCount());
        assertEquals(0, mrpCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed", "missingUnit", "missingDemand", "duplicateDemand", "missingSource"})
    void unprovableOrChangedRootProductUnitCannotReinterpretHistoricalScrap(String problem) {
        switch (problem) {
            case "changed" -> db.update("update pln_product_demand set product_unit='pc' where id=101");
            case "missingUnit" -> db.update("update pln_product_demand set product_unit=null where id=101");
            case "missingDemand" -> db.update("delete from pln_product_demand where id=101");
            case "duplicateDemand" -> db.update("insert into pln_product_demand(id,tenant_id,factory_code,del_flag,"
                    + "demand_no,product_code,product_unit) values(102,1,'F1','0','OLD-DEMAND','P1','kg')");
            case "missingSource" -> db.update("update pln_issue set source_demand_no=null where id=11");
        }

        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));

        assertPristine();
        assertEquals(0, mrpCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "paths", "superseded", "externalSource"})
    void componentUsesItsOwnVerifiedSourceUnitInsteadOfTheRootProductUnit(String shape) {
        db.update("update pln_product_demand set product_code='ROOT',product_unit='pc' where id=101");
        sourceContract(401L, 1L, "F1", "P1", "kg", "VERIFIED", "superseded".equals(shape) ? "SUPERSEDED" : "PUBLISHED");
        if ("paths".equals(shape)) sourceContract(402L, 1L, "F1", "P1", "kg", "VERIFIED", "PUBLISHED");
        if ("externalSource".equals(shape)) db.update("delete from pln_product_demand where id=101");

        var result = approveAndPlan();

        assertEquals("kg", demandRows.selectById(result.getProductDemandId()).getProductUnit());
        assertEquals("SUP-DEMAND-201", issueRows.selectById(result.getSupplementIssueId()).getSourceDemandNo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "mixed", "missingUnit", "wrongProduct", "wrongFactory", "wrongTenant", "notVerified",
            "deletedConflict", "deletedMissingUnit", "wrongDemand"})
    void componentCannotBorrowARootUnrelatedOrAmbiguousContractUnit(String problem) {
        db.update("update pln_product_demand set product_code='ROOT',product_unit='kg' where id=101");
        switch (problem) {
            case "mixed" -> {
                sourceContract(401L, 1L, "F1", "P1", "kg", "VERIFIED", "PUBLISHED");
                sourceContract(402L, 1L, "F1", "P1", "pc", "VERIFIED", "SUPERSEDED");
            }
            case "missingUnit" -> sourceContract(401L, 1L, "F1", "P1", null, "VERIFIED", "PUBLISHED");
            case "wrongProduct" -> sourceContract(401L, 1L, "F1", "OTHER", "kg", "VERIFIED", "PUBLISHED");
            case "wrongFactory" -> sourceContract(401L, 1L, "F2", "P1", "kg", "VERIFIED", "PUBLISHED");
            case "wrongTenant" -> sourceContract(401L, 2L, "F1", "P1", "kg", "VERIFIED", "PUBLISHED");
            case "notVerified" -> sourceContract(401L, 1L, "F1", "P1", "kg", "PENDING", "PUBLISHED");
            case "deletedConflict", "deletedMissingUnit" -> {
                sourceContract(401L, 1L, "F1", "P1", "kg", "VERIFIED", "PUBLISHED");
                sourceContract(402L, 1L, "F1", "P1", "deletedConflict".equals(problem) ? "pc" : null,
                        "VERIFIED", "SUPERSEDED");
                db.update("update pln_formal_plan_contract set del_flag='1' where id=402");
            }
            case "wrongDemand" -> {
                sourceContract(401L, 1L, "F1", "P1", "kg", "VERIFIED", "PUBLISHED");
                db.update("update pln_formal_plan_contract set source_demand_no='OTHER' where id=401");
            }
        }

        assertThrows(CheckedException.class, () -> service.reviewSupplement(command));

        assertPristine();
        assertEquals(0, mrpCalls);
    }

    private void sourceContract(Long id, Long tenant, String factory, String product, String unit,
                                String verification, String status) {
        db.update("insert into pln_formal_plan_contract(id,tenant_id,factory_code,del_flag,source_demand_no,"
                + "product_code,supply_type,unit_code,verification_status,contract_status,contract_no,contract_version,line_no) "
                + "values(?,?,?,'0','OLD-DEMAND',?,'MAKE',?,?,?,'OLD-CONTRACT',1,?)",
                id, tenant, factory, product, unit, verification, status, "PATH-" + id);
    }

    @Test
    void enforcedMpsKeepsApprovalAndRequiresNormalSeparatePublishedVersion() {
        setupMps();
        service.reviewSupplement(command);
        assertThrows(CheckedException.class, () -> service.planSupplement(planCommand()));
        assertEquals("DEMAND_CREATED", supplements.selectById(31L).getStatus());
        assertEquals("1", demandRows.selectById(201L).getPlanState());
        var version = submitMps("MPS-FIRST");
        assertThrows(CheckedException.class, () -> service.planSupplement(planCommand()));
        approvePublish(version);
        assertEquals(1, count("pln_issue"), "MPS发布不提前创建补产根计划");
        var result = service.planSupplement(planCommand());
        assertEquals("MRP_GENERATED", result.getStatus());
        assertEquals(2, result.getProductionPlans().size());
        assertEquals(2, result.getMrpLineCount());
        assertEquals(0, BigDecimal.ONE.compareTo(result.getProductionPlans().stream()
                .map(com.kitex.mom.plan.dto.PlanQualitySupplementProgressDTO.RootPlan::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, new BigDecimal("2").compareTo(issueRows.selectById(11L).getScrapNum()));
        assertEquals(result.getProductDemandId(), service.reviewSupplement(command).getProductDemandId());
    }

    @Test
    void mpsRevisionPublishesInTheSameTransactionBeforeCallingTheRealMrpGate() {
        setupMps();
        service.reviewSupplement(command);
        approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var revision = submitMps("MPS-SECOND");
        approvePublish(revision);
        assertEquals("PUBLISHED", sessions.getMapper(PlnMpsVersionMapper.class).selectById(revision.getId()).getStatus());
        assertEquals("2", demandRows.selectById(201L).getPlanState());
        var roots = progress.ownedRoots(supplements.selectById(31L), progress.mrpRows(supplements.selectById(31L)));
        assertEquals(2, roots.size());
        assertEquals(2, db.queryForObject("select count(*) from pln_issue where source_demand_no='SUP-DEMAND-201' and status='4'", Integer.class));
        assertEquals(2, releaseKeys.size());
    }

    @Test
    void failedMpsRecalculationRetainsOldBucketsAndCanRetryTheSameVersion() {
        setupMps();
        service.reviewSupplement(command);
        approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var before = progress.ownedRoots(supplements.selectById(31L), progress.mrpRows(supplements.selectById(31L)))
                .stream().map(PlnIssueEntity::getId).toList();
        var revision = submitMps("MPS-SECOND");
        approveMps(revision);
        failMrp = true;
        assertEquals("FAILED", mpsExecutor.execute(revision.getId()).getStatus());
        assertEquals(before, progress.ownedRoots(supplements.selectById(31L), progress.mrpRows(supplements.selectById(31L)))
                .stream().map(PlnIssueEntity::getId).toList());
        failMrp = false;
        mpsService.retry(new PlnMpsCommand.Retry().setVersionId(revision.getId()).setRequestId("MPS-RETRY").setReason("端口恢复"));
        assertEquals("PUBLISHED", mpsExecutor.execute(revision.getId()).getStatus());
        assertEquals(4, releaseKeys.size());
        assertEquals(releaseKeys.subList(0, 2), releaseKeys.subList(2, 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "quantity", "key", "product", "deleted", "oldActive", "extraRoot"})
    void mpsCompletionCannotBorrowMissingDuplicateOrDriftedRoots(String problem) {
        setupMps();
        service.reviewSupplement(command);
        approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var supplement = supplements.selectById(31L);
        var roots = progress.ownedRoots(supplement, progress.mrpRows(supplement));
        var first = roots.get(0);
        switch (problem) {
            case "missing" -> db.update("delete from pln_issue where id=?", first.getId());
            case "quantity" -> db.update("update pln_issue set num=2 where id=?", first.getId());
            case "key" -> db.update("update pln_issue set planning_source_key='OLD' where id=?", first.getId());
            case "product" -> db.update("update pln_issue set product_code='OTHER' where id=?", first.getId());
            case "deleted" -> db.update("update pln_issue set del_flag='1' where id=?", first.getId());
            case "oldActive" -> db.update("update pln_issue set mps_line_id=99 where id=?", first.getId());
            case "duplicate", "extraRoot" -> {
                var copy = new PlnIssueEntity();
                org.springframework.beans.BeanUtils.copyProperties(first, copy);
                copy.setId(777L);
                if (problem.equals("extraRoot")) copy.setMpsLineId(null);
                issueRows.insert(copy);
            }
        }
        db.update("update pln_issue set finish_num=num where source_demand_no='SUP-DEMAND-201'");
        assertThrows(CheckedException.class, () -> progress.ownedRoots(supplement, progress.mrpRows(supplement)), problem);
        assertEquals("SUP-DEMAND-201", roots.get(1).getSourceDemandNo(), "raw source");
        assertEquals("P1", roots.get(1).getProductCode(), "raw product");
        assertNotNull(roots.get(1).getId(), "raw id");
        assertThrows(CheckedException.class, () -> service.captureCallback(callback("FINISH-1"), roots.get(1)), problem);
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
    }

    @Test
    void completionAggregatesAllBucketsAndOnlyClosesAfterTheLastOne() {
        setupMps();
        service.reviewSupplement(command);
        approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var supplement = supplements.selectById(31L);
        var roots = progress.ownedRoots(supplement, progress.mrpRows(supplement));
        db.update("update pln_issue set finish_num=num,status='3' where id=?", roots.get(1).getId());
        service.captureCallback(callback("FINISH-1"), issueRows.selectById(roots.get(1).getId()));
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
        db.update("update pln_issue set finish_num=num,status='3' where id=?", roots.get(0).getId());
        service.captureCallback(callback("FINISH-2"), issueRows.selectById(roots.get(0).getId()));
        assertEquals("FULFILLED", supplements.selectById(31L).getStatus());
        service.captureCallback(callback("FINISH-2"), issueRows.selectById(roots.get(0).getId()));
        assertEquals(1, db.queryForObject("select count(*) from pln_quality_replan_operation where operation_type='SUPPLEMENT_FULFILL'", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"beforeMps", "afterMps", "afterMrp", "normalMrp"})
    void stagedWithdrawalClosesTheWholeOwnedDemandAndIsPermanentlyReplayable(String phase) {
        setupMps();
        service.reviewSupplement(command);
        if (phase.equals("normalMrp")) db.update("update pln_mps_policy set enforce_mps=false where id=801");
        else if (!phase.equals("beforeMps")) approvePublish(submitMps("MPS-FIRST"));
        if (List.of("afterMrp", "normalMrp").contains(phase)) service.planSupplement(planCommand());
        var request = withdrawalCommand();
        var submitted = withdrawalService.submit(request);
        assertEquals(submitted.getId(), withdrawalService.submit(request).getId());
        var blocked = planCommand(); blocked.setRequestId("QSUP-PLAN-DURING-WITHDRAWAL");
        assertThrows(CheckedException.class, () -> service.planSupplement(blocked));
        if (List.of("afterMrp", "normalMrp").contains(phase)) assertEquals("MRP_GENERATED", service.planSupplement(planCommand()).getStatus());
        login("withdraw-reviewer", 1L);
        var result = withdrawalService.review(withdrawalReview(submitted));
        assertEquals("SUCCEEDED", result.getStatus(), result.getLastError());
        assertEquals("WITHDRAWN", supplements.selectById(31L).getStatus());
        assertEquals("3", demandRows.selectById(201L).getPlanState());
        assertEquals(0, db.queryForObject("select count(*) from pln_mps_line where active=true", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from pln_issue where source_demand_no='SUP-DEMAND-201' and status<>'4'", Integer.class));
        assertEquals(result.getId(), withdrawalService.review(withdrawalReview(submitted)).getId());
        assertEquals(result.getId(), withdrawalService.submit(request).getId());
        assertEquals(0, new BigDecimal("2").compareTo(issueRows.selectById(11L).getScrapNum()));
    }

    @Test
    void withdrawalFailureKeepsCoverageAndRetriesWithTheOriginalRemoteKeys() {
        setupMps();
        service.reviewSupplement(command);
        approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var submitted = withdrawalService.submit(withdrawalCommand());
        login("withdraw-reviewer", 1L);
        failRelease = true;
        var failed = withdrawalService.review(withdrawalReview(submitted));
        assertEquals("FAILED", failed.getStatus());
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
        assertEquals("2", demandRows.selectById(201L).getPlanState());
        assertEquals(2, progress.ownedRoots(supplements.selectById(31L), progress.mrpRows(supplements.selectById(31L))).size());
        failRelease = false;
        var retry = new PlanQualityLocalReplanDTO.SupplementWithdrawalRetryCommand();
        retry.setWithdrawalId(submitted.getId()); retry.setRequestId("QSW-RETRY"); retry.setReason("预留端口恢复");
        assertEquals("SUCCEEDED", withdrawalService.retry(retry).getStatus());
        assertEquals(releaseKeys.get(0), releaseKeys.get(1));
    }

    @Test
    void pendingMpsCannotBeSilentlyCancelledBySupplementWithdrawal() {
        setupMps();
        service.reviewSupplement(command);
        submitMps("MPS-PENDING");
        assertThrows(CheckedException.class, () -> withdrawalService.submit(withdrawalCommand()));
        assertEquals(0, count("pln_quality_supplement_withdrawal"));
        assertEquals("DEMAND_CREATED", supplements.selectById(31L).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "mps"})
    void normalMrpCalculationProjectsSupplementOnlyAfterContractPublicationAndRollsBackOnFailure(String mode) throws Exception {
        setupMps();
        service.reviewSupplement(command);
        if (mode.equals("mps")) approvePublish(submitMps("MPS-ACTUAL-MRP"));
        else db.update("update pln_mps_policy set enforce_mps=false where id=801");
        var contracts = mock(PlnFormalPlanContractService.class);
        var failContract = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(contracts.publishMomMrpContract(anyString(), anyString())).thenAnswer(call -> {
            assertEquals("DEMAND_CREATED", supplements.selectById(31L).getStatus());
            assertEquals(1, count("pln_issue"));
            assertTrue(count("plan_mat_mrp") > 0);
            if (failContract.get()) throw new CheckedException("正式合同端口失败");
            return List.of();
        });
        var actual = actualMrp(contracts);
        ReflectionTestUtils.setField(target, "productDemandService", actual);
        assertThrows(CheckedException.class, () -> service.planSupplement(planCommand()));
        assertEquals("DEMAND_CREATED", supplements.selectById(31L).getStatus());
        assertEquals("1", demandRows.selectById(201L).getPlanState());
        assertEquals(0, count("plan_mat_mrp"));
        assertEquals(1, count("pln_issue"));
        failContract.set(false);
        var result = service.planSupplement(planCommand());
        assertEquals("MRP_GENERATED", result.getStatus());
        assertEquals(mode.equals("mps") ? 2 : 1, result.getProductionPlans().size());
        assertEquals(mode.equals("mps") ? 4 : 2, count("plan_mat_mrp"));
        assertEquals("2", demandRows.selectById(201L).getPlanState());
        verify(contracts, times(2)).publishMomMrpContract(anyString(), anyString());
    }

    /** 实际公开MRP入口及BOM净算；合同发布、仓库/物料/库存仍是受控端口。 */
    private PlnProductDemandService actualMrp(PlnFormalPlanContractService contracts) throws Exception {
        var constructor = PlnProductDemandServiceImpl.class.getConstructors()[0];
        var actual = (PlnProductDemandServiceImpl) constructor.newInstance(new Object[constructor.getParameterCount()]);
        ReflectionTestUtils.setField(actual, "baseMapper", demandRows);
        var warehouses = mock(RemoteWarehouseService.class);
        var warehouse = new MdmWarehouseEntity(); warehouse.setWarehouseCode("SUP-WH"); warehouse.setWarehouseName("补产仓");
        warehouse.setStatus(true); warehouse.setFactoryCode("F1");
        when(warehouses.getByCode("SUP-WH")).thenReturn(R.ok(warehouse));
        var mes = mock(RemoteMesTaskService.class);
        when(mes.omsLife(anyString())).thenReturn(R.ok(List.of()));
        var issues = mock(PlnIssueService.class);
        when(issues.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(call -> issueRows.selectList(call.getArgument(0)));
        var bomService = mock(RemoteBomService.class);
        var root = new MdmBomChildDTO(); root.setId(61L); root.setBomId(71L); root.setMaterialCode("P1"); root.setQuantity(BigDecimal.ONE);
        var child = new MdmBomChildDTO(); child.setId(62L); child.setBomId(71L); child.setMaterialCode("M1"); child.setQuantity(new BigDecimal("2"));
        root.setChildren(List.of(child));
        when(bomService.getAllChildMaterials(anyList())).thenReturn(R.ok(Map.of("P1", root)));
        var product = new MdmMaterialEntity(); product.setMaterialCode("P1"); product.setBasicUnit("kg"); product.setMaterialAttribute("mes");
        var material = new MdmMaterialEntity(); material.setMaterialCode("M1"); material.setBasicUnit("kg"); material.setMaterialAttribute("purchase");
        when(materials.getListByCodes(anyList())).thenReturn(R.ok(List.of(product, material)));
        var stock = mock(RemoteLiveStockService.class);
        when(stock.getLiveStockByProductCodeList(any())).thenReturn(R.ok(List.of()));
        var gate = mock(PlnFormalPlanningGate.class);
        var policy = new MdmFactoryPlanningPolicyDTO(); policy.setFactoryCode("F1");
        when(gate.requireMomMrp("F1")).thenReturn(policy);
        when(mrp.saveBatch(anyCollection())).thenAnswer(call -> {
            java.util.Collection<PlanMatMrpEntity> rows = call.getArgument(0);
            rows.forEach(mrpRows::insert); return true;
        });
        Map<String,Object> ports = Map.ofEntries(Map.entry("remoteWarehouseService", warehouses),
                Map.entry("remoteMesTaskService", mes), Map.entry("plnIssueService", issues),
                Map.entry("remoteBomService", bomService), Map.entry("remoteMaterialService", materials),
                Map.entry("remoteLiveStockService", stock), Map.entry("planMatMrpService", mrp),
                Map.entry("inventoryPeggingService", mock(PlanMrpInventoryPeggingService.class)),
                Map.entry("batchAllocationMapper", sessions.getMapper(PlnSchedulingBatchAllocationMapper.class)),
                Map.entry("schedulingBatchMapper", sessions.getMapper(PlnSchedulingBatchMapper.class)),
                Map.entry("schedulingTaskDetailMapper", sessions.getMapper(PlnSchedulingTaskDetailMapper.class)),
                Map.entry("schedulingOperationMapper", sessions.getMapper(PlnSchedulingOperationMapper.class)),
                Map.entry("demandChangeMapper", sessions.getMapper(PlnProductDemandChangeMapper.class)),
                Map.entry("mpsService", mpsService), Map.entry("formalPlanningGate", gate),
                Map.entry("formalPlanContractService", contracts), Map.entry("supplementPlanning", projector),
                Map.entry("publicationLock", mock(PlnMpsCapacityPublicationLock.class)));
        ports.forEach((name, port) -> ReflectionTestUtils.setField(actual, name, port));
        return txProxy(actual);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "unit", "factory", "product", "warehouse", "date", "sourceId", "sourceNo", "evidence", "scrap", "mdmUnit"})
    void continuedPlanningRejectsDriftWithoutUndoingTheApprovedDemand(String problem) {
        service.reviewSupplement(command);
        switch (problem) {
            case "quantity" -> db.update("update pln_product_demand set need_num=2 where id=201");
            case "unit" -> db.update("update pln_product_demand set product_unit='pc' where id=201");
            case "factory" -> db.update("update pln_product_demand set factory_code='F2' where id=201");
            case "product" -> db.update("update pln_product_demand set product_code='OTHER' where id=201");
            case "warehouse" -> db.update("update pln_product_demand set demand_warehouse_code='OTHER' where id=201");
            case "date" -> db.update("update pln_product_demand set plan_end_date='2026-12-01' where id=201");
            case "sourceId" -> db.update("update pln_product_demand set source_id=32 where id=201");
            case "sourceNo" -> db.update("update pln_product_demand set source_no='OTHER' where id=201");
            case "evidence" -> db.update("update pln_product_demand set source_evidence_fingerprint='OTHER' where id=201");
            case "scrap" -> db.update("update pln_issue set scrap_num=1 where id=11");
            case "mdmUnit" -> measure.setBasicUnit("pc");
        }
        assertThrows(CheckedException.class, () -> service.planSupplement(planCommand()));
        assertEquals("DEMAND_CREATED", supplements.selectById(31L).getStatus());
        assertEquals("1", db.queryForObject("select plan_state from pln_product_demand where id=201", String.class));
        assertEquals(0, count("plan_mat_mrp"));
        assertEquals(1, count("pln_issue"));
    }

    @Test
    void approvedWaitingDemandAlreadyReservesItsShareOfTheScrapGap() {
        service.reviewSupplement(command);
        var other = new PlnQualitySupplementDemandEntity();
        org.springframework.beans.BeanUtils.copyProperties(supplements.selectById(31L), other);
        other.setId(32L); other.setSupplementNo("SUP-32").setRequestFingerprint("b".repeat(64))
                .setRequestQuantity(new BigDecimal("2")).setStatus("SUBMITTED").setPlanningModelVersion(null)
                .setProductDemandId(null).setProductDemandNo(null).setReviewRequestId(null)
                .setReviewerCode(null).setReviewedTime(null).setReviewReason(null);
        supplements.insert(other);
        command.setSupplementId(32L); command.setRequestId("REVIEW-32"); command.setExpectedRequestFingerprint("b".repeat(64));
        assertEquals("STALE", service.reviewSupplement(command).getStatus());
        assertEquals(1, supplementDemandCount());
        assertEquals(0, mrpCalls);
    }

    @Test
    void bulkProgressUsesTheWholeOwnedSetAndDoesNotBorrowAnotherFactory() {
        setupMps(); service.reviewSupplement(command); approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var supplement = supplements.selectById(31L);
        var roots = progress.ownedRoots(supplement, progress.mrpRows(supplement));
        db.update("update pln_issue set finish_num=num,status='3' where id=?", roots.get(0).getId());
        db.update("insert into pln_issue(id,tenant_id,factory_code,del_flag,issue_no,source_demand_no,num,finish_num,status) "
                + "values(777,1,'F2','0','OTHER-ROOT','SUP-DEMAND-201',99,99,'3')");
        progress.enrichAll(List.of(supplement));
        assertEquals(2, supplement.getProductionPlans().size());
        assertEquals(0, new BigDecimal("0.4").compareTo(supplement.getCompletedQuantity()));
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus(), "读取进度不推进审批状态");
        db.update("update pln_issue set num=99 where id=?", roots.get(1).getId());
        progress.enrichAll(List.of(supplement));
        assertEquals("SOURCE_CONFLICT", supplement.getDownstreamStatus());
        assertEquals(0, supplement.getProductionPlans().size());
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
    }

    @Test
    void withdrawalStillRequiresSeparateReviewAndFrozenFacts() {
        setupMps(); service.reviewSupplement(command); approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var submitted = withdrawalService.submit(withdrawalCommand());
        assertThrows(CheckedException.class, () -> withdrawalService.review(withdrawalReview(submitted)));
        assertEquals("SUBMITTED", sessions.getMapper(PlnQualitySupplementWithdrawalMapper.class).selectById(submitted.getId()).getStatus());
        login("withdraw-reviewer", 1L);
        db.update("update pln_product_demand set plan_end_date='2026-12-01' where id=201");
        assertThrows(CheckedException.class, () -> withdrawalService.review(withdrawalReview(submitted)));
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
        assertEquals("2", demandRows.selectById(201L).getPlanState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherFactory", "noFactory", "tenantSkip", "noActor", "otherFactoryLegacy", "noFactoryLegacy", "tenantSkipLegacy", "noActorLegacy"})
    void stagedWithdrawalReplayNeverLeaksAnOutOfScopeReceipt(String problem) {
        setupMps(); service.reviewSupplement(command);
        if (problem.endsWith("Legacy")) {
            db.update("update pln_mps_policy set enforce_mps=false where id=801");
            service.planSupplement(planCommand());
            problem = problem.replace("Legacy", "");
        }
        withdrawalService.submit(withdrawalCommand());
        switch (problem) {
            case "otherFactory" -> FactoryContextHolder.setFactoryCode("F2");
            case "noFactory" -> FactoryContextHolder.clear();
            case "tenantSkip" -> TenantContextHolder.setTenantSkip();
            case "noActor" -> SecurityContextHolder.clearContext();
        }
        assertThrows(CheckedException.class, () -> withdrawalService.submit(withdrawalCommand()));
        assertEquals(1, count("pln_quality_supplement_withdrawal"));
    }

    @Test
    void ordinaryInternalDemandMrpDoesNotAddAReversePublicationLockToExistingChangeTransactions() throws Exception {
        setupMps(); service.reviewSupplement(command);
        db.update("update pln_mps_policy set enforce_mps=false where id=801");
        db.update("update pln_product_demand set source_type=null,source_id=null,source_no=null,source_evidence_fingerprint=null where id=201");
        var actual = actualMrp(mock(PlnFormalPlanContractService.class));
        assertTrue(actual.mrp(new Long[]{201L}).isOk());
        var lock = (PlnMpsCapacityPublicationLock) ReflectionTestUtils.getField(actual, "publicationLock");
        verify(lock, never()).acquire(anyString());
        assertEquals("2", demandRows.selectById(201L).getPlanState());
        assertEquals(1, count("pln_issue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherSource", "quantity", "duplicate", "wrongKey"})
    void normalSupplementWithdrawalRequiresTheCompleteOwnedSingleRoot(String problem) {
        setupMps(); service.reviewSupplement(command);
        db.update("update pln_mps_policy set enforce_mps=false where id=801");
        var planned = service.planSupplement(planCommand());
        switch (problem) {
            case "otherSource" -> db.update("update pln_issue set source_demand_no='OTHER' where id=?", planned.getSupplementIssueId());
            case "quantity" -> db.update("update pln_issue set num=2 where id=?", planned.getSupplementIssueId());
            case "wrongKey" -> db.update("update pln_issue set planning_source_key='OLD' where id=?", planned.getSupplementIssueId());
            case "duplicate" -> {
                var duplicate = issueRows.selectById(planned.getSupplementIssueId());
                duplicate.setId(777L); duplicate.setIssueNo("OTHER-ROOT"); issueRows.insert(duplicate);
            }
        }
        assertThrows(CheckedException.class, () -> withdrawalService.submit(withdrawalCommand()));
        assertEquals(0, count("pln_quality_supplement_withdrawal"));
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
        assertEquals("2", demandRows.selectById(201L).getPlanState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "missing", "wrongKey", "orphanVersion"})
    void normalSupplementCannotFulfillWithoutItsOwnMrpEvidence(String problem) {
        setupMps(); service.reviewSupplement(command);
        db.update("update pln_mps_policy set enforce_mps=false where id=801");
        var planned = service.planSupplement(planCommand());
        switch (problem) {
            case "deleted" -> db.update("update plan_mat_mrp set del_flag='1' where plan_demand_no='SUP-DEMAND-201'");
            case "missing" -> db.update("delete from plan_mat_mrp where plan_demand_no='SUP-DEMAND-201'");
            case "wrongKey" -> db.update("update plan_mat_mrp set planning_source_key='OTHER' where plan_demand_no='SUP-DEMAND-201'");
            case "orphanVersion" -> db.update("update plan_mat_mrp set mps_version_id=99 where plan_demand_no='SUP-DEMAND-201'");
        }
        db.update("update pln_issue set finish_num=num,status='3' where id=?", planned.getSupplementIssueId());
        assertThrows(CheckedException.class, () -> service.captureCallback(callback("FINISH-MRP-MISSING"), issueRows.selectById(planned.getSupplementIssueId())));
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
        assertEquals(0, db.queryForObject("select count(*) from pln_quality_replan_operation where operation_type='SUPPLEMENT_FULFILL'", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mrp", "roots"})
    void coordinatedWithdrawalCannotFreezeOnlyAVisibleSubsetOfTheOwnedFacts(String hidden) {
        setupMps(); service.reviewSupplement(command); approvePublish(submitMps("MPS-FIRST"));
        service.planSupplement(planCommand());
        var coordinator = (PlnDemandReplanCoordinator) ReflectionTestUtils.getField(withdrawalService, "replanCoordinator");
        var partial = spy(coordinator);
        doAnswer(call -> {
            var evidence = (PlnDemandReplanEvidenceDTO.Evidence) call.callRealMethod();
            if (hidden.equals("mrp")) evidence.setMrpIds(List.of(evidence.getMrpIds().get(0)));
            else evidence.setIssues(List.of(evidence.getIssues().get(0)));
            return evidence;
        }).when(partial).preview(anyLong(), org.mockito.ArgumentMatchers.<Long>anyList());
        ReflectionTestUtils.setField(withdrawalService, "replanCoordinator", partial);
        assertThrows(CheckedException.class, () -> withdrawalService.submit(withdrawalCommand()));
        assertEquals(0, count("pln_quality_supplement_withdrawal"));
        assertEquals("MRP_GENERATED", supplements.selectById(31L).getStatus());
    }

    @Test
    void legacyGeneratedSupplementKeepsItsOriginalWithdrawalSnapshotProtocol() {
        setupMps(); service.reviewSupplement(command);
        db.update("update pln_mps_policy set enforce_mps=false where id=801");
        service.planSupplement(planCommand());
        db.update("update pln_quality_supplement_demand set planning_model_version=null where id=31");
        var submitted = withdrawalService.submit(withdrawalCommand());
        assertNull(submitted.getPlanningModelVersion());
        assertFalse(submitted.getEvidenceSnapshot().contains("sourcePlanningModelVersion"));
        assertFalse(submitted.getEvidenceSnapshot().contains("qualityPlanningModel"));
        assertEquals(submitted.getEvidenceFingerprint(), withdrawalService.submit(withdrawalCommand()).getEvidenceFingerprint());
        login("withdraw-reviewer", 1L);
        var result = withdrawalService.review(withdrawalReview(submitted));
        assertEquals("SUCCEEDED", result.getStatus(), result.getLastError());
        assertEquals("WITHDRAWN", supplements.selectById(31L).getStatus());
        assertEquals("3", demandRows.selectById(201L).getPlanState());
    }

    private PlnIssueCallbackReceiptEntity callback(String request) {
        var receipt = new PlnIssueCallbackReceiptEntity();
        receipt.setId(911L); receipt.setRequestId(request);
        return receipt;
    }

    private PlanQualityLocalReplanDTO.SupplementWithdrawalSubmitCommand withdrawalCommand() {
        var command = new PlanQualityLocalReplanDTO.SupplementWithdrawalSubmitCommand();
        command.setSupplementId(31L); command.setRequestId("QSW-SUBMIT"); command.setReason("取消联验补产");
        return command;
    }

    private PlanQualityLocalReplanDTO.SupplementWithdrawalReviewCommand withdrawalReview(PlnQualitySupplementWithdrawalEntity submitted) {
        var command = new PlanQualityLocalReplanDTO.SupplementWithdrawalReviewCommand();
        command.setWithdrawalId(submitted.getId()); command.setRequestId("QSW-APPROVE");
        command.setApproved(true); command.setExpectedEvidenceFingerprint(submitted.getEvidenceFingerprint()); command.setReason("异岗核验撤回");
        return command;
    }

    private PlnMpsVersionEntity submitMps(String request) {
        return mpsService.submit(new PlnMpsCommand.Submit().setFactoryCode("F1").setRequestId(request)
                .setHorizonStart(LocalDate.now()).setHorizonEnd(LocalDate.now().plusDays(20)).setReason("分阶段联验")
                .setLines(List.of(new PlnMpsCommand.Line().setDemandId(201L).setBucketNo(1)
                                .setPlanQuantity(new BigDecimal("0.4")).setPlannedDate(LocalDate.now().plusDays(7)).setMrpPriority(900),
                        new PlnMpsCommand.Line().setDemandId(201L).setBucketNo(2)
                                .setPlanQuantity(new BigDecimal("0.6")).setPlannedDate(LocalDate.now().plusDays(8)).setMrpPriority(900))));
    }

    private void approveMps(PlnMpsVersionEntity version) {
        login("mps-reviewer-" + version.getId(), 1L);
        mpsService.review(new PlnMpsCommand.Review().setVersionId(version.getId()).setRequestId("REVIEW-" + version.getId())
                .setEvidenceFingerprint(version.getEvidenceFingerprint()).setApproved(true).setReason("异岗复核"));
    }

    private void approvePublish(PlnMpsVersionEntity version) {
        approveMps(version);
        var result = mpsExecutor.execute(version.getId());
        assertEquals("PUBLISHED", result.getStatus(), result.getLastError());
    }

    /** 实际MPS提交/复核/发布/门禁及通用撤回协调器；MRP计算、MDM、RCCP、批量和库存端口仍受控。 */
    private void setupMps() {
        var lock = mock(PlnMpsCapacityPublicationLock.class);
        var coordinator = new PlnDemandReplanCoordinator(demandRows, sessions.getMapper(PlnProductDemandChangeMapper.class),
                mrpRows, issueRows, sessions.getMapper(PlnSchedulingOperationMapper.class),
                sessions.getMapper(PlnSchedulingTaskDetailMapper.class), sessions.getMapper(PlnSchedulingBatchAllocationMapper.class),
                sessions.getMapper(PlnSchedulingBatchMapper.class), mrp, mock(RemoteMesTaskService.class), new ObjectMapper().findAndRegisterModules());
        var warehouses = mock(RemoteWarehouseService.class);
        var warehouse = new MdmWarehouseEntity(); warehouse.setStatus(true); warehouse.setFactoryCode("F1");
        when(warehouses.getByCode(anyString())).thenReturn(R.ok(warehouse));
        var material = new MdmMaterialEntity(); material.setStatus(true); material.setFactoryCode("F1");
        when(materials.getDetails("P1")).thenReturn(R.ok(material));
        var access = mock(RemoteMdmFactoryAccessService.class);
        when(access.check(any())).thenReturn(R.ok(true));
        var lots = mock(PlnMpsLotSizingService.class);
        when(lots.validate(anyString(), anyList())).thenReturn(new PlnMpsLotSizingService.Catalog(Map.of(), List.of("P1"), "b".repeat(64)));
        var policies = adapt(PlnMpsPolicyMapper.class, sessions.getMapper(PlnMpsPolicyMapper.class));
        mpsService = txProxy(new PlnMpsService(policies, lock, sessions.getMapper(PlnMpsVersionMapper.class),
                sessions.getMapper(PlnMpsLineMapper.class), sessions.getMapper(PlnMpsOperationMapper.class), demandRows,
                sessions.getMapper(PlnProductDemandChangeMapper.class), mrpRows, coordinator, mock(PlnMpsRccpService.class),
                lots, warehouses, materials, access));
        mpsExecutor = new PlnMpsRevisionExecutor(sessions.getMapper(PlnMpsVersionMapper.class), sessions.getMapper(PlnMpsLineMapper.class),
                demandRows, mrpRows, new PlnMpsRootPlanService(issueRows), projector, coordinator, demands, mpsService, lock, transactions);
        var policy = new PlnMpsPolicyEntity().setFactoryCode("F1").setPolicyVersion(1).setActive(true)
                .setEnforceMps(true).setFreezeDays(0).setHorizonDays(60);
        policy.setId(801L); sessions.getMapper(PlnMpsPolicyMapper.class).insert(policy);
        when(mrp.previewGeneratedSupplyWithdrawal(anyLong())).thenAnswer(call -> supplyEvidence(call.getArgument(0)));
        when(mrp.captureGeneratedSupplyWithdrawalForExecution(anyLong(), anyString()))
                .thenAnswer(call -> supplyEvidence(call.getArgument(0)));
        when(mrp.releaseForWithdrawal(anyLong(), anyString(), anyString())).thenAnswer(call -> {
            releaseKeys.add(call.getArgument(1)); return !failRelease;
        });
        var withdrawal = new PlnQualitySupplementWithdrawalServiceImpl(
                adapt(PlnQualitySupplementWithdrawalMapper.class, sessions.getMapper(PlnQualitySupplementWithdrawalMapper.class)),
                sessions.getMapper(PlnQualitySupplementWithdrawalOperationMapper.class), supplements, demandRows, mrpRows, issueRows,
                sessions.getMapper(PlnSchedulingOperationMapper.class), sessions.getMapper(PlnSchedulingTaskDetailMapper.class),
                sessions.getMapper(PlnSchedulingBatchMapper.class), sessions.getMapper(PlnSchedulingBatchAllocationMapper.class),
                mrp, mock(RemoteMesTaskService.class), new ObjectMapper().findAndRegisterModules(), transactions,
                coordinator, projector, progress, lock, sessions.getMapper(PlnMpsLineMapper.class), mpsService);
        withdrawalService = (PlnQualitySupplementWithdrawalService) txProxy(withdrawal);
    }

    private PlanMrpSupplyWithdrawalDTO.Evidence supplyEvidence(Long id) {
        return new PlanMrpSupplyWithdrawalDTO.Evidence().setMrpId(id).setStatus("NOT_APPLICABLE")
                .setEvidenceFingerprint("c".repeat(64)).setDownstreamEvidenceFingerprint("d".repeat(64));
    }

    @SuppressWarnings("unchecked")
    private <T> T txProxy(T object) {
        var proxy = new ProxyFactory(object);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private PlnQualitySupplementDemandEntity approveAndPlan() {
        service.reviewSupplement(command);
        return service.planSupplement(planCommand());
    }

    private PlanQualityLocalReplanDTO.SupplementPlanCommand planCommand() {
        var cmd = new PlanQualityLocalReplanDTO.SupplementPlanCommand();
        cmd.setSupplementId(31L);
        cmd.setRequestId("SUP-PLAN-1");
        cmd.setExpectedRequestFingerprint("a".repeat(64));
        return cmd;
    }

    private void assertPristine() {
        assertEquals(0, supplementDemandCount());
        assertEquals(0, count("plan_mat_mrp"));
        assertEquals(1, count("pln_issue"));
        assertEquals(0, count("pln_quality_replan_operation"));
        assertEquals("SUBMITTED", db.queryForObject("select status from pln_quality_supplement_demand where id=31", String.class));
    }

    private int count(String table) {
        return db.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int supplementDemandCount() {
        return db.queryForObject("select count(*) from pln_product_demand where source_type='QUALITY_SUPPLEMENT'", Integer.class);
    }

    private void login(String name, Long tenant) {
        var user = new MomUser(41L, name, 1L, null, null, null, name, null, tenant, "unused",
                true, true, "USER", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private <T> T adapt(Class<T> type, T mapper) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("lockKey")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                lockCalls++;
                return method.getReturnType() == void.class ? null : 1;
            }
            if (type == PlnQualityReplanOperationMapper.class && method.getName().equals("insert") && failAudit) return 0;
            try {
                return method.invoke(mapper, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }));
    }

    private void createTable(Class<?> entity) {
        var table = TableInfoHelper.getTableInfo(entity);
        var sql = new StringBuilder("create table ").append(table.getTableName()).append("(id bigint primary key");
        for (var field : table.getFieldList()) {
            var type = field.getPropertyType();
            String sqlType = type == Long.class ? "bigint" : type == Integer.class ? "integer"
                    : type == Boolean.class ? "boolean" : type == BigDecimal.class ? "decimal(24,8)"
                    : type == LocalDate.class ? "date" : type == LocalDateTime.class ? "timestamp" : "varchar(4096)";
            sql.append(',').append(field.getColumn()).append(' ').append(sqlType);
            if (field.getColumn().equals("del_flag")) sql.append(" default '0'");
        }
        db.execute(sql.append(')').toString());
    }
}
