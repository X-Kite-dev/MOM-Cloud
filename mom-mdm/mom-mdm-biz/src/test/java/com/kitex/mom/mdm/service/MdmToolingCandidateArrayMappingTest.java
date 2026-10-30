package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.kitex.common.data.handler.JsonStringArrayTypeHandler;
import com.kitex.mom.mdm.entity.MdmProcedureToolingRequirementEntity;
import com.kitex.mom.mdm.mapper.MdmProcedureToolingRequirementMapper;
import org.apache.ibatis.type.ArrayTypeHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候选工装列使用PG原生数组，不能退回全局String[]逗号字符串协议。
 */
class MdmToolingCandidateArrayMappingTest {

    static Stream<Arguments> writes() {
        return Stream.of("insert", "updateById").flatMap(command -> Stream.of(
                Arguments.of(command, new String[0]),
                Arguments.of(command, new String[]{"TEST-FIXTURE-1", "TEST-FIXTURE-2"})));
    }

    @ParameterizedTest
    @MethodSource("writes")
    void writesEmptyAndRestrictedCandidatesAsPgArrays(String command, String[] candidates) throws Exception {
        var configuration = configuration();
        var entity = new MdmProcedureToolingRequirementEntity();
        entity.setId(1L);
        entity.setCandidateToolingCodes(candidates);
        Object parameters = "insert".equals(command) ? entity : Map.of("et", entity);
        var statement = configuration.getMappedStatement(MdmProcedureToolingRequirementMapper.class.getName() + "." + command);
        var bound = statement.getBoundSql(parameters);
        var prepared = mock(PreparedStatement.class);
        var connection = mock(Connection.class);
        var sqlArray = mock(java.sql.Array.class);
        when(prepared.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("VARCHAR"), any(Object[].class))).thenReturn(sqlArray);

        new MybatisParameterHandler(statement, parameters, bound).setParameters(prepared);

        var mappings = bound.getParameterMappings();
        int index = -1;
        for (int i = 0; i < mappings.size(); i++) {
            if ("candidateToolingCodes".equals(mappings.get(i).getProperty().replace("et.", ""))) index = i + 1;
        }
        assertTrue(index > 0, "空集合也必须持久化为PG空数组，不能静默省略候选范围更新");
        verify(connection).createArrayOf(eq("VARCHAR"), aryEq(candidates));
        verify(prepared).setArray(index, sqlArray);
        verify(prepared, never()).setString(eq(index), anyString());
        verify(sqlArray).free();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "RESTRICTED"})
    void readsPgCandidateArraysFromTheEntityResultMap(String variant) throws Exception {
        String[] candidates = "EMPTY".equals(variant) ? new String[0] : new String[]{"TEST-FIXTURE-1", "TEST-FIXTURE-2"};
        var statement = configuration().getMappedStatement(MdmProcedureToolingRequirementMapper.class.getName() + ".selectById");
        var mapping = statement.getResultMaps().get(0).getResultMappings().stream()
                .filter(item -> "candidateToolingCodes".equals(item.getProperty())).findFirst();
        assertTrue(mapping.isPresent(), "候选工装必须进入实体结果映射，避免全局字符串处理器改变数组语义");
        var field = mapping.orElseThrow();
        assertInstanceOf(ArrayTypeHandler.class, field.getTypeHandler());
        var result = mock(ResultSet.class);
        var sqlArray = mock(java.sql.Array.class);
        when(result.getArray(field.getColumn())).thenReturn(sqlArray);
        when(sqlArray.getArray()).thenReturn(candidates);

        var actual = field.getTypeHandler().getResult(result, field.getColumn());

        assertArrayEquals(candidates, (String[]) actual);
        verify(sqlArray).free();
        verify(result, never()).getString(anyString());
    }

    private MybatisConfiguration configuration() {
        var configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setIdentifierGenerator(ignored -> 1L));
        configuration.getTypeHandlerRegistry().register(JsonStringArrayTypeHandler.class);
        configuration.addMapper(MdmProcedureToolingRequirementMapper.class);
        return configuration;
    }
}
