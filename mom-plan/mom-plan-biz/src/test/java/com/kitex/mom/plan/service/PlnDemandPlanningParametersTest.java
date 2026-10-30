package com.kitex.mom.plan.service;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.plan.dto.PlnDemandPlanningParametersDTO;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.PlnProductDemandServiceImpl;
import com.kitex.mom.plan.service.impl.PlnMpsService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 实际需求Mapper读写验证白名单、服务器指纹和工厂/租户边界；远端事实由端口夹具提供。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlnDemandPlanningParametersTest {
    @Mock private RemoteWarehouseService warehouses;
    @Mock private RemoteMesTaskService mes;
    @Mock private PlnIssueService issues;
    @Mock private PlanMatMrpService mrp;
    @Mock private PlanMrpInventoryPeggingService pegging;
    @Mock private PlnSchedulingBatchAllocationMapper allocations;
    @Mock private PlnSchedulingTaskDetailMapper details;
    @Mock private PlnSchedulingOperationMapper operations;
    @Mock private PlnProductDemandChangeMapper changes;
    @Mock private OmsDeliveryIssueMapper deliveries;
    @Mock private OmsDeliveryWithdrawalMapper withdrawals;
    @Mock private PlnMpsService mps;
    @InjectMocks private PlnProductDemandServiceImpl service;

    private JdbcTemplate db;
    private PlnProductDemandMapper mapper;
    private MdmWarehouseEntity warehouse;

    @BeforeEach
    void setup() throws Exception {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("planner", null, List.of()));
        var source = new DriverManagerDataSource("jdbc:h2:mem:demand-planning-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        var globalConfig = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setMetaObjectHandler(new MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(PlnProductDemandMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PlnProductDemandMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        var table = TableInfoHelper.getTableInfo(PlnProductDemandEntity.class);
        var ddl = new StringBuilder("create table pln_product_demand (").append(table.getKeyColumn()).append(" bigint");
        for (var field : table.getFieldList()) {
            Class<?> type = field.getPropertyType();
            String sqlType = type == Long.class ? "bigint" : type == Integer.class ? "integer"
                    : type == BigDecimal.class ? "decimal(24,8)" : type == Boolean.class ? "boolean"
                    : type == LocalDate.class ? "date" : type == LocalDateTime.class ? "timestamp(9)" : "varchar(512)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sqlType);
        }
        db.execute(ddl.append(')').toString());
        db.update("insert into pln_product_demand(id,tenant_id,factory_code,del_flag,demand_no,sale_order_code,"
                + "sale_order_code_details,product_code,product_name,customer_code,customer_name,need_num,"
                + "plan_end_date,plan_state,complete_num,mrp_priority,update_time) "
                + "values(1,1,'F1','0','D1','ORDER1',11,'P1','产品1','C1','客户1',2,'2026-10-26','0',0,100,"
                + "TIMESTAMP '2026-10-24 10:20:30.123456789')");
        when(mes.omsLife(anyString())).thenReturn(R.ok(List.of()));
        warehouse = new MdmWarehouseEntity();
        warehouse.setTenantId(1L);
        warehouse.setFactoryCode("F1");
        warehouse.setWarehouseCode("W1");
        warehouse.setWarehouseName("领料仓1");
        warehouse.setStatus(true);
        when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse));
    }

    @AfterEach
    void cleanup() {
        if (db != null) db.execute("SHUTDOWN");
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesOnlyPlanningFieldsAndInvalidatesTheOldFingerprint() {
        var before = mapper.selectById(1L);
        var snapshot = planningSnapshot();
        assertEquals("D1", snapshot.getDemandNo());
        assertEquals("ORDER1", snapshot.getSaleOrderCode());
        assertTrue(snapshot.getEvidenceFingerprint().matches("[a-f0-9]{64}"));

        assertTrue(service.updatePlanningParameters(command(snapshot.getEvidenceFingerprint())));

        var after = mapper.selectById(1L);
        assertEquals("W1", after.getDemandWarehouseCode());
        assertEquals("领料仓1", after.getDemandWarehouseName());
        assertEquals(200, after.getMrpPriority());
        assertEquals("planner", after.getUpdateBy());
        assertNotEquals(before.getUpdateTime(), after.getUpdateTime());
        assertEquals(authoritativeFields(before), authoritativeFields(after));
        assertNotEquals(snapshot.getEvidenceFingerprint(), service.getPlanningParameters(1L).getEvidenceFingerprint());
        assertThrows(CheckedException.class, () -> service.updatePlanningParameters(command(snapshot.getEvidenceFingerprint())));
    }

    @Test
    void sourceChangeIncludingSubMillisecondAuditTimeRejectsTheStaleForm() {
        var snapshot = planningSnapshot();
        db.update("update pln_product_demand set update_time=TIMESTAMP '2026-10-24 10:20:30.123456790' where id=1");
        assertThrows(CheckedException.class, () -> service.updatePlanningParameters(command(snapshot.getEvidenceFingerprint())));
        assertNull(mapper.selectById(1L).getDemandWarehouseCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingFactory", "missingTenant", "skipFactory", "skipTenant", "otherFactory", "otherTenant"})
    void scopeCannotBeMissingSkippedOrCrossed(String scenario) {
        var command = command(planningSnapshot().getEvidenceFingerprint());
        switch (scenario) {
            case "missingFactory" -> FactoryContextHolder.clear();
            case "missingTenant" -> TenantContextHolder.clear();
            case "skipFactory" -> FactoryContextHolder.setFactorySkip();
            case "skipTenant" -> TenantContextHolder.setTenantSkip();
            case "otherFactory" -> FactoryContextHolder.setFactoryCode("F2");
            case "otherTenant" -> TenantContextHolder.setTenantId(2L);
            default -> fail(scenario);
        }
        assertThrows(CheckedException.class, () -> service.getPlanningParameters(1L));
        assertThrows(CheckedException.class, () -> service.updatePlanningParameters(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {"received", "completed", "manual", "otherSource", "mps", "mrp", "pendingDelivery", "pendingWithdrawal", "pendingChange"})
    void sourceStateAndDownstreamFactsPreventPlanningChanges(String scenario) {
        var command = command(planningSnapshot().getEvidenceFingerprint());
        switch (scenario) {
            case "received" -> db.update("update pln_product_demand set plan_state='1'");
            case "completed" -> db.update("update pln_product_demand set complete_num=1");
            case "manual" -> db.update("update pln_product_demand set sale_order_code=null,sale_order_code_details=null");
            case "otherSource" -> db.update("update pln_product_demand set source_type='QMS',source_id=9");
            case "mps" -> when(mps.hasActiveLine(1L)).thenReturn(true);
            case "mrp" -> when(mrp.count(any())).thenReturn(1L);
            case "pendingDelivery" -> when(deliveries.selectCount(any())).thenReturn(1L);
            case "pendingWithdrawal" -> when(withdrawals.selectCount(any())).thenReturn(1L);
            case "pendingChange" -> when(changes.selectCount(any())).thenReturn(1L);
            default -> fail(scenario);
        }
        assertThrows(CheckedException.class, () -> service.updatePlanningParameters(command));
        assertNull(mapper.selectById(1L).getDemandWarehouseCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "otherFactory", "missingFactory", "otherTenant", "missingTenant", "unavailable", "priorityLow", "priorityHigh", "missingPriority", "missingWarehouse", "missingFingerprint", "malformedFingerprint"})
    void invalidWarehouseOrPayloadCannotPersist(String scenario) {
        var command = command(planningSnapshot().getEvidenceFingerprint());
        switch (scenario) {
            case "disabled" -> warehouse.setStatus(false);
            case "otherFactory" -> warehouse.setFactoryCode("F2");
            case "missingFactory" -> warehouse.setFactoryCode(null);
            case "otherTenant" -> warehouse.setTenantId(2L);
            case "missingTenant" -> warehouse.setTenantId(null);
            case "unavailable" -> when(warehouses.getByCode("W1")).thenReturn(R.failed("unavailable"));
            case "priorityLow" -> command.setMrpPriority(0);
            case "priorityHigh" -> command.setMrpPriority(1000);
            case "missingPriority" -> command.setMrpPriority(null);
            case "missingWarehouse" -> command.setDemandWarehouseCode(" ");
            case "missingFingerprint" -> command.setExpectedEvidenceFingerprint("");
            case "malformedFingerprint" -> command.setExpectedEvidenceFingerprint("invalid");
            default -> fail(scenario);
        }
        assertThrows(CheckedException.class, () -> service.updatePlanningParameters(command));
        assertNull(mapper.selectById(1L).getDemandWarehouseCode());
    }

    private PlnDemandPlanningParametersDTO.Snapshot planningSnapshot() {
        return assertDoesNotThrow(() -> service.getPlanningParameters(1L));
    }

    private PlnDemandPlanningParametersDTO.UpdateCommand command(String fingerprint) {
        var command = new PlnDemandPlanningParametersDTO.UpdateCommand();
        command.setDemandId(1L);
        command.setDemandWarehouseCode(" W1 ");
        command.setMrpPriority(200);
        command.setExpectedEvidenceFingerprint(fingerprint);
        return command;
    }

    private Map<String, Object> authoritativeFields(PlnProductDemandEntity row) {
        Map<String, Object> values = BeanUtil.beanToMap(row);
        for (String field : List.of("demandWarehouseCode", "demandWarehouseName", "mrpPriority", "updateTime", "updateBy")) {
            values.remove(field);
        }
        return values;
    }
}
