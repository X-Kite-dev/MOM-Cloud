package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实际Mapper聚合查询；可指定PostgreSQL验证方言，所有测试数据仅存在于连接级临时表。 */
class MesWarehouseReturnMapperTest {
    private static final String STOCK = "com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper.getPageFeedBack";
    private static final String FEED = "com.kitex.mom.mes.mapper.MesWarehouseFeedMapper.";
    private SingleConnectionDataSource source;
    private JdbcTemplate db;
    private SqlSession session;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("MOM_MAPPER_TEST_JDBC_URL");
        boolean postgres = url != null && url.startsWith("jdbc:postgresql:");
        source = postgres
                ? new SingleConnectionDataSource(url, System.getenv("MOM_MAPPER_TEST_JDBC_USER"),
                        System.getenv("MOM_MAPPER_TEST_JDBC_PASSWORD"), true)
                : new SingleConnectionDataSource("jdbc:h2:mem:return-mapper-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL", "sa", "", true);
        source.getConnection().setAutoCommit(false);
        db = new JdbcTemplate(source);
        db.execute("""
                CREATE TEMP TABLE mes_warehouse_live_stock (
                    id bigint, tenant_id bigint, factory_code varchar, del_flag varchar,
                    bill_no varchar, mat_code varchar, reservation_no varchar, requirement_mat_code varchar,
                    requirement_mat_name varchar, substitute_flag boolean, substitute_ratio numeric(24,8),
                    mat_name varchar, mat_spec varchar, mat_unit varchar, mat_batch varchar,
                    supplier_code varchar, supplier_name varchar, warehouse_code varchar, warehouse_name varchar,
                    site_code varchar, site_name varchar, num numeric(24,8), judge_status varchar, mat_detail_type varchar
                )
                """);
        db.execute("CREATE TEMP TABLE mes_warehouse_picking_bill "
                + "(bill_no varchar, task_code varchar, factory_code varchar, tenant_id bigint, del_flag varchar)");
        db.execute("INSERT INTO mes_warehouse_picking_bill VALUES ('B1','T1','F1',1,'0')");
        db.execute("CREATE TEMP TABLE mes_task "
                + "(code varchar, mat_code varchar, mat_name varchar, factory_code varchar, del_flag varchar)");
        db.execute("CREATE TEMP TABLE mes_produce_task "
                + "(code varchar, route_code varchar, route_name varchar, factory_code varchar, del_flag varchar)");
        db.execute("""
                CREATE TEMP TABLE mes_warehouse_feed (
                    id bigint, task_code varchar, arrange_code varchar, produce_task_code varchar,
                    mat_code varchar, reservation_no varchar, requirement_mat_code varchar,
                    substitute_flag boolean, substitute_ratio numeric(24,8), mat_name varchar, mat_spec varchar,
                    mat_batch varchar, warehouse_code varchar, warehouse_name varchar, site_code varchar,
                    site_name varchar, mat_unit varchar, status varchar, feed_num numeric(24,8),
                    used_num numeric(24,8), update_time timestamp, is_lock boolean, factory_code varchar, del_flag varchar
                )
                """);
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("return-mapper", new JdbcTransactionFactory(), source));
        for (String name : List.of("MesWarehouseLiveStockMapper.xml", "MesWarehouseFeedMapper.xml")) {
            try (var xml = getClass().getResourceAsStream("/mapper/" + name)) {
                assertNotNull(xml);
                new XMLMapperBuilder(xml, config, name, config.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession();
    }

    @AfterEach
    void close() throws Exception {
        if (source != null) {
            try {
                if (!source.getConnection().getAutoCommit()) {
                    source.getConnection().rollback();
                }
            } finally {
                try {
                    if (session != null) {
                        session.close();
                    }
                } finally {
                    source.destroy();
                }
            }
        }
    }

    @Test
    void stockBooleanFlagsKeepFalseAndUnknownDistinct() {
        stock(1, false, "2.00000000");
        assertFalse(stockRows().get(0).getSubstituteFlag());
        db.update("UPDATE mes_warehouse_live_stock SET substitute_flag=NULL");
        session.clearCache();
        assertNull(stockRows().get(0).getSubstituteFlag());
    }

    @Test
    void stockAggregationAcceptsMixedBooleanFlagsAndPreservesExactQuantity() {
        stock(1, false, "9007199254740993.12345678");
        stock(2, true, "0.00000001");
        stock(3, null, "0.00000001");
        var rows = stockRows();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getSubstituteFlag());
        assertEquals(0, new BigDecimal("9007199254740993.12345680").compareTo(rows.get(0).getNum()));
        assertEquals("T1", rows.get(0).getTaskCode());
    }

    @Test
    void differentReservationsAndLocationsRemainSeparateStockGroups() {
        stock(1, false, "2");
        stock(2, true, "3");
        stock(3, false, "5");
        db.update("UPDATE mes_warehouse_live_stock SET reservation_no='R2' WHERE id=2");
        db.update("UPDATE mes_warehouse_live_stock SET site_code='S2' WHERE id=3");
        assertEquals(3, stockRows().size());
    }

    @Test
    void bothFeedQueriesAggregateBooleanFlagsWithoutChangingRemainingQuantity() {
        feed(1, false, false);
        feed(2, true, true);
        feed(3, null, null);
        for (String statement : List.of("queryTaskFeedList", "queryLeftPage")) {
            List<MesWarehouseFeedLeftDTO> rows = session.selectList(FEED + statement, params());
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).getSubstituteFlag());
            assertTrue(rows.get(0).getIsLock());
            assertEquals(0, new BigDecimal("21").compareTo(rows.get(0).getLeftNum()));
        }
    }

    @Test
    void feedQueriesKeepFalseAndUnknownFlagsDistinct() {
        feed(1, false, false);
        for (String statement : List.of("queryTaskFeedList", "queryLeftPage")) {
            MesWarehouseFeedLeftDTO row = session.selectOne(FEED + statement, params());
            assertFalse(row.getSubstituteFlag());
            assertFalse(row.getIsLock());
        }
        db.update("UPDATE mes_warehouse_feed SET substitute_flag=NULL,is_lock=NULL");
        session.clearCache();
        for (String statement : List.of("queryTaskFeedList", "queryLeftPage")) {
            MesWarehouseFeedLeftDTO row = session.selectOne(FEED + statement, params());
            assertNull(row.getSubstituteFlag());
            assertNull(row.getIsLock());
        }
    }

    private Map<String, Object> params() {
        return Map.of("query", Map.of("factoryCode", "F1"));
    }

    private List<MesWarehouseFeedDTO> stockRows() {
        return session.selectList(STOCK, params());
    }

    private void stock(long id, Boolean substitute, String amount) {
        db.update("INSERT INTO mes_warehouse_live_stock "
                + "(id,tenant_id,factory_code,del_flag,bill_no,mat_code,reservation_no,requirement_mat_code,"
                + "substitute_flag,mat_batch,warehouse_code,site_code,num,mat_detail_type) "
                + "VALUES (?,1,'F1','0','B1','M1','R1','REQ1',?,'BATCH1','W1','S1',?,'RAW')",
                id, substitute, new BigDecimal(amount));
    }

    private void feed(long id, Boolean substitute, Boolean locked) {
        db.update("INSERT INTO mes_warehouse_feed "
                + "(id,task_code,arrange_code,produce_task_code,mat_code,reservation_no,requirement_mat_code,"
                + "substitute_flag,mat_batch,warehouse_code,site_code,feed_num,used_num,is_lock,factory_code,del_flag) "
                + "VALUES (?,'T1','A1','P1','M1','R1','REQ1',?,'BATCH1','W1','S1',10,3,?,'F1','0')",
                id, substitute, locked);
    }
}
