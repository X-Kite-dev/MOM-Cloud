package com.kitex.mom.wms.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WmsInventoryHealthWarehouseSqlTest {

    @Test
    void allInventoryHealthAggregatesRestrictTheSelectedWarehouse() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mapper/WmsAgentInventoryHealthMapper.xml";
        try (var source = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(source);
            new XMLMapperBuilder(source, configuration, resource, configuration.getSqlFragments()).parse();
        }

        var parameters = new HashMap<String, Object>();
        parameters.put("tenantId", 1L);
        parameters.put("factoryCode", "F1");
        parameters.put("today", LocalDate.of(2026, 10, 24));
        parameters.put("nearExpiryEnd", LocalDate.of(2026, 11, 23));
        parameters.put("slowBefore", LocalDateTime.of(2026, 7, 26, 0, 0));
        parameters.put("limit", 11);
        parameters.put("warehouseCode", "W1");

        for (String method : List.of("selectSummary", "selectUnitSummaries", "selectExceptions")) {
            var statement = configuration.getMappedStatement(
                    "com.kitex.mom.wms.agent.mapper.WmsAgentInventoryHealthMapper." + method);
            assertTrue(statement.getBoundSql(parameters).getSql().contains("s.warehouse_code = ?"), method);
        }

        parameters.put("warehouseCode", null);
        var factoryStatement = configuration.getMappedStatement(
                "com.kitex.mom.wms.agent.mapper.WmsAgentInventoryHealthMapper.selectSummary");
        assertFalse(factoryStatement.getBoundSql(parameters).getSql().contains("s.warehouse_code = ?"));
    }
}
