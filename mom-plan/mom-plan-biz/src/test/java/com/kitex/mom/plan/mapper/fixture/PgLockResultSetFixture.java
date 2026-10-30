package com.kitex.mom.plan.mapper.fixture;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Encoding;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.Tuple;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.jdbc.PgStatement;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用实际PG JDBC缓冲行解码；仅连接和类型目录是替身，不连接数据库。
 * void函数的文本结果为空字节串而非SQL NULL；整数投影是INT4文本1。
 */
public final class PgLockResultSetFixture {

    private PgLockResultSetFixture() {
    }

    public static BaseStatement statement(String sql) throws Exception {
        boolean integerProjection = sql.matches(
                "(?is)\\s*SELECT\\s+1(?:\\s+AS\\s+locked)?\\s+FROM\\s+pg_advisory_xact_lock\\(.*");
        int oid = integerProjection ? Oid.INT4 : Oid.VOID;
        String label = integerProjection ? "locked" : "pg_advisory_xact_lock";
        var connection = mock(BaseConnection.class);
        var typeInfo = mock(TypeInfo.class);
        when(connection.getEncoding()).thenReturn(Encoding.getJVMEncoding("UTF-8"));
        when(connection.getTypeInfo()).thenReturn(typeInfo);
        when(connection.getLogger()).thenReturn(Logger.getLogger(PgLockResultSetFixture.class.getName()));
        when(typeInfo.getSQLType(Oid.INT4)).thenReturn(Types.INTEGER);
        when(typeInfo.getJavaClass(Oid.INT4)).thenReturn(Integer.class.getName());
        when(typeInfo.getPGType(Oid.INT4)).thenReturn("int4");
        when(typeInfo.getSQLType(Oid.VOID)).thenReturn(Types.OTHER);
        when(typeInfo.getJavaClass(Oid.VOID)).thenReturn(Object.class.getName());
        when(typeInfo.getPGType(Oid.VOID)).thenReturn("void");
        var statement = mock(PgStatement.class);
        when(statement.getConnection()).thenReturn(connection);
        var constructor = PgResultSet.class.getDeclaredConstructor(Query.class, BaseStatement.class,
                Field[].class, List.class, ResultCursor.class, int.class, int.class, int.class,
                int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        var row = new Tuple(new byte[][]{(integerProjection ? "1" : "").getBytes(StandardCharsets.UTF_8)});
        var result = constructor.newInstance(null, statement, new Field[]{new Field(label, oid)}, List.of(row),
                null, 0, 0, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                ResultSet.CLOSE_CURSORS_AT_COMMIT, false);
        when(statement.getResultSet()).thenReturn(result);
        when(statement.getUpdateCount()).thenReturn(-1);
        return statement;
    }
}
