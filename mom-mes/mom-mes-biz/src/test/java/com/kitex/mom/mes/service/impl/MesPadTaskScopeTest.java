package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.controller.MesTaskArrangeController;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesTaskArrangeStatusDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 公开现场查询执行实际Mapper；H2的测试函数只适配PG string_to_array，不改业务谓词。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MesPadTaskScopeTest {
    @Mock private MesActualFactoryAccessService factories;
    @Mock private MesExecutionTemplateService templates;
    @Mock private MesTaskService tasks;
    @InjectMocks private MesTaskArrangeServiceImpl service;
    private JdbcTemplate db;
    private MesTaskArrangeController controller;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = new MomUser(7L, "Glue", 1L, null, null, null, "Glue", null,
                1L, "unused", true, true, "USER", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(factories.require("read")).thenReturn("F1");
        when(templates.contract(any(MesTaskArrangeEntity.class))).thenReturn(new MesExecutionContractDTO());
        var source = new DriverManagerDataSource("jdbc:h2:mem:pad-scope-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        db.execute("CREATE ALIAS STRING_TO_ARRAY FOR 'com.kitex.mom.mes.service.impl.MesPadTaskScopeTest.csv'");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(MesTaskArrangeMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        ReflectionTestUtils.setField(service, "baseMapper", new SqlSessionTemplate(factory.getObject()).getMapper(MesTaskArrangeMapper.class));
        var table = TableInfoHelper.getTableInfo(MesTaskArrangeEntity.class);
        var ddl = new StringBuilder("create table mes_task_arrange (").append(table.getKeyColumn()).append(" bigint");
        for (var field : table.getFieldList()) {
            Class<?> type = field.getPropertyType();
            String sqlType = type == Long.class ? "bigint" : type == Integer.class ? "integer"
                    : type == BigDecimal.class ? "decimal(24,8)" : type == Boolean.class ? "boolean"
                    : type == LocalDate.class ? "date" : type == LocalDateTime.class ? "timestamp" : "varchar(10000)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sqlType);
        }
        db.execute(ddl.append(')').toString());
        controller = new MesTaskArrangeController(service);
    }

    @AfterEach
    void cleanup() {
        if (db != null) db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void multiWorkerAndMultiCandidateTasksHaveTheSameScopedListAndStatusCounts() {
        insert(1, "Bob,Glue", "S2,S1", null, "UN_RECEIVE", false);
        insert(2, "Glue", "S1", "S1", "PRODUCT", true);
        insert(3, "Glue", "S1", "S1", "PAUSE", true);
        insert(4, "Glue2", "S1", null, "UN_RECEIVE", false);
        insert(5, "Glue", "S10", null, "UN_RECEIVE", false);
        insert(6, "Glue", "S1", null, "UN_RECEIVE", false);
        db.update("update mes_task_arrange set factory_code='F2' where id=6");
        insert(7, "Glue", "S1", null, "UN_RECEIVE", false);
        db.update("update mes_task_arrange set tenant_id=2 where id=7");
        insert(8, "Glue", "S1", null, "UN_RECEIVE", false);
        db.update("update mes_task_arrange set shift_code='OTHER' where id=8");
        insert(9, "Glue", "S1", null, "UN_RECEIVE", false);
        db.update("update mes_task_arrange set workshop_code='OTHER' where id=9");

        assertEquals(Set.of(1L, 2L, 3L), pageIds(query()));
        assertEquals(Map.of("", 3L, "UN_RECEIVE", 1L, "RECEIVE", 0L, "PRODUCT", 1L,
                "PAUSE", 1L, "CLOSE", 0L, "FINISH", 0L), counts());
    }

    @Test
    void aTaskAlreadyAcceptedAtAnotherCandidateStationIsAbsentFromThisStation() {
        insert(1, "Glue", "S1,S2", "S2", "RECEIVE", false);
        insert(2, "Glue", "S1,S2", null, "UN_RECEIVE", false);
        assertEquals(Set.of(2L), pageIds(query()));
        assertEquals(1L, counts().get(""));
        assertEquals(0L, counts().get("RECEIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UN_RECEIVE", "RECEIVE", "FINISH", "CLOSE"})
    void tasksNotInExecutionNeverOpenTheProductionWorkbench(String status) {
        insert(1, "Glue", "S1", "S1", status, false);
        assertNull(assertDoesNotThrow(() -> service.getCurrentTask("SHIFT1", "SHOP1", "S1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anotherActualStation", "missingActualStation", "notStarted"})
    void aProductionStatusAloneCannotSelectAnUnboundOrDifferentStationTask(String scenario) {
        insert(1, "Glue", "S1,S2", "anotherActualStation".equals(scenario) ? "S2"
                : "missingActualStation".equals(scenario) ? null : "S1", "PRODUCT", !"notStarted".equals(scenario));
        assertNull(assertDoesNotThrow(() -> service.getCurrentTask("SHIFT1", "SHOP1", "S1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT", "PAUSE"})
    void onlyTheStartedActualStationTaskReachesWorkOrderHydration(String status) {
        insert(1, "Glue", "S1", "S1", "RECEIVE", false);
        insert(2, "Glue", "S1,S2", "S1", status, true);
        // 正确候选应继续校验所属工单；夹具刻意没有工单，不能误报候选冲突或静默返回空。
        var error = assertThrows(CheckedException.class, () -> service.getCurrentTask("SHIFT1", "SHOP1", "S1"));
        assertEquals("当前派工所属工单不存在或不属于当前工厂，请核对来源", error.getMessage());
    }

    @Test
    void multipleActualActiveTasksAreRejectedInsteadOfChoosingOne() {
        insert(1, "Glue", "S1", "S1", "PRODUCT", true);
        insert(2, "Glue", "S1", "S1", "PAUSE", true);
        var error = assertThrows(CheckedException.class, () -> service.getCurrentTask("SHIFT1", "SHOP1", "S1"));
        assertTrue(error.getMessage().contains("多份实名在制派工"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shift", "workshop", "station"})
    void listAndCountsRequireACompleteStationContext(String missing) {
        var query = query();
        if ("shift".equals(missing)) query.setShiftCode("");
        if ("workshop".equals(missing)) query.setWorkshopCode("");
        if ("station".equals(missing)) query.setWorksiteCodes("");
        assertThrows(CheckedException.class, () -> pageIds(query));
        assertThrows(CheckedException.class, () -> service.queryStatusList(query.getWorksiteCodes(), query.getShiftCode(), query.getWorkshopCode()));
    }

    private Map<String, Long> counts() {
        return service.queryStatusList("S1", "SHIFT1", "SHOP1").stream()
                .collect(Collectors.toMap(MesTaskArrangeStatusDTO::getStatusName, MesTaskArrangeStatusDTO::getNum));
    }

    private Set<Long> pageIds(MesTaskArrangeEntity query) {
        return controller.padPage(new Page<>(1, 50), query).getData().getRecords().stream()
                .map(MesTaskArrangeEntity::getId).collect(Collectors.toSet());
    }

    private MesTaskArrangeEntity query() {
        var query = new MesTaskArrangeEntity();
        query.setShiftCode("SHIFT1");
        query.setWorkshopCode("SHOP1");
        query.setWorksiteCodes("S1");
        return query;
    }

    private void insert(long id, String workers, String candidates, String actual, String status, boolean started) {
        db.update("insert into mes_task_arrange(id,tenant_id,factory_code,del_flag,worker_code,worksite_codes,"
                + "actual_worksite_code,shift_code,workshop_code,status,start_time,task_code,code) "
                + "values(?,1,'F1','0',?,?,?,'SHIFT1','SHOP1',?,?,?,?)", id, workers, candidates, actual, status,
                started ? LocalDateTime.of(2026, 10, 24, 9, 0) : null, "TASK1", "A" + id);
    }

    public static String[] csv(String value, String delimiter) {
        return value == null ? null : value.split(Pattern.quote(delimiter), -1);
    }
}
