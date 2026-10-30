package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.kitex.common.data.handler.JsonStringArrayTypeHandler;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.mapper.MdmProcessRouteWorkingMapper;
import org.apache.ibatis.type.ArrayTypeHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V2节点关系使用PG原生数组；历史主工序关系仍使用原有逗号字符串协议。
 */
class MdmRouteNodeArrayMappingTest {

    @ParameterizedTest
    @ValueSource(strings = {"insert", "updateById"})
    void writesNodeArraysWithoutChangingLegacyRelationStorage(String command) throws Exception {
        var configuration = configuration();
        var entity = new MdmProcessRouteWorkingEntity();
        entity.setId(1L);
        entity.setPreRouteNodeCodes(new String[0]);
        entity.setNextRouteNodeCodes(new String[]{"RN-2", "RN-3"});
        entity.setPreRouteCode(new String[0]);
        entity.setNextRouteCode(new String[]{"P2", "P3"});
        Object parameters = "insert".equals(command) ? entity : Map.of("et", entity);
        var statement = configuration.getMappedStatement(MdmProcessRouteWorkingMapper.class.getName() + "." + command);
        var bound = statement.getBoundSql(parameters);
        var prepared = mock(PreparedStatement.class);
        var connection = mock(Connection.class);
        var sqlArray = mock(java.sql.Array.class);
        when(prepared.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("VARCHAR"), any(Object[].class))).thenReturn(sqlArray);

        new MybatisParameterHandler(statement, parameters, bound).setParameters(prepared);

        var mappings = bound.getParameterMappings();
        for (int i = 0; i < mappings.size(); i++) {
            String property = mappings.get(i).getProperty().replace("et.", "");
            if (property.equals("preRouteNodeCodes") || property.equals("nextRouteNodeCodes")) {
                verify(prepared).setArray(i + 1, sqlArray);
                verify(prepared, never()).setString(eq(i + 1), anyString());
            }
            if (property.equals("nextRouteCode")) {
                verify(prepared).setString(i + 1, "P2,P3");
            }
        }
        verify(connection).createArrayOf(eq("VARCHAR"), aryEq(new String[0]));
        verify(connection).createArrayOf(eq("VARCHAR"), aryEq(new String[]{"RN-2", "RN-3"}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"preRouteNodeCodes", "nextRouteNodeCodes"})
    void readsPgNodeArraysUsingTheSelectedEntityResultMap(String property) throws Exception {
        var configuration = configuration();
        var statement = configuration.getMappedStatement(MdmProcessRouteWorkingMapper.class.getName() + ".selectById");
        var mapping = statement.getResultMaps().get(0).getResultMappings().stream()
                .filter(item -> property.equals(item.getProperty())).findFirst();
        assertTrue(mapping.isPresent(), "V2数组字段须进入实体结果映射，不能退回全局字符串处理器");
        var field = mapping.orElseThrow();
        assertInstanceOf(ArrayTypeHandler.class, field.getTypeHandler());
        var result = mock(ResultSet.class);
        var sqlArray = mock(java.sql.Array.class);
        when(result.getArray(field.getColumn())).thenReturn(sqlArray);
        when(sqlArray.getArray()).thenReturn(new String[]{"RN-2", "RN-3"});

        var actual = field.getTypeHandler().getResult(result, field.getColumn());

        assertArrayEquals(new String[]{"RN-2", "RN-3"}, (String[]) actual);
        verify(sqlArray).free();
        verify(result, never()).getString(anyString());
    }

    private MybatisConfiguration configuration() {
        var configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setIdentifierGenerator(ignored -> 1L));
        configuration.getTypeHandlerRegistry().register(JsonStringArrayTypeHandler.class);
        configuration.addMapper(MdmProcessRouteWorkingMapper.class);
        return configuration;
    }
}
