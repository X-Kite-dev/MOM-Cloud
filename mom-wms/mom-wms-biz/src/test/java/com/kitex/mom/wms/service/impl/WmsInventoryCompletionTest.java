package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression: a status-only endpoint must not skip inventory adjustment or invent a count. */
public class WmsInventoryCompletionTest {
	@org.junit.jupiter.api.AfterEach
	void clearContext() {
		com.kitex.common.data.tenant.TenantContextHolder.clear();
		com.kitex.common.data.orgFilter.FactoryContextHolder.clear();
	}

	@Test
	void planCannotCompleteAnUnadjustedDifference() {
		assertThrows(CheckedException.class, () -> fixture(7d).complete(1L));
	}

	@Test
	void detailCannotCompleteAnUnadjustedDifference() {
		assertThrows(CheckedException.class, () -> fixture(7d).detailComplete(2L));
	}

	@Test
	void finishingCountMustNotTurnMissingInputIntoZero() {
		assertThrows(CheckedException.class, () -> fixture(null).appInventorySuccess(1L));
	}

	private WmsInsideInventoryPlanServiceImpl fixture(Double counted) {
		var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
		var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "inventory-regression");
		com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
				WmsInsideInventoryPlanEntity.class);
		com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
				WmsInsideInventoryPlanDetailEntity.class);
		com.kitex.common.data.tenant.TenantContextHolder.setTenantId(1L);
		com.kitex.common.data.orgFilter.FactoryContextHolder.setFactoryCode("F1");
		WmsInsideInventoryPlanEntity plan = new WmsInsideInventoryPlanEntity();
		plan.setTenantId(1L);
		plan.setId(1L);
		plan.setStatus("1");
		plan.setHandleState("0");
		plan.setFactoryCode("F1");
		WmsInsideInventoryPlanDetailEntity detail = new WmsInsideInventoryPlanDetailEntity();
		detail.setTenantId(1L);
		detail.setId(2L);
		detail.setPlanId(1L);
		detail.setCurrentNumber(10d);
		detail.setInventoryNum(counted);
		detail.setStatus(counted == null ? "0" : "1");
		detail.setPlStatus("0");
		detail.setFactoryCode("F1");
		WmsInsideInventoryPlanMapper plans = proxy(WmsInsideInventoryPlanMapper.class, plan, List.of(plan));
		WmsInsideInventoryPlanDetailMapper details = proxy(WmsInsideInventoryPlanDetailMapper.class, detail,
				List.of(detail));
		WmsInsideInventoryPlanServiceImpl service = new WmsInsideInventoryPlanServiceImpl(details,
				proxy(WmsLiveStockMapper.class, null, List.of()), null, null, null,
				new WmsInventoryPlanAccess(plans, details), null,
				null);
		ReflectionTestUtils.setField(service, "baseMapper", plans);
		return service;
	}

	@SuppressWarnings("unchecked")
	static <T> T proxy(Class<T> type, Object row, List<?> rows) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, args) -> {
			if (m.getName().equals("selectById") || m.getName().equals("selectOne")) {
				return row;
			}
			if (m.getName().equals("selectList")) {
				return rows;
			}
			if (m.getReturnType() == int.class) {
				return 1;
			}
			if (m.getReturnType() == boolean.class) {
				return true;
			}
			return null;
		});
	}

	public static void main(String[] args) throws Exception {
		int failures = 0;
		for (var method : WmsInventoryCompletionTest.class.getDeclaredMethods()) {
			if (method.getAnnotation(Test.class) == null) {
				continue;
			}
			try {
				method.invoke(new WmsInventoryCompletionTest());
				System.out.println("PASS " + method.getName());
			} catch (java.lang.reflect.InvocationTargetException ex) {
				failures++;
				System.out.println("FAIL " + method.getName() + ": " + ex.getCause());
			} finally {
				com.kitex.common.data.tenant.TenantContextHolder.clear();
				com.kitex.common.data.orgFilter.FactoryContextHolder.clear();
			}
		}
		if (failures > 0) {
			throw new AssertionError(failures + " inventory regression failures");
		}
	}
}
