package com.kitex.mom.mdm.mapper;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 正式工程锁查询必须能通过实际MyBatis结果映射，避免业务服务mock吞掉PG void列错误。
 */
class MdmEngineeringRequestLockMappingTest {
    static Stream<Arguments> engineeringLocks() {
        return Stream.of(
                Arguments.of(MdmFactoryPlanningPolicyMapper.class, "lock", "lockKey", "hashtext(?)"),
                Arguments.of(MdmSopOperationMapper.class, "lockKey", "key", "hashtextextended(?, 0)"),
                Arguments.of(MdmBomEcnMapper.class, "lockRequest", "key", "hashtextextended(?, 0)"),
                Arguments.of(MdmRouteEngineeringCommandMapper.class, "lockRequest", "scopeKey", "hashtextextended(?, 0)"),
                Arguments.of(MdmPlanResourceEventOutboxMapper.class, "lockKey", "key",
                        "hashtextextended(CONCAT('MDM_PLAN_RESOURCE:', ?), 0)"));
    }

    @ParameterizedTest
    @MethodSource("engineeringLocks")
    void transactionLockReturnsMappableAcknowledgement(Class<?> mapper, String method, String parameter,
                                                       String lockExpression) throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(mapper);
        MappedStatement mapping = configuration.getMappedStatement(mapper.getName() + "." + method);
        var boundSql = mapping.getBoundSql(Map.of(parameter, "FORMAL_SOP_CLAIM:1:ZD:original-request"));
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("locked");
        when(metadata.getColumnName(1)).thenReturn("locked");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnClassName(1)).thenReturn(Integer.class.getName());
        ResultSet result = mock(ResultSet.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(result.next()).thenReturn(true, false);
        when(result.getInt(anyString())).thenReturn(1);
        Statement statement = mock(Statement.class);
        when(statement.getResultSet()).thenReturn(result);
        when(statement.getUpdateCount()).thenReturn(-1);

        var handler = new DefaultResultSetHandler(mock(Executor.class), mapping, mock(ParameterHandler.class), null,
                boundSql, RowBounds.DEFAULT);
        assertEquals(List.of(1), assertDoesNotThrow(() -> handler.handleResultSets(statement)));
        assertEquals("SELECT 1 AS locked FROM pg_advisory_xact_lock(" + lockExpression + ")", boundSql.getSql(),
                "锁函数必须保留事务级互斥，并只向MyBatis返回可映射回执");
        assertEquals(List.of(parameter), boundSql.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
}
