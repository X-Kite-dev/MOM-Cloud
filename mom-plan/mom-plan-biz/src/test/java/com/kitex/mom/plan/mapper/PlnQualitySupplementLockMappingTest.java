package com.kitex.mom.plan.mapper;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 实际结果处理器复现void锁映射故障；不替代目标PG锁竞争验证。 */
class PlnQualitySupplementLockMappingTest {

    @ParameterizedTest
    @ValueSource(classes = {PlnQualitySupplementDemandMapper.class, PlnQualityReplanCaseMapper.class})
    void supplementApprovalLockReturnsAMappableAcknowledgement(Class<?> mapper) throws Exception {
        var configuration = new Configuration();
        configuration.addMapper(mapper);
        var mapping = configuration.getMappedStatement(mapper.getName() + ".lockKey");
        var boundSql = mapping.getBoundSql(Map.of("key", "REVIEW:quality-1"));
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("locked");
        when(metadata.getColumnName(1)).thenReturn("locked");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnClassName(1)).thenReturn(Integer.class.getName());
        var result = mock(ResultSet.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(result.next()).thenReturn(true, false);
        when(result.getInt(anyString())).thenReturn(1);
        var statement = mock(Statement.class);
        when(statement.getResultSet()).thenReturn(result);
        when(statement.getUpdateCount()).thenReturn(-1);

        var handler = new DefaultResultSetHandler(mock(Executor.class), mapping, mock(ParameterHandler.class),
                null, boundSql, RowBounds.DEFAULT);

        assertEquals(List.of(1), assertDoesNotThrow(() -> handler.handleResultSets(statement)));
    }
}
