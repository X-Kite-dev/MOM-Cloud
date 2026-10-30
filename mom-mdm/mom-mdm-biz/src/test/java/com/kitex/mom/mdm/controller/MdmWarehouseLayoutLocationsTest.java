package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.service.MdmWarehouseLocationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MdmWarehouseLayoutLocationsTest {
    private final MdmWarehouseLocationService locations = mock(MdmWarehouseLocationService.class);
    private final MdmWarehouseLocationController controller = new MdmWarehouseLocationController(locations);

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void readsOnlyExactWarehouseInTenantAndFactory() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""),
                MdmWarehouseLocationEntity.class);
        TenantContextHolder.setTenantId(11L);
        FactoryContextHolder.setFactoryCode("F1");
        var site = new MdmWarehouseLocationEntity();
        site.setTenantId(11L);
        site.setFactoryCode("F1");
        site.setWarehouseCode("W1");
        site.setLocationCode("A-01");
        site.setLocationLevel(3);
        when(locations.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(site));

        assertEquals("A-01", controller.layoutLocations("W1").getData().get(0).getLocationCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<MdmWarehouseLocationEntity>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(locations).list(query.capture());
        query.getValue().getSqlSegment();
        assertEquals(Set.of(11L, "F1", "W1"),
                new HashSet<>(query.getValue().getParamNameValuePairs().values()));
    }

    @Test
    void rejectsMissingOrSkippedScopeAndForeignRows() {
        assertThrows(CheckedException.class, () -> controller.layoutLocations("W1"));
        TenantContextHolder.setTenantId(11L);
        FactoryContextHolder.setFactoryCode("F1");
        assertThrows(CheckedException.class, () -> controller.layoutLocations("*"));
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> controller.layoutLocations("W1"));
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(11L);
        var foreign = new MdmWarehouseLocationEntity();
        foreign.setTenantId(12L);
        foreign.setFactoryCode("F1");
        foreign.setWarehouseCode("W1");
        when(locations.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(foreign));
        assertThrows(CheckedException.class, () -> controller.layoutLocations("W1"));
    }
}
