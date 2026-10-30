package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.config.JacksonConfiguration;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.controller.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/** 真实历史/任务四表、Spring事务和MVC方法权限；MDM工厂授权为外部控制端口。 */
public class QmsInspectionHistoryTransactionTest {
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private DataSourceTransactionManager transactions;
    private InspectionProcedureHistoryService procedure;
    private InspectionProductHistoryService product;
    private GenericWebApplicationContext web;
    private MockMvc mvc;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final HistoryFault fault = new HistoryFault();
    private final TestPermissions permissions = new TestPermissions();
    private R<Boolean> authorization = R.ok(true);

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(2L);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("inspector");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:qms_history_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE qms_event_installation(version int)");
        db.update("INSERT INTO qms_event_installation VALUES(1)");
        db.execute("CREATE SCHEMA mom_qms");
        db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_event_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionHistoryTransactionTest.installedVersion'");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_event(id bigint PRIMARY KEY,tenant_id bigint"
                    + " NOT NULL,factory_code varchar(100) NOT NULL,inspection_type varchar(16) NOT"
                    + " NULL,task_id bigint NOT NULL,history_id bigint NOT NULL,event_type"
                    + " varchar(16) NOT NULL,sequence_no bigint NOT NULL,previous_event_id"
                    + " bigint,previous_fingerprint varchar(64),occurred_at timestamp NOT"
                    + " NULL,payload text NOT NULL,fingerprint varchar(64) NOT NULL,"
                    + "UNIQUE(tenant_id,factory_code,inspection_type,history_id),"
                    + "UNIQUE(tenant_id,factory_code,inspection_type,task_id,sequence_no))");
        db.execute(
                "CREATE TABLE public.qms_inspection_event AS SELECT * FROM"
                        + " mom_qms.qms_inspection_event WHERE 1=0");
        transactions = new DataSourceTransactionManager(ds);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type :
                List.of(
                        QmsInspectionProcedureTaskEntity.class,
                        QmsInspectionProductTaskEntity.class,
                        QmsInspectionProcedureHistoryEntity.class,
                        QmsInspectionProductHistoryEntity.class)) table(config, type);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(config, "event-ddl"), QmsInspectionEventEntity.class);
        var mappers =
                List.of(
                        QmsInspectionProcedureTaskMapper.class,
                        QmsInspectionProductTaskMapper.class,
                        QmsInspectionProcedureHistoryMapper.class,
                        QmsInspectionProductHistoryMapper.class,
                        QmsInspectionEventMapper.class);
        for (var mapper : mappers) config.addMapper(mapper);
        var tenant = new MybatisPlusInterceptor();
        tenant.addInnerInterceptor(
                new TenantLineInnerInterceptor(
                        new TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        TenantContextHolder.getTenantId());
                            }
                        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(tenant, fault);
        sessions = new SqlSessionTemplate(factory.getObject());
        for (var mapper : mappers) dependencies.put(mapper, sessions.getMapper(mapper));
        var remote = mock(RemoteMdmFactoryAccessService.class);
        when(remote.check(any()))
                .thenAnswer(
                        call -> {
                            com.kitex.mom.mdm.dto.MdmFactoryAccessCheck command =
                                    call.getArgument(0);
                            if (!Objects.equals(2L, command.getUserId())
                                    || !Objects.equals(
                                            FactoryContextHolder.getFactoryCode(),
                                            command.getFactoryCode())
                                    || !"read".equals(command.getRequiredPermission()))
                                return R.ok(false);
                            return authorization;
                        });
        dependencies.put(RemoteMdmFactoryAccessService.class, remote);
        dependencies.put(
                QmsInspectionEventOutboxMapper.class, mock(QmsInspectionEventOutboxMapper.class));
        var eventTarget = construct(QmsInspectionEventService.class);
        var eventProxy = new ProxyFactory(eventTarget);
        eventProxy.setProxyTargetClass(true);
        eventProxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        dependencies.put(QmsInspectionEventService.class, eventProxy.getProxy());
        procedure =
                history(
                        InspectionProcedureHistoryServiceImpl.class,
                        InspectionProcedureHistoryService.class,
                        QmsInspectionProcedureHistoryMapper.class);
        product =
                history(
                        InspectionProductHistoryServiceImpl.class,
                        InspectionProductHistoryService.class,
                        QmsInspectionProductHistoryMapper.class);
        for (String type : List.of("PROCEDURE", "PRODUCT")) {
            db.update(
                    "INSERT INTO "
                            + taskTable(type)
                            + "(id,tenant_id,factory_code,num,result,status)"
                            + " VALUES(700,1,'F1',5,'QUALIFIED','FINISHED')");
            db.update(
                    "INSERT INTO "
                            + historyTable(type)
                            + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                            + " VALUES(100,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                            + " 08:00:00',FALSE)");
        }
        web = new GenericWebApplicationContext();
        web.setServletContext(new MockServletContext());
        AnnotationConfigUtils.registerAnnotationConfigProcessors(web);
        web.registerBean(WebConfiguration.class);
        web.registerBean("pms", TestPermissions.class, () -> permissions);
        web.registerBean(
                InspectionProcedureTaskController.class,
                () ->
                        new InspectionProcedureTaskController(
                                mock(InspectionProcedureTaskService.class),
                                procedure,
                                mock(InspectionProcedureHandleRecordService.class),
                                mock(QmsReworkOrderService.class),
                                (QmsInspectionEventService)
                                        dependencies.get(QmsInspectionEventService.class)));
        web.registerBean(
                InspectionProductTaskController.class,
                () ->
                        new InspectionProductTaskController(
                                mock(InspectionProductTaskService.class),
                                product,
                                mock(InspectionProductHandleRecordService.class),
                                mock(QmsReworkOrderService.class),
                                (QmsInspectionEventService)
                                        dependencies.get(QmsInspectionEventService.class)));
        web.refresh();
        mvc =
                MockMvcBuilders.webAppContextSetup(web)
                        .addFilters(
                                new ExceptionTranslationFilter(new Http403ForbiddenEntryPoint()))
                        .build();
    }

    @AfterEach
    void clear() {
        if (web != null) web.close();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,CREATE",
        "PRODUCT,CREATE",
        "PROCEDURE,APPEND",
        "PRODUCT,APPEND",
        "PROCEDURE,RESULT",
        "PRODUCT,RESULT",
        "PROCEDURE,CANCELED",
        "PRODUCT,CANCELED",
        "PROCEDURE,EXEMPTION",
        "PRODUCT,EXEMPTION"
    })
    void normalHistoryActionsPersistTheirOwnSource(String type, String action) throws Throwable {
        db.update("DELETE FROM " + historyTable(type));
        write(type, action);
        var row = db.queryForMap("SELECT * FROM " + historyTable(type));
        assertEquals(action, row.get("TYPE"));
        assertEquals(700L, ((Number) row.get("TASK_ID")).longValue());
        assertEquals("F1", row.get("FACTORY_CODE"));
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,CREATE",
        "PRODUCT,CREATE",
        "PROCEDURE,APPEND",
        "PRODUCT,APPEND",
        "PROCEDURE,RESULT",
        "PRODUCT,RESULT",
        "PROCEDURE,CANCELED",
        "PRODUCT,CANCELED",
        "PROCEDURE,EXEMPTION",
        "PRODUCT,EXEMPTION"
    })
    void aLostHistoryInsertCannotCommitTheParentChange(String type, String action) {
        fault.loseInsert = true;
        assertThrows(
                CheckedException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .executeWithoutResult(
                                        status -> {
                                            db.update("UPDATE " + taskTable(type) + " SET num=9");
                                            try {
                                                write(type, action);
                                            } catch (RuntimeException | Error failure) {
                                                throw failure;
                                            } catch (Throwable failure) {
                                                throw new AssertionError(failure);
                                            }
                                        }));
        assertEquals(5, db.queryForObject("SELECT num FROM " + taskTable(type), Integer.class));
        assertEquals(
                1, db.queryForObject("SELECT count(*) FROM " + historyTable(type), Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aDetailReadsTheActualHistoryInsteadOfRecursing(String type) {
        Object found = assertDoesNotThrow(() -> detail(type, 100L));
        assertEquals(100L, ((BaseEntity<?>) found).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anotherFactorysHistoryCannotBeListedOrRead(String type) {
        FactoryContextHolder.setFactoryCode("F2");
        assertThrows(CheckedException.class, () -> list(type));
        assertThrows(CheckedException.class, () -> detail(type, 100L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void deniedOrUnknownFactoryAuthorizationCannotExposeHistory(String type) {
        authorization = R.ok(false);
        assertThrows(CheckedException.class, () -> list(type));
        authorization = null;
        assertThrows(CheckedException.class, () -> list(type));
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,TENANT",
        "PRODUCT,FACTORY",
        "PROCEDURE,TENANT_SKIP",
        "PRODUCT,FACTORY_SKIP",
        "PROCEDURE,IDENTITY",
        "PRODUCT,IDENTITY"
    })
    void absentOrBypassedContextCannotExposeHistory(String type, String missing) {
        switch (missing) {
            case "TENANT" -> TenantContextHolder.clear();
            case "FACTORY" -> FactoryContextHolder.clear();
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
            case "IDENTITY" -> SecurityContextHolder.clearContext();
        }
        assertThrows(CheckedException.class, () -> list(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aDetailRequiresTheSameHistoryPermissionAsTheList(String type) throws Exception {
        permissions.allowed = false;
        assertDoesNotThrow(
                () ->
                        mvc.perform(
                                        get(base(type) + "/findTaskDetailHistoryById")
                                                .param("id", "100"))
                                .andExpect(status().isForbidden()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aThirdConflictingRecordAtTheFirstTimeCannotHideBehindTwoMatchingRows(String type)
            throws Exception {
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 08:00:00',FALSE)");
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(102,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 08:00:00',TRUE)");
        assertEquals("AMBIGUOUS", first(type).path("availability").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aQualifiedFirstHistoryRemainsAvailableAfterCurrentDispositionChanges(String type)
            throws Exception {
        db.update("UPDATE " + historyTable(type) + " SET qualified_status=TRUE");
        db.update("UPDATE " + taskTable(type) + " SET result='RECEIVE'");
        JsonNode result = first(type);
        assertEquals("AVAILABLE", result.path("availability").asText());
        assertTrue(result.path("qualified").asBoolean());
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,NUM", "PRODUCT,NUM", "PROCEDURE,TENANT", "PRODUCT,FACTORY"})
    void aSpoofedTaskSnapshotCannotCreateHistoricalEvidence(String type, String changed) {
        Object task = source(type);
        switch (changed) {
            case "NUM" -> ReflectionTestUtils.setField(task, "num", 99);
            case "TENANT" -> ((BaseEntity<?>) task).setTenantId(2L);
            case "FACTORY" -> ReflectionTestUtils.setField(task, "factoryCode", "F2");
        }
        assertThrows(
                CheckedException.class,
                () -> {
                    if ("PROCEDURE".equals(type))
                        procedure.check((QmsInspectionProcedureTaskEntity) task);
                    else product.check((QmsInspectionProductTaskEntity) task);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aReleaseConclusionCannotBeStoredAsAnUnqualifiedInspection(String type) {
        db.update("UPDATE " + taskTable(type) + " SET result='RELEASE'");
        assertThrows(
                CheckedException.class,
                () -> {
                    if ("PROCEDURE".equals(type))
                        procedure.check((QmsInspectionProcedureTaskEntity) source(type));
                    else product.check((QmsInspectionProductTaskEntity) source(type));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anInternalCancellationRetainsItsExistingServiceIdentityContract(String type) {
        db.update("UPDATE " + taskTable(type) + " SET result='CANCELED'");
        Object task = source(type);
        SecurityContextHolder.clearContext();
        assertDoesNotThrow(
                () -> {
                    if ("PROCEDURE".equals(type))
                        procedure.canceled(
                                (QmsInspectionProcedureTaskEntity) task,
                                "trusted parent withdrawal");
                    else
                        product.canceled(
                                (QmsInspectionProductTaskEntity) task, "trusted parent withdrawal");
                });
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM "
                                + historyTable(type)
                                + " WHERE type='CANCELED' AND factory_code='F1' AND tenant_id=1",
                        Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void internalHistoryWritesStillRequireAnActualFactory(String type) {
        Object task = source(type);
        FactoryContextHolder.clear();
        assertThrows(
                CheckedException.class,
                () -> {
                    if ("PROCEDURE".equals(type))
                        procedure.check((QmsInspectionProcedureTaskEntity) task);
                    else product.check((QmsInspectionProductTaskEntity) task);
                });
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,0",
        "PRODUCT,0",
        "PROCEDURE,-1",
        "PRODUCT,-1",
        "PROCEDURE,6",
        "PRODUCT,6"
    })
    void invalidAppendQuantityCannotBecomeHistoricalFact(String type, int quantity) {
        assertThrows(
                CheckedException.class,
                () -> {
                    if ("PROCEDURE".equals(type))
                        procedure.append((QmsInspectionProcedureTaskEntity) source(type), quantity);
                    else product.append((QmsInspectionProductTaskEntity) source(type), quantity);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void duplicateMatchingFirstRowsDoNotChangeTheOriginalOutcome(String type) throws Exception {
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 08:00:00',FALSE)");
        JsonNode result = first(type);
        assertEquals("AVAILABLE", result.path("availability").asText());
        assertFalse(result.path("qualified").asBoolean());
        assertEquals(100, result.path("historyId").asLong());
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,0", "PRODUCT,-1"})
    void anInvalidFirstQuantityIsNotEligibleForFirstPassMetrics(String type, int quantity)
            throws Exception {
        db.update("UPDATE " + historyTable(type) + " SET num=?", quantity);
        JsonNode result = first(type);
        assertEquals("INCOMPLETE", result.path("availability").asText());
        assertTrue(result.path("qualified").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void theFirstDecisionEndpointRequiresHistoryPermission(String type) throws Exception {
        permissions.allowed = false;
        mvc.perform(get(base(type) + "/firstDecision").param("taskId", "700"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anOversizedHistoryIsRejectedRatherThanTruncatedIntoEvidence(String type) {
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 5000; i++) rows.add(new Object[] {1000L + i});
        db.batchUpdate(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(?,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24 09:00:00',FALSE)",
                rows);
        assertThrows(CheckedException.class, () -> list(type));
        assertThrows(
                CheckedException.class,
                () -> {
                    Object service = "PROCEDURE".equals(type) ? procedure : product;
                    try {
                        service.getClass()
                                .getMethod("firstDecision", Long.class)
                                .invoke(service, 700L);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anotherReinspectionTaskKeepsItsOwnFirstDecision(String type) throws Exception {
        db.update(
                "INSERT INTO "
                        + taskTable(type)
                        + "(id,tenant_id,factory_code,num,result,status,source_request_id)"
                        + " VALUES(701,1,'F1',5,'QUALIFIED','FINISHED','REWORK-REINSPECTION:RW-1')");
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',701,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 09:00:00',TRUE)");
        String response =
                mvc.perform(get(base(type) + "/firstDecision").param("taskId", "701"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode result = new ObjectMapper().readTree(response).path("data");
        assertEquals(101, result.path("historyId").asLong());
        assertTrue(result.path("qualified").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void laterReleaseDoesNotOverwriteTheFirstUnqualifiedResult(String type) throws Exception {
        db.update("UPDATE " + taskTable(type) + " SET result='RELEASE',usable_num=5,handle_num=5");
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 09:00:00',TRUE)");
        JsonNode first = first(type);
        assertEquals("AVAILABLE", first.path("availability").asText());
        assertEquals(100, first.path("historyId").asLong());
        assertFalse(first.path("qualified").asBoolean());
        assertEquals(5, first.path("inspectionQuantity").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void noHistoryCannotBeInferredFromTheCurrentQualifiedTask(String type) throws Exception {
        db.update("DELETE FROM " + historyTable(type));
        JsonNode first = first(type);
        assertEquals("MISSING", first.path("availability").asText());
        assertTrue(first.path("qualified").isNull());
        assertFalse(first.path("dataGaps").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aSurvivingLaterPassCannotCertifyFirstPassEligibility(String type) throws Exception {
        // 旧save(false)可丢失原不合格结果；当前只有后来合格的留存记录，不能认证为原首检通过。
        db.update(
                "UPDATE "
                        + historyTable(type)
                        + " SET qualified_status=TRUE,time=TIMESTAMP '2026-10-24 09:00:00'");
        JsonNode result = first(type);
        assertEquals("AVAILABLE", result.path("availability").asText());
        assertTrue(result.path("qualified").asBoolean());
        assertTrue(result.path("firstPassEligible").isBoolean());
        assertFalse(result.path("firstPassEligible").asBoolean());
        assertFalse(result.path("dataGaps").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,2026-10-24T16:05:06.123Z", "PRODUCT,2026-10-24T00:05:06.987Z"})
    void firstDecisionTimePreservesTheInstantWithAnExplicitUtcOffset(String type, String utc)
            throws Exception {
        db.update(
                "UPDATE " + historyTable(type) + " SET time=? WHERE id=100",
                Timestamp.from(Instant.parse(utc)));
        JsonNode result = first(type);
        assertEquals("AVAILABLE", result.path("availability").asText());
        String judgedAt = result.path("judgedAt").asText();
        assertEquals(utc, judgedAt);
        assertEquals(Instant.parse(utc), OffsetDateTime.parse(judgedAt).toInstant());
        assertFalse(result.path("firstPassEligible").asBoolean());
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,CREATE",
        "PROCEDURE,APPEND",
        "PROCEDURE,RESULT",
        "PROCEDURE,CANCELED",
        "PROCEDURE,EXEMPTION",
        "PRODUCT,CREATE",
        "PRODUCT,APPEND",
        "PRODUCT,RESULT",
        "PRODUCT,CANCELED",
        "PRODUCT,EXEMPTION"
    })
    void everyHistoryActionAlsoPersistsAnImmutableEvent(String type, String action)
            throws Throwable {
        write(type, action);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        var event = db.queryForMap("SELECT * FROM mom_qms.qms_inspection_event");
        assertEquals(type, event.get("inspection_type"));
        assertEquals(action, event.get("event_type"));
        assertEquals(1L, ((Number) event.get("sequence_no")).longValue());
        assertEquals(700L, ((Number) event.get("task_id")).longValue());
        assertNull(event.get("previous_event_id"));
        assertNotNull(event.get("fingerprint"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void laterDecisionsLinkToTheFrozenEarlierEvent(String type) throws Throwable {
        write(type, "CREATE");
        write(type, "RESULT");
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        var events =
                db.queryForList("SELECT * FROM mom_qms.qms_inspection_event ORDER BY sequence_no");
        assertEquals(events.get(0).get("id"), events.get(1).get("previous_event_id"));
        assertEquals(events.get(0).get("fingerprint"), events.get(1).get("previous_fingerprint"));
        String raw = (String) events.get(0).get("payload");
        write(type, "CANCELED");
        assertEquals(
                raw,
                db.queryForObject(
                        "SELECT payload FROM mom_qms.qms_inspection_event WHERE sequence_no=1",
                        String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anEventInsertFailureRollsBackTheParentAndHistory(String type) {
        fault.loseEventInsert = true;
        assertThrows(
                CheckedException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        status -> {
                                            db.update(
                                                    "UPDATE "
                                                            + taskTable(type)
                                                            + " SET remark='event-failed'");
                                            try {
                                                write(type, "RESULT");
                                            } catch (RuntimeException failure) {
                                                throw failure;
                                            } catch (Throwable failure) {
                                                throw new AssertionError(failure);
                                            }
                                            return null;
                                        }));
        assertNull(db.queryForObject("SELECT remark FROM " + taskTable(type), String.class));
        assertEquals(
                1, db.queryForObject("SELECT COUNT(*) FROM " + historyTable(type), Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,VERSION", "PRODUCT,VERSION", "PROCEDURE,ABSENT", "PRODUCT,ABSENT"})
    void aMissingJournalUpgradeCannotSilentlySkipAnEvent(String type, String missing) {
        if ("VERSION".equals(missing)) db.update("UPDATE qms_event_installation SET version=0");
        else db.execute("DROP ALIAS mom_qms.mom_inspection_event_ready_v1");
        assertThrows(
                CheckedException.class,
                () -> {
                    try {
                        write(type, "RESULT");
                    } catch (Throwable failure) {
                        throw failure;
                    }
                });
        assertEquals(
                1, db.queryForObject("SELECT COUNT(*) FROM " + historyTable(type), Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void eventsAreReadFromTheSameAuthorizedHistoryEndpoint(String type) throws Throwable {
        write(type, "RESULT");
        mvc.perform(get(base(type) + "/events").param("taskId", "700")).andExpect(status().isOk());
        permissions.allowed = false;
        mvc.perform(get(base(type) + "/events").param("taskId", "700"))
                .andExpect(status().isForbidden());
    }

    public static int installedVersion(java.sql.Connection connection)
            throws java.sql.SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT version FROM qms_event_installation")) {
            result.next();
            return result.getInt(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void theSameHistoryReplaysItsOriginalEvent(String type) throws Throwable {
        write(type, "RESULT");
        var first = eventService().events(type, 700L).get(0);
        var replay =
                new TransactionTemplate(transactions)
                        .execute(status -> eventService().capture(type, first.getHistoryId()));
        assertEquals(first.getId(), replay.getId());
        assertEquals(first.getPayload(), replay.getPayload());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,time", "PRODUCT,type"})
    void theSameHistoryCannotChangeItsOriginalTimeOrAction(String type, String field)
            throws Throwable {
        write(type, "RESULT");
        var first = eventService().events(type, 700L).get(0);
        String change =
                "time".equals(field) ? "time=TIMESTAMP '2020-01-01 00:00:00'" : "type='CREATE'";
        db.update(
                "UPDATE " + historyTable(type) + " SET " + change + " WHERE id=?",
                first.getHistoryId());
        assertThrows(
                CheckedException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        status ->
                                                eventService()
                                                        .capture(type, first.getHistoryId())));
        assertEquals(
                first.getPayload(),
                db.queryForObject(
                        "SELECT payload FROM mom_qms.qms_inspection_event", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aBrokenEventChainCannotBeExtended(String type) throws Throwable {
        write(type, "CREATE");
        write(type, "RESULT");
        // 故障注入模拟越过真实PG不可变守卫后的坏数据；正常业务没有删除入口。
        db.update("DELETE FROM mom_qms.qms_inspection_event WHERE sequence_no=1");
        assertThrows(CheckedException.class, () -> write(type, "CANCELED"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void brokenOrChangedRawEvidenceIsNotReturnedAsAValidEvent(String type) throws Throwable {
        write(type, "RESULT");
        db.update("UPDATE mom_qms.qms_inspection_event SET payload='{}'");
        assertThrows(CheckedException.class, () -> eventService().events(type, 700L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void eventCaptureCannotRunOutsideTheOriginalTransaction(String type) {
        assertThrows(CheckedException.class, () -> eventService().capture(type, 100L));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,FACTORY", "PRODUCT,TENANT", "PROCEDURE,AUTHORIZATION"})
    void eventQueriesRequireActualScopeAndFactoryPermission(String type, String invalid)
            throws Throwable {
        write(type, "RESULT");
        if ("FACTORY".equals(invalid)) FactoryContextHolder.setFactoryCode("F2");
        else if ("TENANT".equals(invalid)) TenantContextHolder.setTenantId(2L);
        else authorization = R.ok(false);
        assertThrows(CheckedException.class, () -> eventService().events(type, 700L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void batchQuantityAndSampleSizeRemainSeparateAndDoNotCertifyFirstPass(String type)
            throws Throwable {
        db.update("UPDATE " + taskTable(type) + " SET inspection_num=2");
        write(type, "RESULT");
        var event = eventService().events(type, 700L).get(0);
        JsonNode raw = new ObjectMapper().readTree(event.getPayload());
        assertEquals(5, raw.path("quantity").asInt());
        assertEquals(2, raw.path("source").path("inspectionNum").asInt());
        assertFalse(raw.path("firstPassEligible").asBoolean());
        assertFalse(raw.path("dataGaps").isEmpty());
        String digest =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                event.getPayload()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
        assertEquals(digest, event.getFingerprint());
    }

    private QmsInspectionEventService eventService() {
        return (QmsInspectionEventService) dependencies.get(QmsInspectionEventService.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anOversizedEventChainIsRejectedWithoutReturningAPartialChain(String type)
            throws Throwable {
        write(type, "RESULT");
        var first = eventService().events(type, 700L).get(0);
        var rows = new ArrayList<Object[]>();
        for (long i = 2; i <= 5001; i++)
            rows.add(
                    new Object[] {
                        i, 10000 + i, i, type, first.getPayload(), first.getFingerprint()
                    });
        db.batchUpdate(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_event(id,tenant_id,factory_code,inspection_type,task_id,history_id,"
                    + "event_type,sequence_no,occurred_at,payload,fingerprint)"
                    + " VALUES(?,1,'F1',?,700,?,'RESULT',?,CURRENT_TIMESTAMP,?,?)",
                rows.stream()
                        .map(row -> new Object[] {row[0], row[3], row[1], row[2], row[4], row[5]})
                        .toList());
        var failure = assertThrows(CheckedException.class, () -> eventService().events(type, 700L));
        assertTrue(failure.getMessage().contains("超过5000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void twoTransactionsAppendOneContinuousChainWithoutUpdatingTheParent(String type)
            throws Exception {
        db.update("UPDATE " + historyTable(type) + " SET qualified_status=TRUE WHERE id=100");
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 09:00:00',TRUE)");
        var firstSaved = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () ->
                                    captureInAnotherTransaction(
                                            type,
                                            100L,
                                            () -> {
                                                firstSaved.countDown();
                                                try {
                                                    assertTrue(
                                                            release.await(
                                                                    5,
                                                                    java.util.concurrent.TimeUnit
                                                                            .SECONDS));
                                                } catch (InterruptedException failure) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(failure);
                                                }
                                            }));
            assertTrue(firstSaved.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second =
                    pool.submit(
                            () -> {
                                secondStarted.countDown();
                                return captureInAnotherTransaction(type, 101L, () -> {});
                            });
            assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var result = eventService().events(type, 700L);
            assertEquals(2, result.size());
            assertEquals(1L, result.get(0).getSequenceNo());
            assertEquals(2L, result.get(1).getSequenceNo());
            assertEquals(result.get(0).getId(), result.get(1).getPreviousEventId());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Object captureInAnotherTransaction(String type, long historyId, Runnable afterCapture) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            return new TransactionTemplate(transactions)
                    .execute(
                            status -> {
                                var result = eventService().capture(type, historyId);
                                afterCapture.run();
                                return result;
                            });
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void excessiveRawBytesAreRejectedBeforeLoadingOrExtendingTheChain(String type)
            throws Throwable {
        write(type, "RESULT");
        var first = eventService().events(type, 700L).get(0);
        String padded =
                first.getPayload()
                        + " "
                                .repeat(
                                        65536
                                                - first.getPayload()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)
                                                        .length);
        String digest =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                padded.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        // 绕过PG守卫的损坏库注入；预检必须先拒绝原文总量，而不是先加载后才报另一错误。
        var rows = new ArrayList<Object[]>();
        for (long i = 2; i <= 130; i++)
            rows.add(new Object[] {10000 + i, type, 20000 + i, i, padded, digest});
        db.batchUpdate(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_event(id,tenant_id,factory_code,inspection_type,task_id,history_id,"
                    + "event_type,sequence_no,occurred_at,payload,fingerprint)"
                    + " VALUES(?,1,'F1',?,700,?,'RESULT',?,CURRENT_TIMESTAMP,?,?)",
                rows);
        var readFailure =
                assertThrows(CheckedException.class, () -> eventService().events(type, 700L));
        assertTrue(readFailure.getMessage().contains("8MiB"));
        var writeFailure = assertThrows(CheckedException.class, () -> write(type, "RESULT"));
        assertTrue(writeFailure.getMessage().contains("8MiB"));
        assertEquals(
                2, db.queryForObject("SELECT COUNT(*) FROM " + historyTable(type), Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,time", "PRODUCT,qualified_status", "PROCEDURE,num"})
    void anIncompleteFirstRecordCannotBeSkippedInFavorOfALaterPass(String type, String field)
            throws Exception {
        db.update("UPDATE " + historyTable(type) + " SET " + field + "=NULL WHERE id=100");
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 09:00:00',TRUE)");
        JsonNode first = first(type);
        assertEquals("INCOMPLETE", first.path("availability").asText());
        assertTrue(first.path("qualified").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void conflictingDecisionsAtTheSameFirstTimeRemainAmbiguous(String type) throws Exception {
        db.update(
                "INSERT INTO "
                        + historyTable(type)
                        + "(id,tenant_id,factory_code,task_id,type,num,time,qualified_status)"
                        + " VALUES(101,1,'F1',700,'RESULT',5,TIMESTAMP '2026-10-24"
                        + " 08:00:00',TRUE)");
        JsonNode first = first(type);
        assertEquals("AMBIGUOUS", first.path("availability").asText());
        assertTrue(first.path("qualified").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void missingParentCannotTurnAnOrphanRecordIntoEvidence(String type) {
        db.update("DELETE FROM " + taskTable(type));
        assertThrows(CheckedException.class, () -> list(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void captureCannotCombineAnOldJudgmentWithADifferentLockedParent(String type) {
        // 留存旧历史是不合格，当前父任务已合格；不能把两时刻事实拼成一份新原件。
        assertThrows(
                CheckedException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(status -> eventService().capture(type, 100L)));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aShadowTableCannotReceiveEventsWhileTheCanonicalProbeIsReady(String type)
            throws Throwable {
        write(type, "RESULT");
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM public.qms_inspection_event", Integer.class));
        assertEquals(1, eventService().events(type, 700L).size());
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,NULL", "PRODUCT,NULL", "PROCEDURE,ZERO", "PRODUCT,ZERO"})
    void sqlNullSampleSizeIsNotInventedAsZeroInThePermanentSource(String type, String sample)
            throws Throwable {
        Integer value = "NULL".equals(sample) ? null : 0;
        db.update("UPDATE " + taskTable(type) + " SET inspection_num=?", value);
        assertEquals(
                value,
                db.queryForObject("SELECT inspection_num FROM " + taskTable(type), Integer.class));
        write(type, "RESULT");
        var original = eventService().events(type, 700L).get(0);
        JsonNode actual =
                new ObjectMapper()
                        .readTree(original.getPayload())
                        .path("source")
                        .path("inspectionNum");
        if (value == null) assertTrue(actual.isNull(), "SQL NULL不能冻结成明确0");
        else {
            assertTrue(actual.isIntegralNumber());
            assertEquals(0, actual.asInt());
        }
        var replay =
                new TransactionTemplate(transactions)
                        .execute(status -> eventService().capture(type, original.getHistoryId()));
        assertEquals(original.getPayload(), replay.getPayload());
    }

    private JsonNode first(String type) throws Exception {
        String response =
                mvc.perform(get(base(type) + "/firstDecision").param("taskId", "700"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new ObjectMapper().readTree(response).path("data");
    }

    private String base(String type) {
        return "/inspection/" + type.toLowerCase(Locale.ROOT) + "/task/history";
    }

    private String taskTable(String type) {
        return "PROCEDURE".equals(type)
                ? "qms_inspection_procedure_task"
                : "qms_inspection_product_task";
    }

    private String historyTable(String type) {
        return "PROCEDURE".equals(type)
                ? "qms_inspection_procedure_history"
                : "qms_inspection_product_history";
    }

    private List<?> list(String type) {
        return "PROCEDURE".equals(type)
                ? procedure.findAllByTaskId(700L)
                : product.findAllByTaskId(700L);
    }

    private Object detail(String type, Long id) {
        return "PROCEDURE".equals(type) ? procedure.findById(id) : product.findById(id);
    }

    private void write(String type, String action) throws Throwable {
        String result =
                switch (action) {
                    case "CANCELED" -> "CANCELED";
                    case "EXEMPTION" -> "EXEMPTION";
                    case "RESULT" -> "QUALIFIED";
                    default -> null;
                };
        db.update(
                "UPDATE " + taskTable(type) + " SET result=?,status=?",
                result,
                "CREATE".equals(action) || "APPEND".equals(action) ? "WAIT" : "FINISHED");
        Object source = source(type);
        if ("CREATE".equals(action)) ((BaseEntity<?>) source).setTenantId(null);
        Object service = "PROCEDURE".equals(type) ? procedure : product;
        String method =
                switch (action) {
                    case "CREATE" -> "create";
                    case "APPEND" -> "append";
                    case "RESULT" -> "check";
                    case "CANCELED" -> "canceled";
                    default -> "exemption";
                };
        try {
            if ("APPEND".equals(action))
                service.getClass()
                        .getMethod(method, source.getClass(), Integer.class)
                        .invoke(service, source, 2);
            else if ("CANCELED".equals(action))
                service.getClass()
                        .getMethod(method, source.getClass(), String.class)
                        .invoke(service, source, "actual cancellation");
            else service.getClass().getMethod(method, source.getClass()).invoke(service, source);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private Object source(String type) {
        return "PROCEDURE".equals(type)
                ? sessions.getMapper(QmsInspectionProcedureTaskMapper.class).selectById(700L)
                : sessions.getMapper(QmsInspectionProductTaskMapper.class).selectById(700L);
    }

    private Object construct(Class<?> type) throws Exception {
        if (dependencies.containsKey(type)) return dependencies.get(type);
        var constructor = type.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < args.length; i++)
            args[i] = construct(constructor.getParameterTypes()[i]);
        Object value = constructor.newInstance(args);
        dependencies.put(type, value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T> T history(Class<?> impl, Class<T> contract, Class<?> mapper) throws Exception {
        Object target = construct(impl);
        ReflectionTestUtils.setField(target, "baseMapper", dependencies.get(mapper));
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(contract);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "history-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        var ddl =
                new StringBuilder("CREATE TABLE mom_qms.")
                        .append(info.getTableName())
                        .append("(id bigint primary key");
        for (var field : info.getFieldList()) {
            var t = field.getPropertyType();
            String sql =
                    t == Long.class
                            ? "bigint"
                            : t == Integer.class
                                    ? "integer"
                                    : t == Boolean.class
                                            ? "boolean"
                                            : t == BigDecimal.class
                                                    ? "decimal(24,8)"
                                                    : t == Date.class || t == LocalDateTime.class
                                                            ? "timestamp"
                                                            : "varchar(64000)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sql);
            if (field.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
        db.execute(
                "CREATE SYNONYM public."
                        + info.getTableName()
                        + " FOR mom_qms."
                        + info.getTableName());
    }

    @Configuration
    @EnableWebMvc
    @EnableMethodSecurity
    static class WebConfiguration implements WebMvcConfigurer {
        @Override
        public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            var builder = new Jackson2ObjectMapperBuilder();
            new JacksonConfiguration().customizer().customize(builder);
            for (var converter : converters) {
                if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
                    jackson.setObjectMapper(builder.build());
                }
            }
        }
    }

    public static class TestPermissions {
        boolean allowed = true;

        public boolean hasPermission(String permission) {
            return allowed
                    && Set.of("procedureTask:history", "productTask:history").contains(permission);
        }
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class HistoryFault implements Interceptor {
        boolean loseInsert;
        boolean loseEventInsert;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (loseInsert && statement.getId().contains("HistoryMapper.insert")) return 0;
            if (loseEventInsert && statement.getId().endsWith("QmsInspectionEventMapper.insert"))
                return 0;
            return invocation.proceed();
        }
    }
}
