package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.controller.WmsMaterialWarningController;
import com.kitex.mom.wms.dto.WmsMaterialWarningQueryDTO;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WmsMaterialWarningQueryTest {
	private final WmsMaterialWarningServiceImpl service = new WmsMaterialWarningServiceImpl();

	@BeforeEach
	void setup() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "warning-query-test"),
				WmsMaterialWarningEntity.class);
		TenantContextHolder.setTenantId(7L);
		FactoryContextHolder.setFactoryCode("F7");
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void businessQueriesAlwaysIncludeExplicitTenantAndFactory() {
		var q = new WmsMaterialWarningQueryDTO();
		q.setBillNo("MW-");
		q.setType("0");

		var wrapper = service.query(q);

		String sql = wrapper.getSqlSegment();

		assertTrue(sql.contains("tenant_id") && sql.contains("factory_code") && sql.contains("bill_no"));
		assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
		assertTrue(wrapper.getParamNameValuePairs().containsValue("F7"));
	}

	@Test
	void dateRangeIncludesTheWholeEndDate() {
		var q = new WmsMaterialWarningQueryDTO();
		q.setCreateTime(new LocalDate[] { LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2) });

		var wrapper = service.query(q);

		String sql = wrapper.getSqlSegment();

		assertTrue(sql.contains("create_time >=") && sql.contains("create_time <"));
		assertTrue(wrapper.getParamNameValuePairs().containsValue(LocalDateTime.of(2026, 1, 3, 0, 0)));
	}

	@Test
	void missingScopeAndMalformedRangesMustFail() {
		TenantContextHolder.clear();

		assertThrows(RuntimeException.class, () -> service.query(null));

		TenantContextHolder.setTenantId(7L);
		var q = new WmsMaterialWarningQueryDTO();
		q.setCreateTime(new LocalDate[] { LocalDate.now() });

		assertThrows(RuntimeException.class, () -> service.query(q));
	}

	@Test
	void exportCarriesServerSnapshotRatherThanClientClock() {
		var row = new WmsMaterialWarningEntity();
		row.setType("1");
		row.setInTime(LocalDateTime.of(2025, 12, 30, 10, 30));
		row.setWarningDays(2);
		row.setCreateTime(LocalDateTime.of(2026, 1, 2, 10, 29));
		var exporter = new WmsMaterialWarningServiceImpl() {
			@Override
			public List<WmsMaterialWarningEntity> list(Wrapper<WmsMaterialWarningEntity> ignored) {
				return List.of(row);
			}
		};
		var result = exporter.export(new WmsMaterialWarningQueryDTO(), null).get(0);

		assertEquals(0L, result.getExcessDays());
		assertEquals("2026-01-01 10:30:00", result.getWarningDeadline());
	}

	@Test
	void traceRecordsCannotBeDeletedThroughLegacyCrudEndpoint() {
		assertThrows(RuntimeException.class,
				() -> new WmsMaterialWarningController(service).removeById(new Long[] { 1L }));
	}
}
