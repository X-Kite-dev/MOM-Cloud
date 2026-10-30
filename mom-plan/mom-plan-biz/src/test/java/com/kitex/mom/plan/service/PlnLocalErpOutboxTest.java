package com.kitex.mom.plan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.PlnErpIntegrationOutboxServiceImpl;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlnLocalErpOutboxTest {

    @Test
    void directErpEnvelopeCannotCreatePostingForLocalOnlyPolicy() {
        var policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10");
        policy.setVersionNo(1);
        policy.setContentFingerprint("a".repeat(64));
        policy.setFactoryCode("F1");
        policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        var authority = mock(PlnFactoryPlanningAuthorityService.class);
        when(authority.requireCurrentPolicy("F1")).thenReturn(policy);
        var contracts = mock(PlnFormalPlanContractMapper.class);
        var operations = mock(PlnErpIntegrationOperationMapper.class);
        var service = new PlnErpIntegrationOutboxServiceImpl(authority, contracts, operations, new ObjectMapper());
        var e = new PlnErpPostingEnvelope().setContractVersion("MOM_ERP_POSTING_V1").setRequestId("POST-1").setEventType("COMPLETION_POSTING").setSourceModule("MES").setSourceDocumentNo("GOOD-1").setFactoryCode("F1").setPlanningPolicyId("10").setPlanningPolicyVersion(1).setPlanningPolicyFingerprint("a".repeat(64)).setFormalPlanContractNo("C1").setFormalPlanContractVersion(1).setFormalPlanContractFingerprint("b".repeat(64)).setMrpAuthority("MOM_MRP").setProductionOrderSyncMode("MOM_ORDER_ONLY").setPayloadJson("{}").setOccurredTime(LocalDateTime.of(2026, 10, 24, 22, 0));
        var error = assertThrows(CheckedException.class, () -> service.enqueue(e));
        assertTrue(error.getMessage().contains("MOM独立执行"));
        verifyZeroInteractions(contracts, operations);
    }
}
