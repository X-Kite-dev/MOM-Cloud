package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.*;
import com.baomidou.mybatisplus.core.metadata.*;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.extension.spring.*;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.feign.*;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 实际本地主表/永久请求查询/回滚，MDM与序号为明确远端端口。 */
class MesManualTaskTransactionTest {
    JdbcTemplate db;
    SqlSessionTemplate session;
    MesManualTaskService service;
    MesTaskMapper taskMapper;
    MesManualTaskMapper facts;
    MesEngineeringPackageService engineering;
    RemoteMdmRouteEngineeringService packages;
    RemoteMdmFactoryPlanningPolicyService policies;
    RemoteMaterialService materials;
    RemoteMdmFactoryAccessService access;
    MdmFactoryPlanningPolicyDTO policy;
    MdmMaterialEntity product;
    MdmRouteEngineeringDTO.ProductionPackage bundle;
    final Fault fault = new Fault();
    int locks;

    @BeforeEach
    void setup() throws Exception {
        identity(7L, "F1");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:manual_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute("CREATE ALIAS mom_mes.mom_manual_task_ready_v1 AS 'Integer ready(){return 1;}'");
        db.execute(
                "CREATE TABLE mom_mes.mes_manual_task_request(id bigint primary key,tenant_id"
                        + " bigint,factory_code varchar,request_id varchar,actor_id"
                        + " bigint,command_payload varchar(64000),command_fingerprint varchar,state"
                        + " varchar,task_id bigint,task_code varchar,created_at timestamp with time"
                        + " zone,UNIQUE(tenant_id,factory_code,request_id))");
        db.execute("CREATE TABLE request_lock(id int primary key)");
        db.update("INSERT INTO request_lock VALUES(1)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(MesTaskMapper.class);
        config.addMapper(MesManualTaskMapper.class);
        config.addInterceptor(fault);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(config, "manual"), MesTaskEntity.class);
        StringBuilder ddl =
                new StringBuilder("CREATE TABLE mom_mes.mes_task(id bigint primary key");
        for (var f : TableInfoHelper.getTableInfo(MesTaskEntity.class).getFieldList()) {
            Class<?> t = f.getPropertyType();
            ddl.append(',')
                    .append(f.getColumn())
                    .append(' ')
                    .append(
                            t == Long.class
                                    ? "bigint"
                                    : t == Integer.class
                                            ? "integer"
                                            : t == BigDecimal.class
                                                    ? "decimal(24,8)"
                                                    : t == LocalDateTime.class
                                                            ? "timestamp"
                                                            : "varchar(2000000)");
            if (f.getColumn().equals("del_flag")) ddl.append(" DEFAULT '0'");
        }
        db.execute(ddl.append(')').toString());
        db.execute("CREATE SYNONYM mes_task FOR mom_mes.mes_task");
        db.execute(
                "CREATE UNIQUE INDEX request_unique ON"
                        + " mom_mes.mes_task(tenant_id,factory_code,source_request_id)");
        var sf = new MybatisSqlSessionFactoryBean();
        sf.setDataSource(ds);
        sf.setConfiguration(config);
        sf.setGlobalConfig(global);
        session = new SqlSessionTemplate(sf.getObject());
        taskMapper = session.getMapper(MesTaskMapper.class);
        var actual = session.getMapper(MesManualTaskMapper.class);
        facts =
                (MesManualTaskMapper)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {MesManualTaskMapper.class},
                                (p, m, args) -> {
                                    if (m.getName().equals("lockRequest")) {
                                        locks++;
                                        return db.queryForObject(
                                                "SELECT id FROM request_lock WHERE id=1 FOR UPDATE",
                                                Integer.class);
                                    }
                                    try {
                                        return m.invoke(actual, args);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        access = mock(RemoteMdmFactoryAccessService.class);
        when(access.check(any())).thenReturn(R.ok(true));
        policies = mock(RemoteMdmFactoryPlanningPolicyService.class);
        policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10");
        policy.setVersionNo(1);
        policy.setFactoryCode("F1");
        policy.setContentFingerprint("a".repeat(64));
        policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        when(policies.active(anyString())).thenAnswer(x -> R.ok(policy));
        materials = mock(RemoteMaterialService.class);
        product = new MdmMaterialEntity();
        product.setMaterialCode("P1");
        product.setMaterialName("正式产品");
        product.setSpecificationModel("SPEC1");
        product.setStatus(true);
        product.setTenantId(1L);
        product.setFactoryCode("F1");
        product.setDelFlag("0");
        when(materials.getDetails(anyString())).thenReturn(R.ok(product));
        packages = mock(RemoteMdmRouteEngineeringService.class);
        engineering = new MesEngineeringPackageService(packages);
        bundle = new MdmRouteEngineeringDTO.ProductionPackage();
        String raw =
                "{\"tenantId\":\"1\",\"factoryCode\":\"F1\",\"route\":{\"id\":\"902\",\"routeCode\":\"R1\",\"productCode\":\"P1\"},\"bom\":{\"id\":\"903\",\"version\":\"B1\"},\"bomStructure\":{\"children\":[]},\"graph\":{\"procedureList\":[{\"routeNodeCode\":\"RN-1\",\"processCode\":\"OP1\",\"type\":\"QUANTITY\",\"preRouteNodeCodes\":[],\"nextRouteNodeCodes\":[]}]},\"nodes\":[{\"routeNodeCode\":\"RN-1\",\"processCode\":\"OP1\",\"works\":[],\"materials\":[],\"molds\":[],\"legacySops\":[]}],\"parameterCatalog\":{},\"sopManifest\":{}}";
        bundle.setTenantId("1");
        bundle.setFactoryCode("F1");
        bundle.setSchemaVersion(MdmRouteEngineeringDTO.SCHEMA_VERSION);
        bundle.setReleaseId(901L);
        bundle.setVersionNo(1);
        bundle.setRouteId(902L);
        bundle.setRouteCode("R1");
        bundle.setProductCode("P1");
        bundle.setBomId(903L);
        bundle.setBomVersion("B1");
        bundle.setBundleSnapshot(raw);
        bundle.setContentHash(
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                raw.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8))));
        when(packages.resolveForProduction(any())).thenReturn(R.ok(bundle));
        when(packages.productionCandidates(anyString())).thenReturn(R.ok(List.of(bundle)));
        var numbers = mock(RemoteSeqConfigService.class);
        var seq = new AtomicInteger();
        when(numbers.create("mes_task"))
                .thenAnswer(x -> R.ok("MANUAL-" + seq.incrementAndGet(), "测试序号"));
        var target =
                new MesManualTaskService(
                        facts,
                        taskMapper,
                        new MesActualFactoryAccessService(access),
                        engineering,
                        materials,
                        policies,
                        numbers,
                        packages);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        service = (MesManualTaskService) proxy.getProxy();
    }

    void identity(Long actor, String factory) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode(factory);
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(actor);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("manual-user");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    MesManualTaskCommand command() {
        return new MesManualTaskCommand()
                .setRequestId(UUID.randomUUID().toString())
                .setExpectedTenantId("1")
                .setExpectedFactoryCode("F1")
                .setExpectedActorId("7")
                .setMatCode("P1")
                .setEngineeringReleaseId(901L)
                .setEngineeringContentHash(bundle.getContentHash())
                .setQuantity(new BigDecimal("5"))
                .setType("PRODUCT")
                .setPlanStartDate(LocalDateTime.of(2026, 10, 27, 8, 0))
                .setPlanEndDate(LocalDateTime.of(2026, 10, 28, 18, 0));
    }

    int count() {
        return db.queryForObject("SELECT COUNT(*) FROM mom_mes.mes_task", Integer.class);
    }

    @Test
    void actualManualCreateFreezesPublishedPackageWithoutPlanContract() {
        var c = command();
        var receipt = service.create(c);
        assertTrue(receipt.exists());
        assertEquals(1, count());
        var task = taskMapper.selectById(Long.valueOf(receipt.task().id()));
        assertEquals("MANUAL", task.getResourceType());
        assertNull(task.getPlanNo());
        assertNull(task.getFormalPlanContractNo());
        assertEquals("CREATE", task.getStatus());
        assertEquals("正式产品", task.getMatName());
        assertEquals(0, task.getNoticeNum().compareTo(new BigDecimal("5")));
        assertEquals(task.getCode(), task.getProductionOrder());
        assertNotNull(engineering.verifyBeforeIssue(task));
        assertTrue(service.resolve(c).exists());
        assertEquals(receipt.task().acceptedAt(), service.resolve(c).task().acceptedAt());
    }

    @Test
    void originalRequestReturnsSameEvenAfterCloseSoftDeleteAndPolicyRetirement() {
        var c = command();
        var one = service.create(c);
        db.update(
                "UPDATE mom_mes.mes_task SET status='CLOSE',del_flag='1' WHERE id=?",
                Long.valueOf(one.task().id()));
        when(policies.active(anyString())).thenReturn(R.failed("策略退役"));
        when(packages.resolveForProduction(any())).thenReturn(R.failed("工程撤回"));
        assertEquals(one.task().id(), service.create(c).task().id());
        assertTrue(service.resolve(c).exists());
        assertEquals(1, count());
    }

    @Test
    void sameRequestCannotChangeQuantityProductOrOperator() {
        var c = command();
        service.create(c);
        c.setQuantity(new BigDecimal("6"));
        assertThrows(RuntimeException.class, () -> service.create(c));
        c.setQuantity(new BigDecimal("5"));
        identity(8L, "F1");
        assertThrows(RuntimeException.class, () -> service.create(c));
        assertEquals(1, count());
    }

    @Test
    void factoryAuthorizationAndExpectedScopeAreCheckedBeforeRequestLock() {
        var c = command();
        when(access.check(any())).thenReturn(R.ok(false));
        assertThrows(RuntimeException.class, () -> service.create(c));
        assertEquals(0, locks);
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLICY",
                "ERP_MAPPING",
                "TENANT",
                "FACTORY",
                "ENGINEERING",
                "PRODUCT",
                "ZERO",
                "SCALE",
                "DATE"
            })
    void invalidSourceRejectsBeforeAnyInsert(String kind) {
        var c = command();
        switch (kind) {
            case "POLICY" -> policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
            case "ERP_MAPPING" -> policy.setErpSystemCode("EXTERNAL");
            case "TENANT" -> bundle.setTenantId("2");
            case "FACTORY" -> bundle.setFactoryCode("F2");
            case "ENGINEERING" ->
                    when(packages.resolveForProduction(any())).thenReturn(R.failed("未发布"));
            case "PRODUCT" -> product.setMaterialCode("OTHER");
            case "ZERO" -> c.setQuantity(BigDecimal.ZERO);
            case "SCALE" -> c.setQuantity(new BigDecimal("0.000000001"));
            case "DATE" -> c.setPlanEndDate(c.getPlanStartDate().minusDays(1));
            default -> fail(kind);
        }
        assertThrows(RuntimeException.class, () -> service.create(c));
        assertEquals(0, count());
    }

    @Test
    void insertZeroDoesNotProduceAcceptedReceipt() {
        fault.zeroInsert = true;
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(0, count());
    }

    @Test
    void notFoundResolutionDoesNotCreateAnything() {
        assertFalse(service.resolve(command()).exists());
        assertEquals(0, count());
    }

    @Test
    void receiptSaveZeroRollsBackCreatedTask() {
        fault.zeroReceipt = true;
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(0, count());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mom_mes.mes_manual_task_request", Integer.class));
    }

    @Test
    void cancelledOriginalIsPermanentAndCannotCreateALateOrder() {
        var c = command();
        assertEquals("CANCELLED", service.cancel(c).state());
        assertEquals("CANCELLED", service.create(c).state());
        assertEquals("CANCELLED", service.resolve(c).state());
        assertEquals(0, count());
    }

    @Test
    void cancellationAfterAcceptanceKeepsTheOriginalTask() {
        var c = command();
        var result = service.create(c);
        assertEquals("ACCEPTED", service.cancel(c).state());
        assertEquals(result.task().id(), service.resolve(c).task().id());
        assertEquals(1, count());
    }

    @Test
    void expectedFactoryAndIdentityDriftNeverLockOrCreate() {
        var c = command().setExpectedFactoryCode("F2");
        assertThrows(RuntimeException.class, () -> service.create(c));
        assertEquals(0, locks);
        assertEquals(0, count());
    }

    @Test
    void publishedCandidatesRemainScopedAndChecked() {
        assertEquals("P1", service.candidates("P1").productCode());
        bundle.setProductCode("OTHER");
        assertThrows(RuntimeException.class, () -> service.candidates("P1"));
    }

    @Test
    void commandRejectsClientInjectedSourceAndStatus() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        assertThrows(
                Exception.class,
                () -> json.readValue("{\"status\":\"ISSUE\"}", MesManualTaskCommand.class));
    }

    @Test
    void ordinaryEditCannotReplaceManualPermanentRequestOrConvertItsSource() throws Exception {
        var c = command();
        var receipt = service.create(c);
        var ctor = MesTaskServiceImpl.class.getDeclaredConstructors()[0];
        var target =
                (MesTaskServiceImpl)
                        ctor.newInstance(
                                Arrays.stream(ctor.getParameterTypes())
                                        .map(
                                                type ->
                                                        type == MesManualTaskService.class
                                                                ? service
                                                                : mock(type))
                                        .toArray());
        org.springframework.test.util.ReflectionTestUtils.setField(
                target, "baseMapper", taskMapper);
        var edit = new MesTaskEntity().setSourceRequestId("CHANGED_REQUEST");
        edit.setId(Long.valueOf(receipt.task().id()));
        assertThrows(RuntimeException.class, () -> target.edit(edit));
        assertEquals(
                MesManualTaskService.PREFIX + c.getRequestId(),
                taskMapper.selectById(edit.getId()).getSourceRequestId());
        edit.setSourceRequestId(null);
        edit.setResourceType("REWORK");
        assertThrows(RuntimeException.class, () -> target.edit(edit));
        assertEquals("MANUAL", taskMapper.selectById(edit.getId()).getResourceType());
    }

    @Test
    void createdManualTaskUsesOriginalPublicIssueAndNodeFreezePath() throws Exception {
        var accepted = service.create(command());
        var dependencies = new HashMap<Class<?>, Object>();
        var ctor = MesTaskServiceImpl.class.getDeclaredConstructors()[0];
        for (var type : ctor.getParameterTypes()) dependencies.put(type, mock(type));
        dependencies.put(MesManualTaskService.class, service);
        dependencies.put(MesEngineeringPackageService.class, engineering);
        var target =
                (MesTaskServiceImpl)
                        ctor.newInstance(
                                Arrays.stream(ctor.getParameterTypes())
                                        .map(dependencies::get)
                                        .toArray());
        org.springframework.test.util.ReflectionTestUtils.setField(
                target, "baseMapper", taskMapper);
        var children =
                (com.kitex.mom.mes.service.MesProduceTaskService)
                        dependencies.get(com.kitex.mom.mes.service.MesProduceTaskService.class);
        when(children.saveBatch(anyCollection())).thenReturn(true);
        var catalog = new MdmProcessParameterStandardDTO.FrozenCatalog();
        catalog.setStandardNo("PARAM-DEMO");
        catalog.setVersionNo(1);
        var parameterPort =
                (MesProcessParameterStandardFreezeService)
                        dependencies.get(MesProcessParameterStandardFreezeService.class);
        when(parameterPort.freezeFromEngineeringSnapshot(any(), anyList(), anyString()))
                .thenReturn(
                        new MesProcessParameterStandardFreezeService.FrozenBundle(
                                catalog,
                                "{}",
                                "a".repeat(64),
                                Map.of(
                                        "RN-1",
                                        new MesProcessParameterStandardFreezeService
                                                .ProcessSnapshot("{}", "a".repeat(64)))));
        var sopPort = (MesSopFreezeService) dependencies.get(MesSopFreezeService.class);
        when(sopPort.mergeFrozenSnapshot(anyString(), anyList())).thenAnswer(c -> c.getArgument(0));
        var seq = (RemoteSeqConfigService) dependencies.get(RemoteSeqConfigService.class);
        when(seq.create("mes_produce_task")).thenReturn(R.ok("MANUAL-OP1", "测试序号"));
        target.issue(Long.valueOf(accepted.task().id()));
        var task = taskMapper.selectById(Long.valueOf(accepted.task().id()));
        assertEquals("ISSUE", task.getStatus());
        assertEquals(901L, task.getEngineeringReleaseId());
        assertEquals(MesManualTaskService.PREFIX + accepted.requestId(), task.getSourceRequestId());
        verify(children)
                .saveBatch(
                        argThat(
                                rows ->
                                        rows.size() == 1
                                                && rows.iterator()
                                                        .next()
                                                        .getSourceRouteNodeCode()
                                                        .equals("RN-1")
                                                && rows.iterator()
                                                        .next()
                                                        .getTaskCode()
                                                        .equals(task.getCode())
                                                && rows.iterator()
                                                                .next()
                                                                .getNoticeNum()
                                                                .compareTo(new BigDecimal("5"))
                                                        == 0));
        verifyZeroInteractions(
                dependencies.get(MesFormalPlanReceiptVerifier.class),
                dependencies.get(com.kitex.mom.plan.feign.RemotePlnIssueService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00000001", "0.0000001", "100000000000", "0.01000000"})
    void quantityStaysPlainAcrossCreateResolveAndCancel(String input) throws Exception {
        var c = command().setQuantity(new BigDecimal(input));
        String expected = new BigDecimal(input).stripTrailingZeros().toPlainString();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var result = service.create(c);
        String raw =
                db.queryForObject(
                        "SELECT command_payload FROM mom_mes.mes_manual_task_request WHERE"
                                + " request_id=?",
                        String.class,
                        c.getRequestId());
        assertEquals(expected, mapper.readTree(raw).path("quantity").asText());
        assertEquals(expected, mapper.valueToTree(result.command()).path("quantity").asText());
        assertEquals(
                expected,
                mapper.valueToTree(service.resolve(c).command()).path("quantity").asText());
        c.setRequestId(UUID.randomUUID().toString());
        assertEquals("CANCELLED", service.cancel(c).state());
        raw =
                db.queryForObject(
                        "SELECT command_payload FROM mom_mes.mes_manual_task_request WHERE"
                                + " request_id=?",
                        String.class,
                        c.getRequestId());
        assertEquals(expected, mapper.readTree(raw).path("quantity").asText());
        assertEquals(
                expected,
                mapper.valueToTree(service.resolve(c).command()).path("quantity").asText());
        assertEquals("CANCELLED", service.create(c).state());
        assertEquals(1, count());
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class Fault implements Interceptor {
        boolean zeroInsert, zeroReceipt;

        public Object intercept(Invocation call) throws Throwable {
            if (zeroInsert
                            && ((MappedStatement) call.getArgs()[0])
                                    .getId()
                                    .endsWith("MesTaskMapper.insert")
                    || zeroReceipt
                            && ((MappedStatement) call.getArgs()[0])
                                    .getId()
                                    .endsWith("MesManualTaskMapper.insert")) return 0;
            return call.proceed();
        }
    }
}
