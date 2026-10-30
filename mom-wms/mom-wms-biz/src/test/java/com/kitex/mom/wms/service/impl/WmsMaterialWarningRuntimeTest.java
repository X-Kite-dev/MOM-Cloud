package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsMaterialWarningService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实Spring事务/H2行锁；既有MyBatis/预警保存边界用JDBC适配，不能替代目标PG联验。 */
class WmsMaterialWarningRuntimeTest {
	private JdbcTemplate db;
	private WmsMaterialWarningRuntimeService service;
	private WmsLiveStockMapper stocks;
	private WmsMaterialWarningService warnings;
	private MdmMaterialWarningConfigDTO config;
	private boolean failMarker, failWarning;

	@BeforeEach
	void setup() {
		var ds = new DriverManagerDataSource(
				"jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
		ds.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(ds);
		db.execute("create table stock(id bigint primary key, tenant bigint, factory "
				+ "varchar, quantity double, produced date, entered timestamp, "
				+ "expiration boolean, slow boolean)");
		db.execute("create table warning(bill varchar, kind varchar, days int)");
		db.update("insert into stock values(1,1,'F1',10,?,?,false,false)", LocalDate.now().minusDays(30),
				LocalDateTime.now().minusDays(30));
		var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "warning-test");
		for (var type : List.of(WmsLiveStockEntity.class, WmsMaterialWarningEntity.class)) {
			TableInfoHelper.initTableInfo(assistant, type);
		}
		stocks = mock(WmsLiveStockMapper.class);
		warnings = mock(WmsMaterialWarningService.class);
		when(stocks.selectOne(any(Wrapper.class))).thenAnswer(call -> {
			Wrapper<?> wrapper = call.getArgument(0);
			String sql = wrapper.getSqlSegment();
			assertTrue(sql.contains("tenant_id") && sql.contains("factory_code") && sql.contains("FOR UPDATE"));
			return db.query("select * from stock where id=1 for update", (r, n) -> {
				var s = new WmsLiveStockEntity().setMatCode("M1").setFactoryCode(r.getString("factory"))
						.setMatNum(r.getDouble("quantity"));
				s.setId(1L);
				s.setTenantId(r.getLong("tenant"));
				s.setMatProduceDate(r.getDate("produced") == null ? null : r.getDate("produced").toLocalDate());
				s.setInTime(r.getTimestamp("entered") == null ? null : r.getTimestamp("entered").toLocalDateTime());
				s.setExpirationStatus((Boolean) r.getObject("expiration"));
				s.setSlowMaterialStatus((Boolean) r.getObject("slow"));
				return s;
			}).stream().findFirst().orElse(null);
		});
		when(warnings.save(any())).thenAnswer(call -> {
			if (failWarning) {
				return false;
			}
			WmsMaterialWarningEntity w = call.getArgument(0);
			assertEquals(1L, w.getTenantId());
			assertEquals("F1", w.getFactoryCode());
			assertEquals("SYSTEM:material_warning", w.getCreateBy());
			assertNotNull(w.getCreateTime());
			db.update("insert into warning values(?,?,?)", w.getBillNo(), w.getType(), w.getWarningDays());
			return true;
		});
		when(stocks.update(any(), any(Wrapper.class))).thenAnswer(call -> {
			if (failMarker) {
				return 0;
			}
			WmsLiveStockEntity patch = call.getArgument(0);
			assertNull(patch.getMatNum()); // 只改标记，不覆盖并行库存数量或其他字段。
			if (patch.getExpirationStatus() != null) {
				db.update("update stock set expiration=? where id=1", patch.getExpirationStatus());
			}
			if (patch.getSlowMaterialStatus() != null) {
				db.update("update stock set slow=? where id=1", patch.getSlowMaterialStatus());
			}
			return 1;
		});
		var proxy = new ProxyFactory(new WmsMaterialWarningRuntimeService(stocks, warnings));
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
				new AnnotationTransactionAttributeSource()));
		service = (WmsMaterialWarningRuntimeService) proxy.getProxy();
		scope();
		config = new MdmMaterialWarningConfigDTO();
		config.setTenantId(1L);
		config.setFactoryCode("F1");
		config.setMaterialCode("M1");
		config.setExpiration(true);
		config.setExpirationDay(1);
		config.setSlowMaterial(true);
		config.setSlowMaterialDay(1);
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void warningAndFlagsCommitTogetherAndRerunIsIdempotent() {
		assertEquals(2, service.evaluate(1L, config).size());

		assertTrue(flag("expiration"));
		assertTrue(flag("slow"));

		assertTrue(service.evaluate(1L, config).isEmpty());

		assertEquals(2, count());
	}

	@Test
	void markerFailureRollsBackBothWarnings() {
		failMarker = true;

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		assertEquals(0, count());
		assertFalse(flag("expiration"));
		assertFalse(flag("slow"));
	}

	@Test
	void warningPersistenceFailureMustNotMarkStockAsWarned() {
		failWarning = true;

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		assertEquals(0, count());
		assertFalse(flag("expiration"));
	}

	@Test
	void secondWarningFailureAlsoRollsBackFirstWarning() {
		doAnswer(call -> {
			WmsMaterialWarningEntity w = call.getArgument(0);
			if ("1".equals(w.getType())) {
				return false;
			}
			db.update("insert into warning values('first','0',1)");
			return true;
		}).when(warnings).save(any());

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		assertEquals(0, count());
		assertFalse(flag("expiration"));
	}

	@Test
	void emptyAndNegativeStockDoNotGenerateAlerts() {
		for (double n : new double[] { 0, -1 }) {
			db.update("update stock set quantity=?", n);
			assertTrue(service.evaluate(1L, config).isEmpty());
		}

		assertEquals(0, count());
	}

	@Test
	void nullSwitchesMeanDisabledAndNullFlagsMeanNotYetWarned() {
		config.setExpiration(null);
		config.setSlowMaterial(null);

		assertTrue(service.evaluate(1L, config).isEmpty());

		config.setExpiration(true);
		db.update("update stock set expiration=null,slow=null");

		assertEquals(1, service.evaluate(1L, config).size());
	}

	@Test
	void missingAndInvalidEnabledThresholdsOrDatesFailBeforeAnyWrites() {
		config.setSlowMaterialDay(null);

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		config.setSlowMaterialDay(-1);

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		config.setSlowMaterialDay(1);
		db.update("update stock set produced=null");

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		db.update("update stock set produced=?,entered=null", LocalDate.now().minusDays(30));

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		assertEquals(0, count());
		assertFalse(flag("expiration"));
	}

	@Test
	void zeroDayExpiryStillAllowsProductionDayAndWarnsNextDay() {
		config.setSlowMaterial(false);
		config.setExpirationDay(0);
		db.update("update stock set produced=?", LocalDate.now());

		assertTrue(service.evaluate(1L, config).isEmpty());

		db.update("update stock set produced=?", LocalDate.now().minusDays(1));

		var result = service.evaluate(1L, config);

		assertEquals(1L, result.get(0).getExcessDays());
	}

	@Test
	void configurationChangesAreAppliedToNotYetWarnedStock() {
		config.setExpirationDay(365);
		config.setSlowMaterial(false);

		assertTrue(service.evaluate(1L, config).isEmpty());

		config.setExpirationDay(1);

		assertEquals(1, service.evaluate(1L, config).size());
	}

	@Test
	void scopeMismatchOrSkipMustFail() {
		config.setTenantId(2L);

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		config.setTenantId(1L);
		db.update("update stock set factory='F2'");

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		TenantContextHolder.setTenantSkip();

		assertThrows(RuntimeException.class, () -> service.evaluate(1L, config));

		assertEquals(0, count());
	}

	@Test
	void twoConcurrentEvaluationsOnlyCreateOneWarningPerType() throws Exception {
		var pool = Executors.newFixedThreadPool(2);

		try {
			java.util.concurrent.Callable<Integer> job = () -> {
				scope();
				try {
					return service.evaluate(1L, config).size();
				} finally {
					cleanup();
				}
			};
			var first = pool.submit(job);
			var second = pool.submit(job);
			assertEquals(2, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
			assertEquals(2, count());
		} finally {
			pool.shutdownNow();
		}
	}

	private void scope() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	private boolean flag(String column) {
		return Boolean.TRUE.equals(db.queryForObject("select " + column + " from stock where id=1", Boolean.class));
	}

	private int count() {
		return db.queryForObject("select count(*) from warning", Integer.class);
	}
}
