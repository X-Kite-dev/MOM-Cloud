package com.kitex.mom.mdm.service;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.feign.RemoteMesFactoryPlanningImpactService;
import com.kitex.mom.mdm.feign.RemotePlanFactoryPlanningImpactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 远程端口替身；执行真实影响编排，防止不可读/不完整证据被视为无影响。
 */
class FactoryPlanningImpactEvidenceTest {

    private final RemotePlanFactoryPlanningImpactService plan = mock(RemotePlanFactoryPlanningImpactService.class);

    private final RemoteMesFactoryPlanningImpactService mes = mock(RemoteMesFactoryPlanningImpactService.class);

    private final FactoryPlanningPolicyImpactInspector inspector = new FactoryPlanningPolicyImpactInspector(plan, mes);

    private MdmFactoryPlanningImpactDTO.ModuleImpact clean(String module) {
        return new MdmFactoryPlanningImpactDTO.ModuleImpact().setModule(module).setTenantId(1L).setFactoryCode("F1").setAvailable(true).setActiveMrpCount(0L).setLockedApsCount(0L).setUnfinishedTaskCount(0L).setPendingErpEventCount(0L).setFormalSourceCount(0L).setIntelligentApsInstalled(false);
    }

    private MdmFactoryPlanningImpactDTO inspect(MdmFactoryPlanningImpactDTO.ModuleImpact evidence) {
        when(plan.current("F1")).thenReturn(R.ok(evidence));
        when(mes.current("F1")).thenReturn(R.ok(clean("MES")));
        var draft = new MdmFactoryPlanningPolicyEntity();
        draft.setId(10L);
        draft.setTenantId(1L);
        draft.setFactoryCode("F1");
        draft.setVersionNo(1);
        draft.setContentFingerprint("a".repeat(64));
        return inspector.inspect(draft);
    }

    @Test
    void unavailableResponseCannotBePromotedToAvailable() {
        var evidence = clean("PLAN").setAvailable(false);
        var result = inspect(evidence);
        assertFalse(result.getImmediateSwitchAllowed());
        assertFalse(result.getPlan().getAvailable());
        assertFalse(evidence.getAvailable());
    }

    @Test
    void wrongModuleCannotProvidePlanningEvidence() {
        assertFalse(inspect(clean("MES")).getImmediateSwitchAllowed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MRP", "APS", "TASK", "ERP"})
    void missingCountIsNotZero(String field) {
        var evidence = clean("PLAN");
        switch (field) {
            case "MRP" -> evidence.setActiveMrpCount(null);
            case "APS" -> evidence.setLockedApsCount(null);
            case "TASK" -> evidence.setUnfinishedTaskCount(null);
            default -> evidence.setPendingErpEventCount(null);
        }
        assertFalse(inspect(evidence).getImmediateSwitchAllowed());
    }

    @Test
    void negativeCountIsNotAnEmptyFactory() {
        assertFalse(inspect(clean("PLAN").setActiveMrpCount(-1L)).getImmediateSwitchAllowed());
    }

    @Test
    void actualPendingErpEventsFromMesBlockSwitch() {
        when(mes.current("F1")).thenReturn(R.ok(clean("MES").setPendingErpEventCount(1L)));
        // inspect() supplies its normal MES response; override at the port after priming it.
        inspect(clean("PLAN"));
        when(mes.current("F1")).thenReturn(R.ok(clean("MES").setPendingErpEventCount(1L)));
        var draft = new MdmFactoryPlanningPolicyEntity();
        draft.setId(10L);
        draft.setTenantId(1L);
        draft.setFactoryCode("F1");
        draft.setVersionNo(1);
        draft.setContentFingerprint("a".repeat(64));
        assertFalse(inspector.inspect(draft).getImmediateSwitchAllowed());
    }

    @Test
    void zeroEvidenceAllowsRegularSwitch() {
        assertTrue(inspect(clean("PLAN")).getImmediateSwitchAllowed());
    }

    @Test
    void sameFactoryInAnotherTenantIsRejected() {
        assertFalse(inspect(clean("PLAN").setTenantId(2L)).getImmediateSwitchAllowed());
    }

    @Test
    void missingFormalSourceCountBlocksEvidence() {
        assertFalse(inspect(clean("PLAN").setFormalSourceCount(null)).getImmediateSwitchAllowed());
    }

    @Test
    void transportFailureRemainsBlocking() {
        inspect(clean("PLAN"));
        when(plan.current("F1")).thenThrow(new IllegalStateException("offline"));
        var draft = new MdmFactoryPlanningPolicyEntity();
        draft.setId(10L);
        draft.setTenantId(1L);
        draft.setFactoryCode("F1");
        draft.setVersionNo(1);
        draft.setContentFingerprint("a".repeat(64));
        assertFalse(inspector.inspect(draft).getImmediateSwitchAllowed());
    }
}
