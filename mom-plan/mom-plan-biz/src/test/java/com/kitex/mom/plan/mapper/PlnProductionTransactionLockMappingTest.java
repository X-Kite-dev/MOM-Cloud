package com.kitex.mom.plan.mapper;

import com.kitex.mom.plan.mapper.fixture.PgLockResultSetFixture;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 实际生产Mapper、MyBatis参数/结果处理与PG驱动解码；不证明服务器咨询锁或并发。 */
class PlnProductionTransactionLockMappingTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("productionLockMethods")
    void productionTransactionLockReturnsAnIntegerWithoutDecodingVoid(
            String name, Class<?> mapper, String method, String parameterName) throws Exception {
        var configuration = new Configuration();
        configuration.addMapper(mapper);
        var mapping = configuration.getMappedStatement(mapper.getName() + '.' + method);
        String key = "tenant-1:F1:request'参数";
        var parameters = Map.of(parameterName, key);
        var boundSql = mapping.getBoundSql(parameters);
        var parameterHandler = configuration.newParameterHandler(mapping, parameters, boundSql);
        var prepared = mock(PreparedStatement.class);
        assertDoesNotThrow(() -> parameterHandler.setParameters(prepared));
        verify(prepared).setString(1, key);
        assertEquals(1, boundSql.getParameterMappings().size());

        try (var statement = PgLockResultSetFixture.statement(boundSql.getSql())) {
            var handler = new DefaultResultSetHandler(mock(Executor.class), mapping, parameterHandler,
                    null, boundSql, RowBounds.DEFAULT);

            assertEquals(List.of(1), assertDoesNotThrow(() -> handler.handleResultSets(statement), name), name);
        }
    }

    /** 自动覆盖生产主线Mapper，OMS另有业务域，不在本次修复范围。 */
    static Stream<Arguments> productionLockMethods() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:com/kitex/mom/plan/mapper/*.class");
        Arrays.sort(resources, Comparator.comparing(resource -> resource.getFilename()));
        var methods = new ArrayList<Arguments>();
        for (var resource : resources) {
            String file = resource.getFilename();
            if (file == null || !(file.startsWith("Plan") || file.startsWith("Pln")) || file.contains("$")) continue;
            Class<?> mapper = Class.forName("com.kitex.mom.plan.mapper." + file.substring(0, file.length() - 6));
            for (var method : mapper.getDeclaredMethods()) {
                var select = method.getAnnotation(Select.class);
                if (select == null || Arrays.stream(select.value()).noneMatch(sql -> sql.contains("pg_advisory_xact_lock"))) continue;
                String parameter = method.getParameters()[0].getAnnotation(Param.class).value();
                methods.add(Arguments.of(mapper.getSimpleName() + '.' + method.getName(), mapper, method.getName(), parameter));
            }
        }
        return methods.stream();
    }
}
