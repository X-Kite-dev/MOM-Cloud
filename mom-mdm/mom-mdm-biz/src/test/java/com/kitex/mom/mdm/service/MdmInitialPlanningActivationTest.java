package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyCommand;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.feign.RemoteMesFactoryPlanningImpactService;
import com.kitex.mom.mdm.feign.RemotePlanFactoryPlanningImpactService;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmFactoryPlanningPolicyMapper;
import com.kitex.mom.mdm.service.impl.MdmFactoryPlanningPolicyServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 真实策略编排与影响计算；仅持久化/跨服务端口替身，不代表原生PG事务联验。
 */
class MdmInitialPlanningActivationTest {

    private MdmFactoryPlanningPolicyServiceImpl service;

    private MdmFactoryPlanningPolicyMapper policies;

    private MdmFactoryPlanningPolicyEntity draft;

    private MdmFactoryPlanningImpactDTO.ModuleImpact plan;

    private MdmFactoryPlanningImpactDTO.ModuleImpact mes;

    private FactoryPlanningPolicyImpactInspector inspector;

    private MdmFactoryPlanningPolicyEntity replay;

    @BeforeEach
    void setup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        login("preparer");
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "initial-policy");
        TableInfoHelper.initTableInfo(assistant, MdmFactoryPlanningPolicyEntity.class);
        TableInfoHelper.initTableInfo(assistant, MdmFactoryModelEntity.class);
        policies = mock(MdmFactoryPlanningPolicyMapper.class);
        when(policies.selectList(any())).thenReturn(List.of());
        when(policies.insert(any(MdmFactoryPlanningPolicyEntity.class))).thenAnswer(a -> {
            draft = a.getArgument(0);
            return 1;
        });
        when(policies.updateById(any(MdmFactoryPlanningPolicyEntity.class))).thenReturn(1);
        var factories = mock(MdmFactoryModelMapper.class);
        var factory = new MdmFactoryModelEntity();
        factory.setId(10L);
        factory.setCode("F1");
        when(factories.selectOne(any())).thenReturn(factory);
        var planPort = mock(RemotePlanFactoryPlanningImpactService.class);
        var mesPort = mock(RemoteMesFactoryPlanningImpactService.class);
        plan = evidence("PLAN").setActiveMrpCount(25L);
        mes = evidence("MES").setUnfinishedTaskCount(12L);
        when(planPort.current("F1")).thenAnswer(a -> R.ok(plan));
        when(mesPort.current("F1")).thenAnswer(a -> R.ok(mes));
        inspector = new FactoryPlanningPolicyImpactInspector(planPort, mesPort);
        service = spy(new MdmFactoryPlanningPolicyServiceImpl(factories, inspector));
        ReflectionTestUtils.setField(service, "baseMapper", policies);
        var create = new MdmFactoryPlanningPolicyCommand.CreateDraft();
        create.setFactoryModelId("10");
        create.setMrpAuthority("MOM_MRP");
        create.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        create.setSubstituteGovernanceMode("MOM_APPROVAL");
        create.setEffectiveFrom(LocalDateTime.now().minusMinutes(5));
        create.setChangeReason("首次接入独立MOM，历史单保留");
        create.setRequestId("draft-1");
        service.createDraft(create);
        doAnswer(a -> {
            Wrapper<?> query = a.getArgument(0);
            String sql = query.getCustomSqlSegment();
            if (sql.contains("publish_request_id"))
                return replay;
            if (sql.contains("id ="))
                return draft;
            return null;
        }).when(service).getOne(any(), eq(false));
        login("publisher");
    }

    private void login(String username) {
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(2L);
        when(user.getUsername()).thenReturn(username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
    }

    private MdmFactoryPlanningImpactDTO.ModuleImpact evidence(String module) {
        return new MdmFactoryPlanningImpactDTO.ModuleImpact().setTenantId(1L).setModule(module).setFactoryCode("F1").setAvailable(true).setActiveMrpCount(0L).setLockedApsCount(0L).setUnfinishedTaskCount(0L).setPendingErpEventCount(0L).setFormalSourceCount(0L).setIntelligentApsInstalled(false);
    }

    private MdmFactoryPlanningPolicyCommand.Publish command(boolean initial) {
        var impact = service.inspect(draft.getId().toString());
        var command = new MdmFactoryPlanningPolicyCommand.Publish();
        command.setDraftId(draft.getId().toString());
        command.setExpectedFingerprint(draft.getContentFingerprint());
        command.setImpactConfirmationNo(impact.getConfirmationNo());
        command.setRequestId("publish-1");
        command.setInitialLocalActivation(initial);
        return command;
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void firstLocalActivationPreservesLegacyFactsAndFreezesEvidence() throws Exception {
        var impact = service.inspect(draft.getId().toString());
        assertFalse(impact.getImmediateSwitchAllowed());
        assertTrue(impact.getInitialLocalActivationAllowed());
        var result = service.publish(command(true));
        assertEquals("ACTIVE", result.getStatus());
        var snapshot = new ObjectMapper().readTree(result.getInitialActivationSnapshot());
        assertEquals("NEW_CONTRACTS_ONLY", snapshot.path("scope").asText());
        assertEquals(25, snapshot.path("impact").path("plan").path("activeMrpCount").asLong());
        assertEquals(12, snapshot.path("impact").path("mes").path("unfinishedTaskCount").asLong());
        assertEquals("publisher", snapshot.path("operator").asText());
        assertEquals(0, snapshot.path("impact").path("publishedPolicyCount").asLong());
        assertEquals(result.getInitialActivationSnapshot(), draft.getInitialActivationSnapshot());
    }

    @Test
    void noImplicitFirstActivationWithoutExplicitAcknowledgement() {
        assertThrows(CheckedException.class, () -> service.publish(command(false)));
        assertEquals("DRAFT", draft.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAN_FORMAL", "MES_FORMAL", "APS", "PLAN_ERP", "MES_ERP", "PRIOR", "UNKNOWN_PRIOR"})
    void incompatibleFactsCannotUseFirstActivation(String fact) {
        switch (fact) {
            case "PLAN_FORMAL" -> plan.setFormalSourceCount(1L);
            case "MES_FORMAL" -> mes.setFormalSourceCount(1L);
            case "APS" -> plan.setLockedApsCount(1L);
            case "PLAN_ERP" -> plan.setPendingErpEventCount(1L);
            case "MES_ERP" -> mes.setPendingErpEventCount(1L);
            case "PRIOR" -> when(policies.countEverPublished(1L, "F1")).thenReturn(1L);
            default -> when(policies.countEverPublished(1L, "F1")).thenReturn(-1L);
        }
        assertFalse(service.inspect(draft.getId().toString()).getInitialLocalActivationAllowed());
        assertThrows(CheckedException.class, () -> service.publish(command(true)));
        assertEquals("DRAFT", draft.getStatus());
    }

    @Test
    void evidenceChangeAfterInspectionRejectsPublish() {
        var command = command(true);
        plan.setActiveMrpCount(26L);
        assertThrows(CheckedException.class, () -> service.publish(command));
    }

    @Test
    void historyChangeAfterInspectionInvalidatesConfirmation() {
        var command = command(true);
        when(policies.countEverPublished(1L, "F1")).thenReturn(1L);
        assertThrows(CheckedException.class, () -> service.publish(command));
    }

    @Test
    void initialActivationCannotBeScheduledForTheFuture() {
        // Creation establishes a valid canonical fingerprint for a future version.
        var other = new MdmFactoryPlanningPolicyCommand.CreateDraft();
        other.setFactoryModelId("10");
        other.setMrpAuthority("MOM_MRP");
        other.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        other.setSubstituteGovernanceMode("MOM_APPROVAL");
        other.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        other.setChangeReason("未来策略");
        other.setRequestId("future");
        login("preparer");
        service.createDraft(other);
        login("publisher");
        assertThrows(CheckedException.class, () -> service.publish(command(true)));
    }

    @Test
    void laterDraftCanBeTheFirstPublishedPolicy() {
        when(policies.selectList(any())).thenReturn(List.of(draft));
        var next = new MdmFactoryPlanningPolicyCommand.CreateDraft();
        next.setFactoryModelId("10");
        next.setMrpAuthority("MOM_MRP");
        next.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        next.setSubstituteGovernanceMode("MOM_APPROVAL");
        next.setEffectiveFrom(LocalDateTime.now().minusMinutes(5));
        next.setChangeReason("调整首次草稿");
        next.setRequestId("draft-2");
        login("preparer");
        service.createDraft(next);
        login("publisher");
        assertEquals(2, draft.getVersionNo());
        assertEquals("ACTIVE", service.publish(command(true)).getStatus());
    }

    @Test
    void regularPublishStillWorksForAnEmptyFactoryWithoutInitialIntent() {
        plan.setActiveMrpCount(0L);
        mes.setUnfinishedTaskCount(0L);
        var result = service.publish(command(false));
        assertEquals("ACTIVE", result.getStatus());
        assertNull(result.getInitialActivationSnapshot());
    }

    @Test
    void integrationModeCannotUseLocalInitialActivation() {
        var integrated = new MdmFactoryPlanningPolicyCommand.CreateDraft();
        integrated.setFactoryModelId("10");
        integrated.setMrpAuthority("MOM_MRP");
        integrated.setProductionOrderSyncMode("MOM_ORDER_WITH_POSTING");
        integrated.setSubstituteGovernanceMode("MOM_APPROVAL");
        integrated.setErpSystemCode("ERP");
        integrated.setExternalFactoryCode("EF1");
        integrated.setEffectiveFrom(LocalDateTime.now().minusMinutes(5));
        integrated.setChangeReason("集成草稿");
        integrated.setRequestId("integrated");
        login("preparer");
        service.createDraft(integrated);
        login("publisher");
        assertFalse(service.inspect(draft.getId().toString()).getInitialLocalActivationAllowed());
        assertThrows(CheckedException.class, () -> service.publish(command(true)));
    }

    @Test
    void sameCompilerCannotPublishInitialActivation() {
        login("preparer");
        assertThrows(CheckedException.class, () -> service.publish(command(true)));
    }

    @Test
    void exactReplayIsStableAndCannotChangeInitialActivationIntent() {
        var command = command(true);
        assertDoesNotThrow(() -> service.publish(command));
        replay = draft;
        assertEquals("ACTIVE", service.publish(command).getStatus());
        command.setInitialLocalActivation(false);
        assertThrows(CheckedException.class, () -> service.publish(command));
    }
}
