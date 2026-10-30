package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesErpPostingOutboxMapper;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.feign.RemotePlanFormalContractService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MesLocalPlanningTest {

    private MesFormalPlanReceiptVerifier verifier;

    private MesTaskReceiveDTO receive;

    private MdmFactoryPlanningPolicyDTO policy;

    private PlnFormalPlanContractDTO contract;

    private MesErpPostingOutboxServiceImpl postings;

    private MesErpPostingOutboxMapper mapper;

    private MesTaskEntity task;

    @BeforeEach
    void setup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10");
        policy.setVersionNo(1);
        policy.setContentFingerprint("a".repeat(64));
        policy.setFactoryCode("F1");
        policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        var policies = mock(RemoteMdmFactoryPlanningPolicyService.class);
        when(policies.active("F1")).thenReturn(R.ok(policy));
        contract = new PlnFormalPlanContractDTO();
        contract.setPlanningPolicyId("10");
        contract.setPlanningPolicyVersion(1);
        contract.setPlanningPolicyFingerprint("a".repeat(64));
        contract.setSourceContentFingerprint("b".repeat(64));
        contract.setSourceSystemCode("MOM");
        contract.setSourcePlanNo("MRP-1");
        contract.setSourcePlanVersion("1");
        var line = new PlnFormalPlanContractDTO.Line();
        line.setId("21");
        line.setLineNo("L1");
        line.setSourceDemandNo("D1");
        line.setUnitCode("EA");
        line.setContentFingerprint("c".repeat(64));
        line.setSupplyType("MAKE");
        line.setProductCode("P1");
        line.setPlannedQuantity(new BigDecimal("2"));
        contract.setLines(List.of(line));
        var contracts = mock(RemotePlanFormalContractService.class);
        when(contracts.verified("C1", 1)).thenReturn(R.ok(contract));
        verifier = new MesFormalPlanReceiptVerifier(policies, contracts);
        receive = new MesTaskReceiveDTO();
        receive.setFactoryCode("F1");
        receive.setPlanningPolicyId(10L);
        receive.setPlanningPolicyVersion(1);
        receive.setPlanningPolicyFingerprint("a".repeat(64));
        receive.setFormalPlanContractId(21L);
        receive.setFormalPlanContractNo("C1");
        receive.setFormalPlanContractVersion(1);
        receive.setFormalPlanContractFingerprint("b".repeat(64));
        receive.setMrpAuthority("MOM_MRP");
        receive.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        receive.setSourceSystem("MOM");
        receive.setSourcePlanNo("MRP-1");
        receive.setSourcePlanVersion("1");
        receive.setMatCode("P1");
        receive.setNoticeNum(new BigDecimal("2"));
        receive.setProductionOrderQuantity(new BigDecimal("2"));
        receive.setProductionOrder("MOM-ORDER-1");
        mapper = mock(MesErpPostingOutboxMapper.class);
        postings = spy(new MesErpPostingOutboxServiceImpl(new ObjectMapper().findAndRegisterModules()));
        ReflectionTestUtils.setField(postings, "baseMapper", mapper);
        doReturn(true).when(postings).save(any(MesErpPostingOutboxEntity.class));
        task = new MesTaskEntity();
        task.setId(30L);
        task.setCode("MES-1");
        task.setFactoryCode("F1");
        task.setPlanningPolicyId(10L);
        task.setPlanningPolicyVersion(1);
        task.setPlanningPolicyFingerprint("a".repeat(64));
        task.setFormalPlanContractNo("C1");
        task.setFormalPlanContractVersion(1);
        task.setFormalPlanContractFingerprint("b".repeat(64));
        task.setMrpAuthority("MOM_MRP");
        task.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        task.setProductionOrder("MOM-ORDER-1");
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void localReceiptStillRequiresVerifiedContractAndAcceptsLocalOrder() {
        assertDoesNotThrow(() -> verifier.verify(receive));
    }

    @Test
    void localReceiptRejectsMixedErpOrder() {
        receive.setErpProductionOrderNo("ERP-1");
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void localReceiptRejectsErpContractSource() {
        contract.setSourceSystemCode("ERP");
        receive.setSourceSystem("ERP");
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void unknownSyncModeCannotFallThroughToLocalOrder() {
        policy.setProductionOrderSyncMode("UNKNOWN");
        receive.setProductionOrderSyncMode("UNKNOWN");
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void localReceiptRetainsQuantityGuard() {
        receive.setNoticeNum(new BigDecimal("3"));
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void localReceiptRetainsPolicyFingerprintGuard() {
        receive.setPlanningPolicyFingerprint("c".repeat(64));
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void localCompletionKeepsReportButCreatesNoErpPosting() {
        var report = new MesTaskReportEntity();
        report.setId(41L);
        report.setRequestId("GOOD-1");
        report.setReportNum(new BigDecimal("2"));
        report.setReportTime(LocalDateTime.of(2026, 10, 24, 22, 0));
        postings.captureCompletion(task, report);
        assertEquals(new BigDecimal("2"), report.getReportNum());
        verify(postings, never()).save(any(MesErpPostingOutboxEntity.class));
        verifyZeroInteractions(mapper);
    }

    @Test
    void localMaterialCaptureCreatesNoErpPosting() {
        var feed = new MesTaskReportFeedEntity();
        feed.setId(42L);
        feed.setFeedNum(BigDecimal.ONE);
        postings.captureMaterial(task, feed);
        assertEquals(BigDecimal.ONE, feed.getFeedNum());
        verify(postings, never()).save(any(MesErpPostingOutboxEntity.class));
        verifyZeroInteractions(mapper);
    }

    @Test
    void localScrapCaptureCreatesNoErpPosting() {
        var report = new MesTaskReportUnqualifiedEntity();
        report.setId(43L);
        report.setRequestId("NG-1");
        report.setUnqualifiedNum(BigDecimal.ONE);
        postings.captureScrap(task, report);
        verify(postings, never()).save(any(MesErpPostingOutboxEntity.class));
        verifyZeroInteractions(mapper);
    }

    @Test
    void postingModeStillCapturesCompletion() {
        task.setProductionOrderSyncMode("MOM_ORDER_WITH_POSTING");
        var report = new MesTaskReportEntity();
        report.setId(41L);
        report.setRequestId("GOOD-1");
        report.setReportNum(BigDecimal.ONE);
        postings.captureCompletion(task, report);
        verify(postings).save(any(MesErpPostingOutboxEntity.class));
    }
}
