package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyCommand;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmFactoryPlanningPolicyMapper;
import com.kitex.mom.mdm.service.impl.MdmFactoryPlanningPolicyServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MdmLocalPlanningPolicyTest {

    private MdmFactoryPlanningPolicyServiceImpl service;

    private MdmFactoryPlanningPolicyMapper policies;

    @BeforeEach
    void setup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        MomUser user = mock(MomUser.class);
        when(user.getId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("preparer");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
        var config = new MybatisConfiguration();
        var assistant = new MapperBuilderAssistant(config, "local-policy");
        for (Class<?> entity : List.of(MdmFactoryModelEntity.class, MdmFactoryPlanningPolicyEntity.class))
            TableInfoHelper.initTableInfo(assistant, entity);
        var factories = mock(MdmFactoryModelMapper.class);
        var factory = new MdmFactoryModelEntity();
        factory.setId(10L);
        factory.setCode("F1");
        when(factories.selectOne(any())).thenReturn(factory);
        policies = mock(MdmFactoryPlanningPolicyMapper.class);
        when(policies.selectList(any())).thenReturn(List.of());
        when(policies.insert(any(MdmFactoryPlanningPolicyEntity.class))).thenReturn(1);
        service = new MdmFactoryPlanningPolicyServiceImpl(factories, mock(FactoryPlanningPolicyImpactInspector.class));
        ReflectionTestUtils.setField(service, "baseMapper", policies);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    private MdmFactoryPlanningPolicyCommand.CreateDraft command(String mode) {
        var c = new MdmFactoryPlanningPolicyCommand.CreateDraft();
        c.setFactoryModelId("10");
        c.setMrpAuthority("MOM_MRP");
        c.setProductionOrderSyncMode(mode);
        c.setSubstituteGovernanceMode("MOM_APPROVAL");
        c.setEffectiveFrom(LocalDateTime.of(2026, 10, 24, 22, 0));
        c.setChangeReason("local workflow acceptance");
        c.setRequestId("DRAFT-LOCAL-1");
        return c;
    }

    @Test
    void createsLocalOnlyDraftWithoutErpMappings() {
        var result = assertDoesNotThrow(() -> service.createDraft(command("MOM_ORDER_ONLY")));
        assertEquals("MOM_ORDER_ONLY", result.getProductionOrderSyncMode());
        assertNull(result.getErpSystemCode());
        assertNull(result.getExternalFactoryCode());
        assertEquals("DRAFT", result.getStatus());
        verify(policies).insert(any(MdmFactoryPlanningPolicyEntity.class));
    }

    @Test
    void rejectsErpAuthorityWithLocalOnlyMode() {
        var c = command("MOM_ORDER_ONLY");
        c.setMrpAuthority("ERP_MRP");
        assertThrows(CheckedException.class, () -> service.createDraft(c));
        verify(policies, never()).insert(any(MdmFactoryPlanningPolicyEntity.class));
    }

    @Test
    void rejectsErpMappingInLocalOnlyMode() {
        var c = command("MOM_ORDER_ONLY");
        c.setErpSystemCode("ERP");
        c.setExternalFactoryCode("F1");
        assertThrows(CheckedException.class, () -> service.createDraft(c));
        verify(policies, never()).insert(any(MdmFactoryPlanningPolicyEntity.class));
    }

    @Test
    void postingModeStillRequiresMappings() {
        assertThrows(CheckedException.class, () -> service.createDraft(command("MOM_ORDER_WITH_POSTING")));
        verify(policies, never()).insert(any(MdmFactoryPlanningPolicyEntity.class));
    }

    @Test
    void postingModeKeepsExistingMappings() {
        var c = command("MOM_ORDER_WITH_POSTING");
        c.setErpSystemCode("ERP");
        c.setExternalFactoryCode("EXT-F1");
        var result = service.createDraft(c);
        assertEquals("ERP", result.getErpSystemCode());
        assertEquals("EXT-F1", result.getExternalFactoryCode());
    }
}
