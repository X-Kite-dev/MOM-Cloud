package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WmsMaterialMoveSiteQueryScopeTest {
    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "move-site"),
                WmsMaterialMoveSiteEntity.class);
    }

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void selectedExportIdsIntersectOrganizationWarehouseAndSearchFilters() {
        var query = new WmsMaterialMoveSiteQueryDTO();
        query.setWarehouseCode("W1");
        query.setMatCode("M1");
        var wrapper = WmsMaterialMoveSiteQueryScope.build(query, new Long[] { 7L, 8L });
        String sql = wrapper.getSqlSegment();

        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("factory_code"));
        assertTrue(sql.contains("warehouse_code"));
        assertTrue(sql.contains(" IN "));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("F1"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("W1"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%M1%"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(8L));
    }

    @Test
    void blankWarehouseStillKeepsTenantAndFactoryForGlobalView() {
        var wrapper = WmsMaterialMoveSiteQueryScope.build(new WmsMaterialMoveSiteQueryDTO(), null);
        String sql = wrapper.getSqlSegment();

        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("factory_code"));
        assertFalse(sql.contains("warehouse_code"));
    }

    @Test
    void invalidIdOrMissingContextIsRejectedBeforeQuery() {
        assertThrows(CheckedException.class, () -> WmsMaterialMoveSiteQueryScope.build(
                new WmsMaterialMoveSiteQueryDTO(), new Long[] { 7L, null }));
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> WmsMaterialMoveSiteQueryScope.build(
                new WmsMaterialMoveSiteQueryDTO(), null));
    }
}
