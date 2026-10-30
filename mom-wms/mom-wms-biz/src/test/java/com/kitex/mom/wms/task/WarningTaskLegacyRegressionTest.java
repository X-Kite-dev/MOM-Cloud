package com.kitex.mom.wms.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.impl.WmsMaterialWarningRuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 首三项旧实现RED见 /tmp/mom-warning-red.log；改造后分别由任务、快照和事务回归承接。 */
class WarningTaskLegacyRegressionTest {
	private final RemoteMaterialService remote = mock(RemoteMaterialService.class);
	private final RemoteMessagePushService push = mock(RemoteMessagePushService.class);
	private final WmsLiveStockService stocks = mock(WmsLiveStockService.class);
	private final WmsMaterialWarningRuntimeService runtime = mock(WmsMaterialWarningRuntimeService.class);
	private final WarningTask task = new WarningTask(remote, push, stocks, runtime, new ObjectMapper());

	@BeforeEach
	void metadata() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "warning-task-test"),
				WmsLiveStockEntity.class);
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void changedConfigurationMustApplyOnNextRun() {
		var old = config(365);
		var updated = config(1);
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(old)), R.ok(List.of(updated)));
		when(stocks.list(any(Wrapper.class))).thenAnswer(call -> List.of(candidate()));
		when(runtime.evaluate(anyLong(), any())).thenReturn(List.of());
		task.runScope(1L, "F1");
		task.runScope(1L, "F1");
		verify(remote, times(2)).warningConfigurations(1L, "F1");
		verify(runtime).evaluate(1L, old);
		verify(runtime).evaluate(1L, updated);

		assertNull(TenantContextHolder.getTenantId());
		assertNull(FactoryContextHolder.getFactoryCode());
	}

	@Test
	void zeroDayThresholdMustCalculateCrossYearElapsedDaysAtTriggerTime() {
		WmsMaterialWarningEntity row = new WmsMaterialWarningEntity();
		row.setMatProduceDate(LocalDate.of(2025, 12, 31));
		row.setWarningDays(0);
		row.setType("0");
		row.setCreateTime(LocalDateTime.of(2026, 1, 5, 0, 0));

		assertEquals(5L, row.getExcessDays());
		assertEquals("2025-12-31", row.getWarningDeadline());

		row.setWarningDays(null);

		assertNull(row.getExcessDays());
	}

	@Test
	void defaultModeMustNotSendTenantWideInventoryDetails() {
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(config(0))));
		when(stocks.list(any(Wrapper.class))).thenReturn(List.of(candidate()));
		when(runtime.evaluate(anyLong(), any())).thenReturn(List.of(new WmsMaterialWarningEntity()));
		when(push.businessPushScoped(anyLong(), anyString(), any())).thenReturn(R.ok(true));
		task.runScope(1L, "F1");
		verifyZeroInteractions(push);
	}

	@Test
	void persistedWarningPushMustCarryScopeAndNotClaimExternalDelivery() {
		var warning = new WmsMaterialWarningEntity();
		warning.setType("0");
		warning.setWarningDays(0);
		warning.setMatProduceDate(LocalDate.of(2025, 12, 31));
		warning.setCreateTime(LocalDateTime.of(2026, 1, 5, 0, 0));
		warning.setBillNo("MW-1-0");
		warning.setMatName("禁止出现在租户提醒中的物料");
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(config(0))));
		when(stocks.list(any(Wrapper.class))).thenReturn(List.of(candidate()));
		when(runtime.evaluate(anyLong(), any())).thenReturn(List.of(warning));
		when(push.businessPushScoped(eq(1L), eq("F1"), any())).thenReturn(R.ok(true));
		task.runScope(1L, "F1", "TENANT_BUSINESS");
		verify(push).businessPushScoped(eq(1L), eq("F1"), argThat(m -> m.getMessage().contains("MW-1-0")
				&& !m.getMessage().contains("物料禁止") && !m.getMessage().contains("禁止出现在")));
		verify(push, never()).businessPush(any());
	}

	@Test
	void remoteFailureMustBeReportedWithoutRepeatingCommittedStockWork() {
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(config(0))));
		when(stocks.list(any(Wrapper.class))).thenReturn(List.of(candidate()));
		when(runtime.evaluate(anyLong(), any())).thenReturn(List.of(new WmsMaterialWarningEntity()));
		when(push.businessPushScoped(anyLong(), anyString(), any())).thenReturn(R.failed("rejected"));
		TenantContextHolder.setTenantId(9L);
		FactoryContextHolder.setFactoryCode("previous");

		assertThrows(RuntimeException.class, () -> task.runScope(1L, "F1", "TENANT_BUSINESS"));
		assertEquals(9L, TenantContextHolder.getTenantId());
		assertEquals("previous", FactoryContextHolder.getFactoryCode());

		verify(runtime, times(1)).evaluate(anyLong(), any());
	}

	@Test
	void duplicateOrCrossScopeConfigurationsMustStopBeforeAnyStockWork() {
		var other = config(1);
		other.setTenantId(2L);
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(other)),
				R.ok(List.of(config(1), config(2))));

		assertThrows(RuntimeException.class, () -> task.runScope(1L, "F1"));
		assertThrows(RuntimeException.class, () -> task.runScope(1L, "F1"));

		verifyZeroInteractions(stocks, runtime, push);
	}

	@Test
	void missingScopeMustNeverStartAnonymousGlobalJob() {
		assertThrows(RuntimeException.class, () -> task.runScope(null, "F1"));
		assertThrows(RuntimeException.class, () -> task.runScope(1L, ""));
		assertThrows(Exception.class, () -> task.materialWarning());
		assertThrows(RuntimeException.class, () -> task.runScope(1L, "F1", "UNKNOWN"));

		verifyZeroInteractions(remote, stocks, runtime, push);
	}

	@Test
	void oneBadStockMustNotSuppressRemainingStocks() {
		var a = candidate();
		var b = candidate();
		b.setId(2L);
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(config(0))));
		when(stocks.list(any(Wrapper.class))).thenReturn(List.of(a, b));
		when(runtime.evaluate(eq(1L), any())).thenThrow(new IllegalStateException("bad dates"));
		when(runtime.evaluate(eq(2L), any())).thenReturn(List.of());

		assertThrows(RuntimeException.class, () -> task.runScope(1L, "F1"));

		verify(runtime).evaluate(eq(2L), any());
	}

	@Test
	void fullPageMustContinueWithStrictCursorAndScope() {
		var first = new java.util.ArrayList<WmsLiveStockEntity>();
		for (long id = 1; id <= 200; id++) {
			var row = candidate();
			row.setId(id);
			first.add(row);
		}
		var last = candidate();
		last.setId(201L);
		when(remote.warningConfigurations(1L, "F1")).thenReturn(R.ok(List.of(config(0))));
		when(stocks.list(any(Wrapper.class))).thenReturn(first, List.of(last));
		when(runtime.evaluate(anyLong(), any())).thenReturn(List.of());
		task.runScope(1L, "F1");
		verify(runtime, times(201)).evaluate(anyLong(), any());
		verify(stocks, times(2)).list(argThat((Wrapper<WmsLiveStockEntity> w) -> {
			String sql = w.getSqlSegment();
			return sql.contains("tenant_id") && sql.contains("factory_code")
					&& sql.contains("AND (") && sql.contains("LIMIT 200");
		}));
	}

	private WmsLiveStockEntity candidate() {
		var row = new WmsLiveStockEntity().setMatCode("M1").setFactoryCode("F1");
		row.setId(1L);
		row.setTenantId(1L);
		return row;
	}

	private MdmMaterialWarningConfigDTO config(int days) {
		var row = new MdmMaterialWarningConfigDTO();
		row.setTenantId(1L);
		row.setFactoryCode("F1");
		row.setMaterialCode("M1");
		row.setExpiration(true);
		row.setExpirationDay(days);
		return row;
	}
}
