package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.PlnFormalPlanContractServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlnLocalFormalContractTest {

    private PlnFormalPlanContractServiceImpl service;

    private PlnErpIntegrationOutboxService outbox;

    private MdmFactoryPlanningPolicyDTO policy;

    private final List<PlnFormalPlanContractEntity> stored = new ArrayList<>();

    @BeforeEach
    void setup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "local-contract");
        for (Class<?> type : List.of(PlanMatMrpEntity.class, PlnProductDemandEntity.class, PlnFormalPlanContractEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10");
        policy.setVersionNo(1);
        policy.setFactoryCode("F1");
        policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        policy.setContentFingerprint("a".repeat(64));
        policy.setSubstituteGovernanceMode("MOM_APPROVAL");
        var authority = mock(PlnFactoryPlanningAuthorityService.class);
        when(authority.requireCurrentPolicy("F1")).thenReturn(policy);
        when(authority.revalidate(policy)).thenReturn(policy);
        var mrpMapper = mock(PlanMatMrpMapper.class);
        var mrp = new PlanMatMrpEntity();
        mrp.setId(21L);
        mrp.setStatus(false);
        mrp.setPlanDemandNo("D1");
        mrp.setMatUnit("piece");
        mrp.setBomPathSnapshot("""
                [{"netDemandNum":2,"bomLevel":1,"bomPath":"ROOT:P1/M1","parentBomPath":"ROOT:P1",
                  "materialCode":"M1","materialName":"Material","supplyType":"pms","requirementDate":"2026-10-26"}]
                """);
        mrp.setSubstituteStatus("NONE");
        when(mrpMapper.selectList(any())).thenReturn(List.of(mrp));
        var demands = mock(PlnProductDemandMapper.class);
        var demand = new PlnProductDemandEntity();
        demand.setId(20L);
        demand.setDemandNo("D1");
        demand.setProductCode("P1");
        demand.setProductName("Product");
        demand.setProductUnit("piece");
        demand.setNeedNum(new BigDecimal("2"));
        demand.setPlanEndDate(LocalDate.of(2026, 10, 26));
        when(demands.selectList(any())).thenReturn(List.of(demand));
        outbox = mock(PlnErpIntegrationOutboxService.class);
        service = spy(new PlnFormalPlanContractServiceImpl(mock(PlnErpPlanInboxMapper.class),
                mock(PlnErpProductionOrderReceiptMapper.class), mrpMapper, demands, authority, outbox,
                new ObjectMapper().findAndRegisterModules()));
        ReflectionTestUtils.setField(service, "baseMapper", mock(PlnFormalPlanContractMapper.class));
        doReturn(List.of()).when(service).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        doAnswer(invocation -> {
            stored.addAll(invocation.getArgument(0));
            return true;
        }).when(service).saveBatch(anyCollection());
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void localModeStillPublishesFormalContractButCreatesNoErpPosting() {
        var result = service.publishMomMrpContract("MRP-1", "PUBLISH-1");
        assertEquals(1, result.size());
        assertEquals(2, stored.size());
        var row = stored.get(0);
        assertEquals("PUBLISHED", row.getContractStatus());
        assertEquals("VERIFIED", row.getVerificationStatus());
        assertEquals("D1", row.getSourceDemandNo());
        assertEquals("P1", row.getProductCode());
        assertEquals(0, new BigDecimal("2").compareTo(row.getPlannedQuantity()));
        assertEquals(10L, row.getPlanningPolicyId());
        assertEquals("a".repeat(64), row.getPlanningPolicyFingerprint());
        assertEquals("MOM", row.getSourceSystemCode());
        verifyZeroInteractions(outbox);
    }

    @Test
    void postingModeStillEmitsCommitment() {
        policy.setProductionOrderSyncMode("MOM_ORDER_WITH_POSTING");
        policy.setErpSystemCode("ERP");
        policy.setExternalFactoryCode("EXT-F1");
        service.publishMomMrpContract("MRP-1", "PUBLISH-1");
        verify(outbox).enqueue(argThat(e -> "COMMITMENT_UPDATE".equals(e.getEventType()) && "ERP".equals(e.getErpSystemCode())));
    }

    @Test
    void erpOrderRequiredStillRequestsOrderAndCommitment() {
        policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        policy.setErpSystemCode("ERP");
        policy.setExternalFactoryCode("EXT-F1");
        service.publishMomMrpContract("MRP-1", "PUBLISH-1");
        verify(outbox).enqueue(argThat(e -> "COMMITMENT_UPDATE".equals(e.getEventType())));
        verify(outbox).enqueue(argThat(e -> "PRODUCTION_ORDER_REQUEST".equals(e.getEventType())));
    }
}
