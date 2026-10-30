package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.mapper.WmsWarehouseBizMapper;
import com.kitex.mom.wms.service.impl.WmsWarehouseBizServiceImpl;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC持久边界与真实Spring事务验证显式空配置和写失败回滚，不模拟PG锁/组织拦截器。 */
class WmsWarehouseConfigurationPersistenceTest {
	private WmsWarehouseBizServiceImpl service;
	private JdbcTemplate db;
	private int inserted;
	private int failInsertAt;
	private boolean failDelete;

	@BeforeEach
	void setup() {
		var source = new DriverManagerDataSource(
				"jdbc:h2:mem:warehouse-config-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("create table wms_warehouse_biz(id bigint primary key,tenant_id "
				+ "bigint,factory_code varchar,warehouse_code varchar,biz_type "
				+ "varchar)");
		db.update("insert into wms_warehouse_biz values(1,1,'F1','W1','old')");
		db.update("insert into wms_warehouse_biz values(2,2,'F1','W1','foreign')");
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "biz"),
				WmsWarehouseBizEntity.class);
		var mapper = mock(WmsWarehouseBizMapper.class);
		when(mapper.selectCount(any(Wrapper.class)))
				.thenAnswer(c -> query("select count(*) from wms_warehouse_biz ", c.getArgument(0)));
		when(mapper.delete(any(Wrapper.class))).thenAnswer(c -> {
			if (failDelete) {
				return 0;
			}
			var bound = bind("delete from wms_warehouse_biz ", c.getArgument(0));
			return db.update(bound.sql(), bound.values());
		});
		when(mapper.insert(any(WmsWarehouseBizEntity.class))).thenAnswer(c -> insert(c.getArgument(0)));
		var target = new WmsWarehouseBizServiceImpl() {
			@Override
			public boolean remove(Wrapper<WmsWarehouseBizEntity> query) {
				var bound = bind("delete from wms_warehouse_biz ", query);
				return db.update(bound.sql(), bound.values()) > 0;
			}

			@Override
			public boolean saveBatch(Collection<WmsWarehouseBizEntity> rows) {
				boolean ok = !rows.isEmpty();
				for (var row : rows) {
					ok &= insert(row) == 1;
				}
				return ok;
			}
		};
		ReflectionTestUtils.setField(target, "baseMapper", mapper);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (WmsWarehouseBizServiceImpl) proxy.getProxy();
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
	}

	@AfterEach
	void clear() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	private int insert(WmsWarehouseBizEntity row) {
		inserted++;
		if (inserted == failInsertAt) {
			return 0;
		}
		return db.update("insert into wms_warehouse_biz values(?,?,?,?,?)", 10L + inserted, row.getTenantId(),
				row.getFactoryCode(), row.getWarehouseCode(), row.getBizType());
	}

	private record Bound(String sql, Object[] values) {
	}

	private Bound bind(String prefix, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}")
				.matcher(wrapper.getCustomSqlSegment());
		var values = new ArrayList<>();
		var sql = new StringBuilder();
		var params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
		while (matcher.find()) {
			values.add(params.get(matcher.group(1)));
			matcher.appendReplacement(sql, "?");
		}
		matcher.appendTail(sql);
		return new Bound(prefix + sql, values.toArray());
	}

	private long query(String prefix, Wrapper<?> wrapper) {
		var bound = bind(prefix, wrapper);
		return db.queryForObject(bound.sql(), Long.class, bound.values());
	}

	private List<WmsWarehouseBizCheckVO> snapshot(List<String> values) {
		return List.of(new WmsWarehouseBizCheckVO().setWarehouseCode("W1").setBizs(values));
	}

	private void assertOldRows() {
		assertEquals(List.of("old", "foreign"),
				db.queryForList("select biz_type from wms_warehouse_biz order by id", String.class));
	}

	@Test
	void savingActiveWarehousePreservesDisabledWarehouseConfiguration() {
		db.update("insert into wms_warehouse_biz values(3,1,'F1','STOPPED','retained')");

		service.updateAll(snapshot(List.of("new")));

		assertEquals("retained", db.queryForObject("select biz_type from wms_warehouse_biz "
				+ "where tenant_id=1 and warehouse_code='STOPPED'", String.class));
	}

	@Test
	void noActiveWarehousesCannotEraseDisabledConfiguration() {
		assertTrue(service.updateAll(List.of()));

		assertOldRows();
	}

	@Test
	void emptySelectedBusinessPersistsDenyAllMarker() {
		assertTrue(service.updateAll(snapshot(List.of())));

		assertEquals("__NONE__",
				db.queryForObject("select biz_type from wms_warehouse_biz where tenant_id=1", String.class));
	}

	@Test
	void replacementCannotDeleteAnotherTenantConfiguration() {
		service.updateAll(snapshot(List.of("new")));

		assertEquals("foreign",
				db.queryForObject("select biz_type from wms_warehouse_biz where tenant_id=2", String.class));
	}

	@Test
	void zeroDeleteRollsBackWithoutAddingAnotherConfiguration() {
		failDelete = true;

		assertThrows(CheckedException.class, () -> service.updateAll(snapshot(List.of("new"))));

		assertOldRows();
	}

	@Test
	void laterInsertFailureRollsBackDeletionAndFirstInsert() {
		failInsertAt = 2;

		assertThrows(CheckedException.class, () -> service.updateAll(snapshot(List.of("one", "two"))));

		assertOldRows();
	}

	@Test
	void missingFactoryCannotDeleteConfiguration() {
		FactoryContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.updateAll(snapshot(List.of())));

		assertOldRows();
	}

	@Test
	void missingTenantCannotDeleteConfiguration() {
		TenantContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.updateAll(snapshot(List.of())));

		assertOldRows();
	}
}
