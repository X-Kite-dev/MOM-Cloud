package com.kitex.mom.mes.mapper;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实MyBatis结果处理器验证锁查询，避免服务mock漏掉PostgreSQL void结果映射错误。 */
class MesExecutionRequestLockMappingTest {

	@ParameterizedTest
	@ValueSource(classes = { MesStartPreparationCheckMapper.class, MesWorkpieceTimeMapper.class })
	void requestLockMapsAnIntegerAcknowledgement(Class<?> mapper) throws Exception {
        assertIntegerLock(mapper, "lockRequest");
    }

    @Test
    void withdrawalLockMapsAnIntegerAcknowledgement() throws Exception {
        assertIntegerLock(MesPlanWithdrawalReceiptMapper.class, "lockKey");
    }

    private void assertIntegerLock(Class<?> mapper, String method) throws Exception {
		Configuration configuration = new Configuration();
		configuration.addMapper(mapper);
		MappedStatement mapping = configuration.getMappedStatement(mapper.getName() + "." + method);
		var boundSql = mapping.getBoundSql(Map.of("key", "same-original-request"));
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
		assertTrue(boundSql.getSql().matches("(?is)SELECT\\s+1(?:\\s+AS\\s+locked)?\\s+FROM\\s+pg_advisory_xact_lock\\(.*"),
				"锁函数的void列必须转换为可映射的整数回执，仍在原事务持锁");
	}
}
