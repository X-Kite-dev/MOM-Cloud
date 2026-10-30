package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.plan.dto.PlanMrpShortageQueryDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.PlanMatMrpServiceImpl;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 公开缺料分析入口，生产计划来源由真实MyBatis/H2及租户拦截器筛选，其余端口提供固定事实。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanMrpSupplySourceEvidenceTest {

    @Mock
    private PlnSchedulingOperationMapper schedulingOperationMapper;

    @Mock
    private PlnSchedulingTaskMapper schedulingTaskMapper;

    @Mock
    private RemoteProcessRouteService routes;

    @Spy
    @InjectMocks
    private PlanMatMrpServiceImpl service;

    private JdbcTemplate db;

    private PlanMatMrpEntity mrp;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source = new DriverManagerDataSource("jdbc:h2:mem:mrp-source-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(PlnIssueMapper.class);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public Expression getTenantId() {
                return new LongValue(TenantContextHolder.getTenantId());
            }
        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(interceptor);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PlnIssueMapper.class);
        ReflectionTestUtils.setField(service, "plnIssueMapper", mapper);
        var table = TableInfoHelper.getTableInfo(PlnIssueEntity.class);
        var ddl = new StringBuilder("CREATE TABLE pln_issue(id bigint");
        for (var field : table.getFieldList()) {
            Class<?> type = field.getPropertyType();
            String sqlType = type == Long.class ? "bigint" : type == Integer.class ? "integer" : type == Boolean.class ? "boolean" : type == BigDecimal.class ? "decimal(24,8)" : type == LocalDate.class ? "date" : type == LocalDateTime.class ? "timestamp" : "varchar(1024)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sqlType);
        }
        db.execute(ddl.append(')').toString());
        mrp = new PlanMatMrpEntity();
        mrp.setId(21L);
        mrp.setFactoryCode("F1");
        mrp.setPlanDemandNo("D1");
        mrp.setMatCode("P1");
        mrp.setType("mes");
        mrp.setRemainingDemandNum(new BigDecimal("2"));
        mrp.setDemandDate(LocalDate.of(2026, 10, 26));
        mrp.setSubstituteStatus("NONE");
        doReturn(List.of(mrp)).when(service).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var operation = new PlnSchedulingOperationEntity();
        operation.setId(101L);
        operation.setTaskId(100L);
        operation.setIssueId(11L);
        operation.setFactoryCode("F1");
        operation.setLockStatus(true);
        operation.setProcessCode("CUT");
        operation.setRouteCode("R1");
        operation.setOperationSeq(1);
        operation.setScheduledStartTime(LocalDateTime.of(2026, 10, 25, 8, 0));
        operation.setScheduledEndTime(LocalDateTime.of(2026, 10, 25, 10, 0));
        when(schedulingOperationMapper.selectList(any())).thenAnswer(call -> {
            operation.setIssueNo(mrp.getSupplySourceNo());
            return List.of(operation);
        });
        var task = new PlnSchedulingTaskEntity();
        task.setId(100L);
        task.setMode("official");
        task.setTaskStatus("SUCCESS");
        when(schedulingTaskMapper.selectList(any())).thenReturn(List.of(task));
        var assignment = new MdmProcessProcedureMatEntity();
        assignment.setRouteCode("R1");
        assignment.setProcessCode("CUT");
        assignment.setMaterialCode("P1");
        when(routes.getMaterialAssignments(any())).thenReturn(R.ok(List.of(assignment)));
    }

    @AfterEach
    void cleanup() {
        if (db != null)
            db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    private void issue(String mrpId, String demand, String issueNo, String factory, long tenant, String product) {
        mrp.setSupplySourceNo(issueNo);
        db.update("INSERT INTO pln_issue(id,tenant_id,factory_code,del_flag,source_mrp_id,source_demand_no,issue_no,product_code,num,finish_num,status,lock_task_id) VALUES(11,?,?,'0',?,?,?,?,2,0,'1',100)", tenant, factory, "NULL".equals(mrpId) ? null : Long.valueOf(mrpId), "NULL".equals(demand) ? null : demand, issueNo, product);
    }

    private PlanMrpShortageQueryDTO query(int version) {
        var query = new PlanMrpShortageQueryDTO();
        query.setFactoryCode("F1");
        query.setPlanDemandNo("D1");
        query.setSupplyEvidenceVersion(version);
        return query;
    }

    @ParameterizedTest
    @CsvSource({ "21,D1,MRP-21,F1,1,P1,true", "NULL,NULL,D1,F1,1,P1,true", "22,OTHER,D1,F1,1,P1,false", "21,D1,MRP-21,F2,1,P1,false", "21,D1,MRP-21,F1,2,P1,false", "21,D1,MRP-21,F1,1,OTHER,false" })
    void existingProductionSupplyUsesModernSourceOrOnlyNullSourceFallback(String source, String demand, String no, String factory, long tenant, String product, boolean found) {
        issue(source, demand, no, factory, tenant, product);
        var line = service.shortageAnalysis(query(3)).getLines().get(0);
        assertEquals(found ? new BigDecimal("2") : BigDecimal.ZERO, line.getPlannedSupplyNum().stripTrailingZeros());
        assertEquals(found ? no : "", line.getSupplyPlanNos());
    }

    @ParameterizedTest
    @CsvSource({ "21,D1,MRP-21,F1,1,true", "NULL,NULL,D1,F1,1,true", "22,OTHER,D1,F1,1,false", "21,D1,MRP-21,F2,1,false", "21,D1,MRP-21,F1,2,false" })
    void impactedOperationsUseDemandSourceWithoutInventingLegacyOrCrossScopeMatches(String source, String demand, String no, String factory, long tenant, boolean found) {
        issue(source, demand, no, factory, tenant, "P1");
        var line = service.shortageAnalysis(query(4)).getLines().get(0);
        assertEquals(found ? "MAPPED" : "APS_NOT_SCHEDULED", line.getOperationImpactStatus());
        assertEquals(found ? 1 : 0, line.getImpactedOperationCount());
    }
}
