package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.mapper.MesWipTimeoutConfigMapper;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/**
 * Real injected BaseMapper SQL with the production NOT_NULL strategy, not a successful mapper mock.
 */
class MesWipTimeoutNullablePersistenceTest {
    @Test
    void clearingLevelsAndScopeActuallyPersistsSqlNull() throws Exception {
        TenantContextHolder.setTenantId(1L);
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("tester");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:wip_nullable_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig().setUpdateStrategy(FieldStrategy.NOT_NULL));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(MesWipTimeoutConfigMapper.class);
        bean.setConfiguration(config);
        bean.setGlobalConfig(global);
        var factory = bean.getObject();
        var table = TableInfoHelper.getTableInfo(MesWipTimeoutConfigEntity.class);
        var db = new JdbcTemplate(ds);
        var columns = new ArrayList<String>();
        columns.add(table.getKeyColumn() + " BIGINT PRIMARY KEY");
        for (var f : table.getFieldList()) {
            var t = f.getPropertyType();
            String type =
                    t == Long.class || t == long.class
                            ? "BIGINT"
                            : t == Integer.class || t == int.class
                                    ? "INTEGER"
                                    : t == Boolean.class || t == boolean.class
                                            ? "BOOLEAN"
                                            : java.time.temporal.Temporal.class.isAssignableFrom(t)
                                                            || java.util.Date.class
                                                                    .isAssignableFrom(t)
                                                    ? "TIMESTAMP"
                                                    : "VARCHAR(4000)";
            columns.add(f.getColumn() + " " + type);
        }
        db.execute("CREATE TABLE " + table.getTableName() + "(" + String.join(",", columns) + ")");
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(MesWipTimeoutConfigMapper.class);
            var row = new MesWipTimeoutConfigEntity();
            row.setId(42L);
            row.setTenantId(1L);
            row.setFactoryCode("F1");
            row.setCode("WIP42");
            row.setName("before");
            row.setLineCode("L1");
            row.setRouteCode("R1");
            row.setProductCode("P1");
            row.setTimeoutSec(60);
            row.setLevel2Sec(120);
            row.setLevel3Sec(180);
            row.setLevel1NotifyRoles("worker");
            row.setLevel2NotifyRoles("manager");
            row.setLevel3NotifyRoles("director");
            row.setVerifyRoles("quality");
            row.setEnabled(true);
            assertEquals(1, mapper.insert(row));
            if (table.isWithLogicDelete())
                db.update(
                        "UPDATE "
                                + table.getTableName()
                                + " SET "
                                + table.getLogicDeleteFieldInfo().getColumn()
                                + "=? WHERE id=42",
                        table.getLogicDeleteFieldInfo().getLogicNotDeleteValue());
            var access = mock(MesActualFactoryAccessService.class);
            when(access.require("write")).thenReturn("F1");
            var service =
                    new MesWipTimeoutConfigServiceImpl(
                            mock(MesWipTimeoutHandleMapper.class), access);
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            var change = new MesWipTimeoutConfigEntity();
            change.setId(42L);
            change.setName("after");
            change.setTimeoutSec(90);
            change.setLevel1NotifyRoles("worker");
            change.setVerifyRoles("quality");
            change.setEnabled(true);
            service.updateData(change);
            var actual = mapper.selectById(42L);
            assertNotNull(actual);
            assertEquals("after", actual.getName());
            assertEquals(90, actual.getTimeoutSec());
            assertEquals("WIP42", actual.getCode());
            assertNull(actual.getLineCode());
            assertNull(actual.getRouteCode());
            assertNull(actual.getProductCode());
            assertNull(actual.getLevel2Sec());
            assertNull(actual.getLevel3Sec());
            assertNull(actual.getLevel2NotifyRoles());
            assertNull(actual.getLevel3NotifyRoles());
        } finally {
            TenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
