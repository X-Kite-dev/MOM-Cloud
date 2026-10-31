package com.kitex.mom.iot.parameter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.service.impl.IotDeviceDataAccess;
import com.kitex.mom.mes.api.feign.RemoteMesParameterContextService;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Actual MVC, method security, Spring transactions, MyBatis XML and portable production DDL. Only
 * remote MES authorization/evidence and device-access port are adapted; the device lock is real H2
 * SQL.
 */
class IotParameterCommandTransactionTest {
    AnnotationConfigApplicationContext app;
    JdbcTemplate db;
    MockMvc mvc;
    RemoteMesParameterContextService mes;
    MesParameterDispatchContextDTO.Evidence source;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final java.util.concurrent.atomic.AtomicReference<String> zeroWrite =
            new java.util.concurrent.atomic.AtomicReference<>();

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "update",
                    args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static class ZeroWrite implements org.apache.ibatis.plugin.Interceptor {
        final java.util.concurrent.atomic.AtomicReference<String> selected;

        ZeroWrite(java.util.concurrent.atomic.AtomicReference<String> selected) {
            this.selected = selected;
        }

        @Override
        public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            String action = selected.get();
            String statement =
                    ((org.apache.ibatis.mapping.MappedStatement) call.getArgs()[0]).getId();
            if (action != null
                    && statement.endsWith("." + action)
                    && selected.compareAndSet(action, null)) return 0;
            return call.proceed();
        }
    }

    static final List<String> ALL =
            List.of(
                    "mesTask:show",
                    "mesProduceTask:show",
                    "iot_device_view",
                    "iot_parameter_dispatch");

    @Configuration
    @EnableTransactionManagement
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "pms")
        PermissionCheck permissions() {
            return new PermissionCheck();
        }
    }

    public static class PermissionCheck {
        public boolean hasPermission(String value) {
            var a = SecurityContextHolder.getContext().getAuthentication();
            return a != null
                    && a.getAuthorities().stream().anyMatch(p -> p.getAuthority().equals(value));
        }
    }

    @RestControllerAdvice
    static class Advice {
        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<?> forbidden() {
            return ResponseEntity.status(403).body(Map.of("code", 1));
        }

        @ExceptionHandler(RuntimeException.class)
        ResponseEntity<?> rejected(RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 1, "msg", String.valueOf(e.getMessage())));
        }
    }

    void login(long actor, List<String> permissions) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(actor);
        when(user.getTenantId()).thenReturn(1L);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                user,
                                "N/A",
                                permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    Class<?> type(String name) {
        try {
            return Class.forName("com.kitex.mom.iot.parameter." + name);
        } catch (ClassNotFoundException e) {
            return fail("Persistent parameter command chain is not implemented: " + name);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        Class<?> mapper = type("IotParameterCommandMapper");
        login(7L, ALL);
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:parameter_commands_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_iot");
        String sql =
                Files.readString(
                        Path.of("../../../docs/database/mom-iot-parameter-command-upgrade-pg.sql"));
        String portable =
                sql.split("-- parameter_command_tables_begin", 2)[1]
                        .split("-- parameter_command_tables_end", 2)[0];
        for (String statement : portable.split(";"))
            if (!statement.isBlank()) db.execute(statement);
        db.execute("CREATE TABLE mom_iot.iot_device(id BIGINT PRIMARY KEY,firmware VARCHAR)");
        db.update("INSERT INTO mom_iot.iot_device VALUES(3,'FW1')");
        var access = mock(IotDeviceDataAccess.class);
        when(access.tenant()).thenAnswer(c -> TenantContextHolder.getTenantId());
        when(access.factory(anyString())).thenAnswer(c -> FactoryContextHolder.getFactoryCode());
        when(access.device(any(), any(), anyString()))
                .thenAnswer(
                        c -> {
                            String level = c.getArgument(2);
                            assertTrue(
                                    "read".equals(level)
                                            || TransactionSynchronizationManager
                                                    .isActualTransactionActive());
                            String version =
                                    db.queryForObject(
                                            "SELECT firmware FROM mom_iot.iot_device WHERE id=3"
                                                    + ("read".equals(level) ? "" : " FOR UPDATE"),
                                            String.class);
                            var device = new IotDeviceEntity();
                            device.setId(3L);
                            device.setTenantId(1L);
                            device.setFactoryCode("F1");
                            device.setDeviceCode("D1");
                            device.setProductId(4L);
                            device.setProductCode("P1");
                            device.setFirmwareVersion(version);
                            return device;
                        });
        mes = mock(RemoteMesParameterContextService.class);
        source =
                new MesParameterDispatchContextDTO.Evidence()
                        .setSchemaVersion("MES_PARAMETER_DISPATCH@1")
                        .setFactoryCode("F1")
                        .setTaskCode("MT1")
                        .setArrangeCode("A1")
                        .setOperationCode("OP1")
                        .setProcessCode("WIRE")
                        .setRouteNodeCode("RN-1")
                        .setStationCode("S1")
                        .setDeviceCode("D1")
                        .setEngineeringReleaseId("9007199254740993")
                        .setEngineeringVersion(2)
                        .setEngineeringHash("a".repeat(64))
                        .setSnapshotFingerprint("b".repeat(64))
                        .setNodeSnapshotFingerprint("c".repeat(64))
                        .setStandardNo("PS1")
                        .setStandardVersion(2)
                        .setParameters(
                                List.of(
                                        new MesParameterDispatchContextDTO.Parameter(
                                                "torque", "Nm", "2.50000000000000001", "2", "3")));
        when(mes.context(any())).thenAnswer(c -> R.ok(source));
        var config = new MybatisConfiguration();
        config.addMapper(mapper);
        config.addInterceptor(new ZeroWrite(zeroWrite));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setMapperLocations(
                new FileSystemResource("src/main/resources/mapper/IotParameterCommandMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        app = new AnnotationConfigApplicationContext();
        app.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test", Map.of("mom.iot.parameter-simulator-enabled", "true")));
        app.register(Config.class);
        app.getBeanFactory().registerSingleton("access", access);
        app.getBeanFactory().registerSingleton("mapper", sessions.getMapper(mapper));
        app.getBeanFactory().registerSingleton("mes", mes);
        app.getBeanFactory()
                .registerSingleton("devices", mock(com.kitex.mom.iot.mapper.IotDeviceMapper.class));
        app.getBeanFactory().registerSingleton("json", json);
        app.getBeanFactory()
                .registerSingleton("transactionManager", new DataSourceTransactionManager(ds));
        for (String name :
                List.of(
                        "IotParameterCommandService",
                        "IotParameterSimulator",
                        "IotParameterCommandCoordinator",
                        "IotParameterReceiptService",
                        "IotParameterPreparationService",
                        "IotParameterCommandController")) app.registerBean(type(name));
        app.refresh();
        mvc =
                MockMvcBuilders.standaloneSetup(app.getBean(type("IotParameterCommandController")))
                        .setControllerAdvice(new Advice())
                        .build();
    }

    @AfterEach
    void close() {
        if (app != null) app.close();
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    String body(String request, String scenario) throws Exception {
        return json.writeValueAsString(
                Map.of(
                        "requestId",
                        request,
                        "factoryCode",
                        "F1",
                        "taskCode",
                        "MT1",
                        "arrangeCode",
                        "A1",
                        "deviceCode",
                        "D1",
                        "firmwareVersion",
                        "FW1",
                        "mode",
                        "SIMULATION",
                        "scenario",
                        scenario,
                        "reason",
                        "冻结参数联验"));
    }

    MvcResult post(String path, String body) throws Exception {
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/process-parameters/commands" + path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn();
    }

    MvcResult action(String id, String verb) throws Exception {
        return post("/" + id + "/" + verb, "{}");
    }

    void ok(MvcResult r) throws Exception {
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    String state(String id) {
        return db.queryForObject(
                "SELECT state FROM mom_iot.iot_parameter_command WHERE request_id=?",
                String.class,
                id);
    }

    int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM mom_iot." + table, Integer.class);
    }

    void prepare(String id, String scenario) throws Exception {
        ok(post("", body(id, scenario)));
    }

    void request(String id, String scenario) throws Exception {
        prepare(id, scenario);
        ok(action(id, "request"));
    }

    @Test
    void actualSerializedCommandContractKeepsReasonOnlyInOriginal() throws Exception {
        var input =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(body("UI-CONTRACT", "MATCH"));
        input.put("reason", " 首件参数确认\n第二班次复核 ");
        var prepared = post("", json.writeValueAsString(input));
        ok(prepared);
        String prepareRaw =
                prepared.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var root = json.readTree(prepareRaw).path("data");
        assertFalse(root.has("reason"));
        assertEquals(
                input.path("reason").asText(),
                json.readTree(root.path("requestJson").asText()).path("reason").asText());
        var listResult =
                mvc.perform(get("/process-parameters/commands").param("taskCode", "MT1"))
                        .andReturn();
        ok(listResult);
        ok(action("UI-CONTRACT", "request"));
        ok(action("UI-CONTRACT", "simulate"));
        var detailResult = mvc.perform(get("/process-parameters/commands/UI-CONTRACT")).andReturn();
        ok(detailResult);
        String output = System.getProperty("mom.parameter.contract-output");
        if (output != null) {
            var file = Path.of(output);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(
                    file,
                    json.writeValueAsString(
                            Map.of(
                                    "prepare",
                                    prepareRaw,
                                    "list",
                                    listResult
                                            .getResponse()
                                            .getContentAsString(
                                                    java.nio.charset.StandardCharsets.UTF_8),
                                    "detail",
                                    detailResult
                                            .getResponse()
                                            .getContentAsString(
                                                    java.nio.charset.StandardCharsets.UTF_8))));
        }
    }

    @Test
    void requestSuccessDoesNotInventAnyDeviceReceipt() throws Exception {
        request("C1", "MATCH");
        assertEquals("REQUESTED", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
        assertEquals(0, count("iot_parameter_sim_execution"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCH", "MISMATCH", "REJECT"})
    void simulatedFatesRemainExplicitAndSeparatelyPersisted(String scenario) throws Exception {
        request("C1", scenario);
        ok(action("C1", "simulate"));
        assertEquals(
                Map.of("MATCH", "VERIFIED", "MISMATCH", "MISMATCH", "REJECT", "REJECTED")
                        .get(scenario),
                state("C1"));
        assertEquals(
                "SIMULATION",
                db.queryForObject("SELECT mode FROM mom_iot.iot_parameter_command", String.class));
        assertEquals(scenario.equals("REJECT") ? 1 : 3, count("iot_parameter_receipt"));
        assertTrue(
                db.queryForObject(
                                "SELECT context_json FROM mom_iot.iot_parameter_command",
                                String.class)
                        .contains("2.50000000000000001"));
        ok(action("C1", "simulate"));
        assertEquals(1, count("iot_parameter_sim_execution"));
        assertEquals(
                scenario.equals("REJECT") ? 0 : 1,
                db.queryForObject(
                        "SELECT execution_count FROM mom_iot.iot_parameter_sim_execution",
                        Integer.class));
    }

    @Test
    void lostResponseRecoversOriginalDeviceJournalWithoutReexecuting() throws Exception {
        request("C1", "TIMEOUT_AFTER_EXECUTION");
        ok(action("C1", "simulate"));
        assertEquals("UNKNOWN", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
        assertEquals(1, count("iot_parameter_sim_execution"));
        assertEquals(400, post("", body("C2", "MATCH")).getResponse().getStatus());
        ok(action("C1", "recover"));
        assertEquals("VERIFIED", state("C1"));
        assertEquals(3, count("iot_parameter_receipt"));
        ok(action("C1", "recover"));
        assertEquals(3, count("iot_parameter_receipt"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT execution_count FROM mom_iot.iot_parameter_sim_execution",
                        Integer.class));
    }

    @Test
    void sameOriginalPrepareIsReplayButDifferentReasonOrActorIsRejected() throws Exception {
        prepare("C1", "MATCH");
        prepare("C1", "MATCH");
        assertEquals(1, count("iot_parameter_command"));
        assertEquals(
                400,
                post("", body("C1", "MATCH").replace("冻结参数联验", "替换原文")).getResponse().getStatus());
        login(8L, ALL);
        assertEquals(400, post("", body("C1", "MATCH")).getResponse().getStatus());
        assertEquals(1, count("iot_parameter_command"));
    }

    @Test
    void failedRemoteReplyWithDataDoesNotCreateCommand() throws Exception {
        when(mes.context(any())).thenReturn(R.ok(source).setCode(1));
        assertEquals(400, post("", body("C1", "MATCH")).getResponse().getStatus());
        assertEquals(0, count("iot_parameter_command"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "firmware"})
    void sourceOrFirmwareChangeBeforeRequestCannotSendStaleParameters(String changed)
            throws Exception {
        prepare("C1", "MATCH");
        if (changed.equals("source")) source.setNodeSnapshotFingerprint("d".repeat(64));
        else db.update("UPDATE mom_iot.iot_device SET firmware='FW2'");
        assertEquals(400, action("C1", "request").getResponse().getStatus());
        assertEquals("PREPARED", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
    }

    @Test
    void receiptSaveFailureRollsBackBusinessReceiptsButNotExecutedSimulatorJournal()
            throws Exception {
        request("C1", "MATCH");
        db.execute(
                "ALTER TABLE mom_iot.iot_parameter_receipt ADD CONSTRAINT fail_execution"
                        + " CHECK(stage <> 'EXECUTED')");
        assertEquals(400, action("C1", "simulate").getResponse().getStatus());
        assertEquals("UNKNOWN", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
        assertEquals(1, count("iot_parameter_sim_execution"));
        db.execute("ALTER TABLE mom_iot.iot_parameter_receipt DROP CONSTRAINT fail_execution");
        ok(action("C1", "recover"));
        assertEquals("VERIFIED", state("C1"));
        assertEquals(3, count("iot_parameter_receipt"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT execution_count FROM mom_iot.iot_parameter_sim_execution",
                        Integer.class));
    }

    @Test
    void recoverWithoutJournalCannotInventExecution() throws Exception {
        request("C1", "MATCH");
        ok(action("C1", "recover"));
        assertEquals("UNKNOWN", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
        assertEquals(0, count("iot_parameter_sim_execution"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "mesTask:show",
                "mesProduceTask:show",
                "iot_device_view",
                "iot_parameter_dispatch"
            })
    void eachOriginalOrDedicatedWritePermissionIsRequiredDespiteInnerHeader(String missing)
            throws Exception {
        login(7L, ALL.stream().filter(p -> !p.equals(missing)).toList());
        var result =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/process-parameters/commands")
                                        .header("from", "Y")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("C1", "MATCH")))
                        .andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(0, count("iot_parameter_command"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mesTask:show", "mesProduceTask:show", "iot_device_view"})
    void preparationOptionsRequireAllOriginalReadPermissions(String missing) throws Exception {
        login(7L, ALL.stream().filter(p -> !p.equals(missing)).toList());
        assertEquals(
                403,
                mvc.perform(
                                get("/process-parameters/commands/preparation-options")
                                        .param("taskCode", "MT1"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(0, count("iot_parameter_command"));
    }

    @Test
    void disabledSimulatorConfigurationCannotCreateCommand() throws Exception {
        Object bean =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                        app.getBean(type("IotParameterCommandService")));
        org.springframework.test.util.ReflectionTestUtils.setField(bean, "simulatorEnabled", false);
        assertEquals(400, post("", body("C1", "MATCH")).getResponse().getStatus());
        assertEquals(0, count("iot_parameter_command"));
    }

    @Test
    void anotherFactoryOrTenantCannotReadOriginalCommand() throws Exception {
        prepare("C1", "MATCH");
        FactoryContextHolder.setFactoryCode("F2");
        assertEquals(
                400,
                mvc.perform(get("/process-parameters/commands/C1"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(2L);
        assertEquals(
                400,
                mvc.perform(get("/process-parameters/commands/C1"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }

    @Test
    void executedJournalRequiresAllThreeRealStageTimes() throws Exception {
        request("C1", "MATCH");
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " mom_iot.iot_parameter_sim_execution(tenant_id,factory_code,request_id,command_hash,device_code,firmware_version,outcome,execution_count,values_json,received_at,executed_at,read_back_at)"
                                    + " SELECT"
                                    + " tenant_id,factory_code,request_id,context_hash,device_code,firmware_version,'EXECUTED',1,'{}',CURRENT_TIMESTAMP,NULL,NULL"
                                    + " FROM mom_iot.iot_parameter_command"));
    }

    @Test
    void receiptUsesPersistedTimestampPrecisionRatherThanInMemoryJournal() throws Exception {
        for (String column : List.of("received_at", "executed_at", "read_back_at"))
            db.execute(
                    "ALTER TABLE mom_iot.iot_parameter_sim_execution ALTER COLUMN "
                            + column
                            + " TIMESTAMP(0)");
        request("C1", "MATCH");
        ok(action("C1", "simulate"));
        assertEquals("VERIFIED", state("C1"));
        assertEquals(3, count("iot_parameter_receipt"));
    }

    @Test
    void inputCannotSmuggleReplacementParameterValues() throws Exception {
        String altered = body("C1", "MATCH");
        altered =
                altered.substring(0, altered.length() - 1)
                        + ",\"parameters\":[{\"identifier\":\"torque\",\"targetValue\":\"999\"}]}";
        assertEquals(400, post("", altered).getResponse().getStatus());
        assertEquals(0, count("iot_parameter_command"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit", "missing"})
    void incompleteOrWrongUnitReadbackCannotVerify(String changed) throws Exception {
        request("C1", "TIMEOUT_AFTER_EXECUTION");
        ok(action("C1", "simulate"));
        db.update(
                "UPDATE mom_iot.iot_parameter_sim_execution SET values_json=?",
                changed.equals("unit")
                        ? "{\"torque\":{\"value\":\"2.50000000000000001\",\"unit\":\"nm\"}}"
                        : "{}");
        ok(action("C1", "recover"));
        assertEquals("MISMATCH", state("C1"));
    }

    @Test
    void wrongDeviceVersionInOriginalJournalCannotBecomeReceipt() throws Exception {
        request("C1", "TIMEOUT_AFTER_EXECUTION");
        ok(action("C1", "simulate"));
        db.update("UPDATE mom_iot.iot_parameter_sim_execution SET firmware_version='FW2'");
        assertEquals(400, action("C1", "recover").getResponse().getStatus());
        assertEquals("UNKNOWN", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
    }

    @Test
    void readerWithoutNewWritePermissionCanInspectButNotControl() throws Exception {
        prepare("C1", "MATCH");
        login(7L, ALL.subList(0, 3));
        assertEquals(
                200,
                mvc.perform(get("/process-parameters/commands/C1"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(403, action("C1", "request").getResponse().getStatus());
        assertEquals("PREPARED", state("C1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertCommand", "updateState", "insertJournal", "insertReceipt"})
    void necessaryWriteReturningZeroCannotReportSuccessfulFact(String statement) throws Exception {
        if (statement.equals("insertCommand")) {
            zeroWrite.set(statement);
            assertEquals(400, post("", body("C1", "MATCH")).getResponse().getStatus());
            assertEquals(0, count("iot_parameter_command"));
            return;
        }
        prepare("C1", "MATCH");
        if (statement.equals("updateState")) {
            zeroWrite.set(statement);
            assertEquals(400, action("C1", "request").getResponse().getStatus());
            assertEquals("PREPARED", state("C1"));
            return;
        }
        ok(action("C1", "request"));
        zeroWrite.set(statement);
        assertEquals(400, action("C1", "simulate").getResponse().getStatus());
        assertEquals("UNKNOWN", state("C1"));
        assertEquals(0, count("iot_parameter_receipt"));
        assertEquals(
                statement.equals("insertReceipt") ? 1 : 0, count("iot_parameter_sim_execution"));
        if (statement.equals("insertReceipt")) {
            ok(action("C1", "recover"));
            assertEquals("VERIFIED", state("C1"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT execution_count FROM mom_iot.iot_parameter_sim_execution",
                            Integer.class));
        }
    }

    @Test
    void twoPreparedCommandsCannotBothBecomeActiveOnOneDevice() throws Exception {
        prepare("C1", "MATCH");
        prepare("C2", "MATCH");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String id : List.of("C1", "C2"))
                results.add(
                        executor.submit(
                                () -> {
                                    login(7L, ALL);
                                    try {
                                        start.await();
                                        return action(id, "request").getResponse().getStatus();
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                        TenantContextHolder.clear();
                                        FactoryContextHolder.clear();
                                    }
                                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 400), statuses);
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM mom_iot.iot_parameter_command WHERE"
                                    + " state='REQUESTED'",
                            Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }
}
