package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingBatchAllocationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingOperationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper;
import com.kitex.mom.plan.service.impl.PlnProductDemandServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/** 公开接收入口执行实际MyBatis查询，防止新来源号和旧计划号回退遗漏执行事实。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlnProductDemandSourceGuardTest {

    @Mock private PlnIssueService issueService;
    @Mock private PlanMatMrpService mrpService;
    @Mock private PlanMrpInventoryPeggingService peggingService;
    @Mock private RemoteMesTaskService mesService;
    @Mock private PlnSchedulingBatchAllocationMapper allocationMapper;
    @Mock private PlnSchedulingTaskDetailMapper detailMapper;
    @Mock private PlnSchedulingOperationMapper operationMapper;
    @Spy @InjectMocks private PlnProductDemandServiceImpl service;

    private JdbcTemplate db;

    @BeforeEach
    void setup() throws Exception {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        var source = new DriverManagerDataSource("jdbc:h2:mem:demand-source-guard-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.addMapper(PlnIssueMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var sessions = new SqlSessionTemplate(factory.getObject());
        var mapper = sessions.getMapper(PlnIssueMapper.class);
        var table = TableInfoHelper.getTableInfo(PlnIssueEntity.class);
        var ddl = new StringBuilder("create table pln_issue (").append(table.getKeyColumn()).append(" bigint");
        for (var field : table.getFieldList()) {
            ddl.append(',').append(field.getColumn()).append(' ').append(sqlType(field.getPropertyType()));
        }
        db.execute(ddl.append(')').toString());
        when(issueService.list(any(Wrapper.class)))
                .thenAnswer(call -> mapper.selectList(call.<Wrapper<PlnIssueEntity>>getArgument(0)));
        when(mesService.omsLife(anyString())).thenReturn(R.ok(List.of()));

        var demand = new PlnProductDemandEntity();
        demand.setId(1L);
        demand.setDemandNo("D1");
        demand.setFactoryCode("F1");
        demand.setPlanState("0");
        demand.setCompleteNum(BigDecimal.ZERO);
        doReturn(demand).when(service).getOne(any(Wrapper.class));
        doReturn(0L).when(service).count(any(Wrapper.class));
    }

    @AfterEach
    void clearContext() {
        if (db != null) db.execute("SHUTDOWN");
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @ParameterizedTest(name = "source={0}, issue={1}, status={2}, finished={3}")
    @CsvSource(value = {
            "D1, PLAN-NEW, 1, 0, true",
            "NULL, D1, 1, 0, true",
            "OTHER, PLAN-OTHER, 1, 0, false",
            "D1, PLAN-CLOSED, 4, 0, false",
            "D1, PLAN-FINISHED, 4, 1, true",
            "OTHER, D1, 1, 0, false"
    }, nullValues = "NULL")
    void linkedExecutionFactsBlockReceptionWhileUnrelatedOrPristineClosedPlansReachWarehouseValidation(
            String sourceNo, String issueNo, String status, int finished, boolean blocked) {
        db.update("insert into pln_issue(id,tenant_id,factory_code,del_flag,source_demand_no,issue_no,status,finish_num) "
                + "values(1,1,'F1','0',?,?,?,?)", sourceNo, issueNo, status, finished);

        var error = assertThrows(CheckedException.class, () -> service.receive(new Long[]{1L}));

        assertEquals(blocked ? "产品需求已关联生产计划或APS，不能直接修改或重算"
                : "产品需求[D1]必须指定需求领料仓库后才能接收或运算MRP", error.getMessage());
    }

    private static String sqlType(Class<?> type) {
        if (type == Long.class) return "bigint";
        if (type == Integer.class) return "integer";
        if (type == BigDecimal.class) return "decimal(24,8)";
        if (type == Boolean.class) return "boolean";
        if (type == LocalDate.class) return "date";
        if (type == LocalDateTime.class) return "timestamp";
        return "varchar(512)";
    }
}
