package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.feign.RemotePlanFormalContractService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际接单合同核验器；MDM/PLAN已核验读取为受控远端端口。 */
class MesFormalPlanAllocationTest {
    private MesFormalPlanReceiptVerifier verifier;
    private MesTaskReceiveDTO receive;
    private PlnFormalPlanContractDTO contract;
    private MdmFactoryPlanningPolicyDTO policy;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10");
        policy.setVersionNo(1);
        policy.setFactoryCode("F1");
        policy.setContentFingerprint("a".repeat(64));
        policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        contract = new PlnFormalPlanContractDTO();
        contract.setContractNo("C1");
        contract.setContractVersion(1);
        contract.setVerificationStatus("VERIFIED");
        contract.setContractStatus("PUBLISHED");
        contract.setSourceFactoryCode("F1");
        contract.setPlanningPolicyId("10");
        contract.setPlanningPolicyVersion(1);
        contract.setPlanningPolicyFingerprint("a".repeat(64));
        contract.setSourceContentFingerprint("b".repeat(64));
        contract.setSourceSystemCode("MOM");
        contract.setSourcePlanNo("MRP-1");
        contract.setSourcePlanVersion("1");
        contract.setLines(new ArrayList<>(List.of(line("21", "L1", "D1", "2"),
                line("22", "L2", "D1", "3"), line("23", "L3", "D2", "20"))));
        var policies = mock(RemoteMdmFactoryPlanningPolicyService.class);
        var contracts = mock(RemotePlanFormalContractService.class);
        when(policies.active("F1")).thenReturn(R.ok(policy));
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
        receive.setProductionOrder("ORDER-1");
        receive.setMatCode("P1");
        receive.setNoticeNum(new BigDecimal("5"));
        receive.setProductionOrderQuantity(new BigDecimal("5"));
    }

    private static PlnFormalPlanContractDTO.Line line(String id, String no, String demand, String qty) {
        var line = new PlnFormalPlanContractDTO.Line();
        line.setId(id);
        line.setLineNo(no);
        line.setSourceDemandNo(demand);
        line.setSupplyType("MAKE");
        line.setProductCode("P1");
        line.setUnitCode("EA");
        line.setPlannedQuantity(new BigDecimal(qty));
        line.setContentFingerprint("c".repeat(64));
        return line;
    }

    @AfterEach
    void cleanup() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void sameProductMakePathsTwoAndThreeCoverFive() {
        assertDoesNotThrow(() -> verifier.verify(receive));
    }

    @Test
    void splitBatchCanUseWholeOrderCoverage() {
        receive.setNoticeNum(BigDecimal.ONE);
        assertDoesNotThrow(() -> verifier.verify(receive));
    }

    @Test
    void anotherDemandCannotLendItsTwentyUnits() {
        receive.setProductionOrderQuantity(new BigDecimal("6"));
        receive.setNoticeNum(new BigDecimal("6"));
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void representativeLineMustExist() {
        receive.setNoticeNum(BigDecimal.ONE);
        receive.setProductionOrderQuantity(BigDecimal.ONE);
        receive.setFormalPlanContractId(999L);
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit", "bom", "route", "duplicateId", "duplicateLine", "fingerprint", "zero", "negative", "nullQuantity"})
    void incompatibleOrDamagedMakeLinesCannotBePooled(String damage) {
        receive.setNoticeNum(BigDecimal.ONE);
        receive.setProductionOrderQuantity(BigDecimal.ONE);
        var other = contract.getLines().get(1);
        switch (damage) {
            case "unit" -> other.setUnitCode("KG");
            case "bom" -> other.setBomVersion("B2");
            case "route" -> other.setRouteVersion("R2");
            case "duplicateId" -> other.setId("21");
            case "duplicateLine" -> other.setLineNo("L1");
            case "fingerprint" -> other.setContentFingerprint(null);
            case "zero" -> other.setPlannedQuantity(BigDecimal.ZERO);
            case "negative" -> other.setPlannedQuantity(BigDecimal.ONE.negate());
            case "nullQuantity" -> other.setPlannedQuantity(null);
            default -> fail(damage);
        }
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void existingWirePayloadStillWorksForSingleLineContract() {
        contract.setLines(List.of(contract.getLines().get(0)));
        receive.setNoticeNum(new BigDecimal("2"));
        receive.setProductionOrderQuantity(new BigDecimal("2"));
        assertDoesNotThrow(() -> verifier.verify(receive));
        receive.setProductionOrderQuantity(new BigDecimal("3"));
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }

    @Test
    void localModeRejectsErpOrderOnAnyCoveredLine() {
        receive.setNoticeNum(BigDecimal.ONE);
        receive.setProductionOrderQuantity(BigDecimal.ONE);
        contract.getLines().get(1).setErpProductionOrderNo("ERP-HIDDEN");
        assertThrows(CheckedException.class, () -> verifier.verify(receive));
    }
}
