package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

class MesWipTimeoutConfigScopeTest {
    MesWipTimeoutConfigMapper mapper;
    MesWipTimeoutHandleMapper handles;
    MesActualFactoryAccessService access;
    MesWipTimeoutConfigServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("tester");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
        var cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var t : List.of(MesWipTimeoutConfigEntity.class, MesWipTimeoutHandleEntity.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(cfg, "scope"), t);
        mapper = mock(MesWipTimeoutConfigMapper.class);
        handles = mock(MesWipTimeoutHandleMapper.class);
        access = mock(MesActualFactoryAccessService.class);
        when(access.require(anyString())).thenReturn("F1");
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(MesWipTimeoutConfigEntity.class))).thenReturn(1);
        when(mapper.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        var c = MesWipTimeoutConfigServiceImpl.class.getConstructors()[0];
        service =
                (MesWipTimeoutConfigServiceImpl)
                        (c.getParameterCount() == 1
                                ? c.newInstance(handles)
                                : c.newInstance(handles, access));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @AfterEach
    void clean() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    MesWipTimeoutConfigEntity draft() {
        var c = new MesWipTimeoutConfigEntity();
        c.setName("rule");
        c.setTimeoutSec(60);
        c.setLevel1NotifyRoles("worker");
        c.setVerifyRoles("quality");
        c.setEnabled(true);
        return c;
    }

    MesWipTimeoutConfigEntity persisted(String factory) {
        var c = draft();
        c.setId(42L);
        c.setTenantId(1L);
        c.setFactoryCode(factory);
        c.setCode("WIP-42");
        when(mapper.selectById(42L)).thenReturn(c);
        return c;
    }

    @Test
    void birthUsesActualFactoryAndTenant() {
        var c = draft();
        service.saveData(c);
        assertEquals("F1", c.getFactoryCode());
        assertEquals(1L, c.getTenantId());
        verify(access).require("write");
    }

    @Test
    void spoofedFactoryCannotBeSaved() {
        var c = draft();
        c.setFactoryCode("F2");
        assertThrows(RuntimeException.class, () -> service.saveData(c));
        verify(mapper, never()).insert(any(MesWipTimeoutConfigEntity.class));
    }

    @Test
    void spoofedTenantCannotBeSaved() {
        var c = draft();
        c.setTenantId(2L);
        assertThrows(RuntimeException.class, () -> service.saveData(c));
    }

    @Test
    void foreignPersistedRuleCannotBeUpdated() {
        persisted("F2");
        var c = draft();
        c.setId(42L);
        assertThrows(RuntimeException.class, () -> service.updateData(c));
        verify(mapper, never())
                .update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void zeroRowInsertCannotBeReportedAsSaved() {
        when(mapper.insert(any(MesWipTimeoutConfigEntity.class))).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.saveData(draft()));
    }

    @Test
    void zeroRowUpdateCannotBeReportedAsSaved() {
        persisted("F1");
        when(mapper.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(0);
        var c = draft();
        c.setId(42L);
        assertThrows(RuntimeException.class, () -> service.updateData(c));
    }

    @Test
    void directFactoryWriteDenialPreventsSave() {
        when(access.require("write")).thenThrow(new IllegalStateException("denied"));
        assertThrows(RuntimeException.class, () -> service.saveData(draft()));
        verify(mapper, never()).insert(any(MesWipTimeoutConfigEntity.class));
    }

    @Test
    void foreignRuleCannotBeDeleted() {
        persisted("F2");
        assertThrows(RuntimeException.class, () -> service.removeUnused(List.of(42L)));
    }
}
