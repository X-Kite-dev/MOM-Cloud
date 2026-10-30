package com.kitex.mom.wms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.mapper.WmsInventoryCountTemplateMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryCountTemplateServiceTest {
	private final WmsInventoryCountTemplateMapper templates = mock(WmsInventoryCountTemplateMapper.class);
	private final WmsInventoryScopeSuggestionService suggestions = mock(WmsInventoryScopeSuggestionService.class);
	private final WmsInsideInventoryPlanService plans = mock(WmsInsideInventoryPlanService.class);
	private final RemoteSeqConfigService sequences = mock(RemoteSeqConfigService.class);
	private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
	private final RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
	private final WmsWarehouseMenuMapper menus = mock(WmsWarehouseMenuMapper.class);
	private final PermissionService permissions = mock(PermissionService.class);
	private final WmsInventoryCountTemplateService service = new WmsInventoryCountTemplateService(
			templates, suggestions, plans, sequences, warehouses, locations, menus,
			new WmsInventoryWarehousePermission(permissions), permissions, new ObjectMapper());

	@BeforeEach
	void setScope() {
		TenantContextHolder.setTenantId(11L);
		FactoryContextHolder.setFactoryCode("F1");
		when(permissions.hasPermission("wms_wmsInsideInventoryPlan_add_W1")).thenReturn(true);
		when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
		MdmWarehouseEntity warehouse = new MdmWarehouseEntity()
				.setWarehouseCode("W1").setWarehouseName("原辅仓").setFactoryCode("F1").setStatus(true);
		warehouse.setTenantId(11L);
		when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse));
		MdmWarehouseLocationEntity site = new MdmWarehouseLocationEntity();
		site.setWarehouseCode("W1");
		site.setLocationCode("S1");
		site.setLocationName("一号库位");
		site.setFactoryCode("F1");
		site.setLocationLevel(3);
		site.setStatus(true);
		site.setTenantId(11L);
		when(locations.getByCode("S1")).thenReturn(R.ok(site));
		MomUser user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("operator");
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user, null));
	}

	@AfterEach
	void clearScope() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void saveRejectsDuplicateSitesAndForeignLocationsBeforeWriting() {
		var duplicate = new WmsInventoryCountTemplateService.SaveCommand(
				"CYCLE_A", "循环盘点", "W1", List.of("S1", "S1"), "ALL_POSITIVE", null,
				"SNAPSHOT", true, 0);
		assertThrows(CheckedException.class, () -> service.save(duplicate));

		var foreign = new MdmWarehouseLocationEntity();
		foreign.setWarehouseCode("W2");
		foreign.setLocationCode("S1");
		foreign.setLocationName("外仓库位");
		foreign.setFactoryCode("F1");
		foreign.setLocationLevel(3);
		foreign.setStatus(true);
		foreign.setTenantId(11L);
		when(locations.getByCode("S1")).thenReturn(R.ok(foreign));
		var command = new WmsInventoryCountTemplateService.SaveCommand(
				"CYCLE_A", "循环盘点", "W1", List.of("S1"), "ALL_POSITIVE", null,
				"SNAPSHOT", true, 0);
		assertThrows(CheckedException.class, () -> service.save(command));
		verify(templates, never()).upsert(any(), any(), any(), any(), any(), any(), any(),
				any(), any(), anyBoolean(), anyInt(), any());
	}

	@Test
	void savedTemplateKeepsItsWarehouseAndVersion() {
		when(templates.countInWarehouse(11L, "F1", "W1")).thenReturn(0L);
		when(templates.upsert(11L, "F1", "CYCLE_A", "循环盘点", "W1", "ALL_POSITIVE",
				null, "[\"S1\"]", "SNAPSHOT", true, 0, "operator")).thenReturn(1);
		var command = new WmsInventoryCountTemplateService.SaveCommand(
				"CYCLE_A", "循环盘点", "W1", List.of("S1"), "ALL_POSITIVE", null,
				"SNAPSHOT", true, 0);

		var saved = service.save(command);

		assertEquals("W1", saved.warehouseCode());
		assertEquals(1, saved.version());
		assertEquals(List.of("S1"), saved.siteCodes());
		verify(menus).lockConfiguration("WMS_WAREHOUSE_MENU:11:F1");
	}

	@Test
	void configurationAndTriggerRequireTheirOwnWarehousePermissions() {
		when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(false);
		var command = new WmsInventoryCountTemplateService.SaveCommand(
				"CYCLE_A", "循环盘点", "W1", List.of("S1"), "ALL_POSITIVE", null,
				"SNAPSHOT", true, 0);
		assertThrows(CheckedException.class, () -> service.save(command));
		verify(templates, never()).upsert(any(), any(), any(), any(), any(), any(), any(),
				any(), any(), anyBoolean(), anyInt(), any());

		when(permissions.hasPermission("wms_wmsInsideInventoryPlan_add_W1")).thenReturn(false);
		assertThrows(CheckedException.class, () -> service.trigger(new WmsInventoryCountTemplateService.RunCommand(
				"W1", "CYCLE_A", 3, "req-1")));
		verify(templates, never()).reserveRun(any(), any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void previewDoesNotCreateAPlanAndRejectsAnEmptyMatch() {
		when(templates.find(11L, "F1", "CYCLE_A")).thenReturn(template());
		when(suggestions.suggest("W1", "S1", "NEAR_EXPIRY", 14)).thenReturn(suggestion());

		var preview = service.preview("W1", "CYCLE_A", 3);
		assertEquals(1, preview.rows().size());
		verify(plans, never()).saveDeep(any());

		when(suggestions.suggest("W1", "S1", "NEAR_EXPIRY", 14)).thenReturn(
				new WmsInventoryScopeSuggestionService.Suggestion("W1", "S1", 0,
						"NEAR_EXPIRY", 14, List.of()));
		assertThrows(CheckedException.class, () -> service.preview("W1", "CYCLE_A", 3));
		verify(plans, never()).saveDeep(any());
	}

	@Test
	void triggerCreatesAnExecutablePlanAndRecordsTheOriginalRequest() {
		when(templates.findForUpdate(11L, "F1", "CYCLE_A")).thenReturn(template());
		when(templates.reserveRun(eq(11L), eq("F1"), eq("req-1"), eq("CYCLE_A"), eq(3),
				anyString(), eq("operator")))
				.thenReturn(1);
		when(suggestions.suggest("W1", "S1", "NEAR_EXPIRY", 14)).thenReturn(suggestion());
		when(sequences.create("inside_inventory")).thenReturn(R.ok("PD-20261027-01", "ok"));
		when(plans.saveDeep(any())).thenAnswer(invocation -> {
			WmsInsideInventoryPlanEntity plan = invocation.getArgument(0);
			assertEquals("W1", plan.getWarehouseCode());
			assertEquals("SNAPSHOT", plan.getStockFreezeMode());
			assertEquals(1, plan.getWmsInsideInventoryPlanDetailList().size());
			assertEquals("S1", plan.getWmsInsideInventoryPlanDetailList().get(0).getSiteCode());
			plan.setId(901L);
			return true;
		});
		when(templates.completeRun(11L, "F1", "req-1", 901L, "PD-20261027-01")).thenReturn(1);

		var result = service.trigger(new WmsInventoryCountTemplateService.RunCommand(
				"W1", "CYCLE_A", 3, "req-1"));

		assertTrue(result.created());
		assertEquals(901L, result.planId());
		assertEquals("PD-20261027-01", result.planNo());
		verify(templates).completeRun(11L, "F1", "req-1", 901L, "PD-20261027-01");
	}

	@Test
	void exactReplayReturnsTheOriginalPlanWithoutResuggestingOrCreating() {
		when(templates.findForUpdate(11L, "F1", "CYCLE_A")).thenReturn(
				new WmsInventoryCountTemplateMapper.TemplateRow("CYCLE_A", "已修改模板", "W1",
						"LONG_STAY", 90, "[\"S1\"]", "SNAPSHOT", false, 4));
		when(templates.reserveRun(eq(11L), eq("F1"), eq("req-1"), eq("CYCLE_A"), eq(3),
				anyString(), eq("operator")))
				.thenReturn(0);
		when(templates.findRunForUpdate(11L, "F1", "req-1"))
				.thenReturn(new WmsInventoryCountTemplateMapper.RunRow("CYCLE_A", 3, 901L, "PD-1"));
		WmsInsideInventoryPlanEntity original = new WmsInsideInventoryPlanEntity();
		original.setId(901L);
		original.setTenantId(11L);
		original.setFactoryCode("F1");
		original.setWarehouseCode("W1");
		original.setPlanNo("PD-1");
		when(plans.getDetails(901L)).thenReturn(original);

		var result = service.trigger(new WmsInventoryCountTemplateService.RunCommand(
				"W1", "CYCLE_A", 3, "req-1"));

		assertEquals(901L, result.planId());
		assertEquals(false, result.created());
		verify(suggestions, never()).suggest(any(), any(), any(), any());
		verify(plans, never()).saveDeep(any());
	}

	@Test
	void staleTemplateVersionAndChangedReplayCannotCreateAPlan() {
		when(templates.findForUpdate(11L, "F1", "CYCLE_A")).thenReturn(template());
		when(templates.reserveRun(eq(11L), eq("F1"), eq("req-1"), eq("CYCLE_A"), eq(2),
				anyString(), eq("operator")))
				.thenReturn(1);
		assertThrows(CheckedException.class, () -> service.trigger(new WmsInventoryCountTemplateService.RunCommand(
				"W1", "CYCLE_A", 2, "req-1")));

		when(templates.reserveRun(eq(11L), eq("F1"), eq("req-2"), eq("CYCLE_A"), eq(3),
				anyString(), eq("operator")))
				.thenReturn(0);
		when(templates.findRunForUpdate(11L, "F1", "req-2"))
				.thenReturn(new WmsInventoryCountTemplateMapper.RunRow("OTHER", 3, 902L, "PD-2"));
		assertThrows(CheckedException.class, () -> service.trigger(new WmsInventoryCountTemplateService.RunCommand(
				"W1", "CYCLE_A", 3, "req-2")));
		verify(plans, never()).saveDeep(any());
	}

	@Test
	void recentRunsExposeTheOriginalValidatedConfiguration() {
		when(templates.find(11L, "F1", "CYCLE_A")).thenReturn(
				new WmsInventoryCountTemplateMapper.TemplateRow("CYCLE_A", "新版模板", "W1",
						"LONG_STAY", 90, "[\"S1\"]", "SNAPSHOT", true, 4));
		String oldSnapshot = "{\"templateCode\":\"CYCLE_A\",\"templateName\":\"临期盘点\","
				+ "\"warehouseCode\":\"W1\",\"ruleCode\":\"NEAR_EXPIRY\","
				+ "\"thresholdDays\":14,\"siteCodes\":[\"S1\"],"
				+ "\"stockFreezeMode\":\"SNAPSHOT\",\"enabled\":true,\"version\":3}";
		when(templates.recentRuns(11L, "F1", "W1", "CYCLE_A")).thenReturn(List.of(
				new WmsInventoryCountTemplateMapper.HistoryRow("req-1", 3, 901L, "PD-1",
						"operator", java.time.LocalDateTime.parse("2026-10-27T10:00:00"), oldSnapshot)));

		var history = service.recentRuns("W1", "CYCLE_A");

		assertEquals("NEAR_EXPIRY", history.get(0).snapshot().ruleCode());
		assertEquals(14, history.get(0).snapshot().thresholdDays());
		assertEquals(List.of("S1"), history.get(0).snapshot().siteCodes());
		assertEquals(3, history.get(0).snapshot().version());
	}

	@Test
	void recentRunsRejectMalformedOrForeignWarehouseSnapshots() {
		when(templates.find(11L, "F1", "CYCLE_A")).thenReturn(template());
		when(templates.recentRuns(11L, "F1", "W1", "CYCLE_A")).thenReturn(List.of(
				new WmsInventoryCountTemplateMapper.HistoryRow("req-1", 3, 901L, "PD-1",
						"operator", java.time.LocalDateTime.parse("2026-10-27T10:00:00"), "broken")));
		assertThrows(CheckedException.class, () -> service.recentRuns("W1", "CYCLE_A"));

		String foreignSnapshot = "{\"templateCode\":\"CYCLE_A\",\"templateName\":\"临期盘点\","
				+ "\"warehouseCode\":\"W2\",\"ruleCode\":\"ALL_POSITIVE\","
				+ "\"thresholdDays\":null,\"siteCodes\":[\"S1\"],"
				+ "\"stockFreezeMode\":\"SNAPSHOT\",\"enabled\":true,\"version\":3}";
		when(templates.recentRuns(11L, "F1", "W1", "CYCLE_A")).thenReturn(List.of(
				new WmsInventoryCountTemplateMapper.HistoryRow("req-1", 3, 901L, "PD-1",
						"operator", java.time.LocalDateTime.parse("2026-10-27T10:00:00"), foreignSnapshot)));
		assertThrows(CheckedException.class, () -> service.recentRuns("W1", "CYCLE_A"));
	}

	@Test
	void scheduledTriggerUsesExplicitScopeWithoutAUserPermissionAndCreatesAFormalPlan() {
		SecurityContextHolder.clearContext();
		when(permissions.hasPermission("wms_wmsInsideInventoryPlan_add_W1")).thenReturn(false);
		when(templates.findForUpdate(11L, "F1", "CYCLE_A")).thenReturn(template());
		when(templates.reserveRun(eq(11L), eq("F1"), anyString(), eq("CYCLE_A"), eq(3),
				anyString(), eq("WMS_SCHEDULE"))).thenReturn(1);
		when(suggestions.suggestScheduled("W1", "S1", "NEAR_EXPIRY", 14)).thenReturn(suggestion());
		when(sequences.create("inside_inventory")).thenReturn(R.ok("PD-SCHEDULE-1", "ok"));
		when(plans.saveDeep(any())).thenAnswer(invocation -> {
			WmsInsideInventoryPlanEntity plan = invocation.getArgument(0);
			assertEquals("WMS_SCHEDULE", plan.getCreateBy());
			assertEquals("WMS_SCHEDULE", plan.getUpdateBy());
			assertEquals("WMS_SCHEDULE", plan.getWmsInsideInventoryPlanDetailList().get(0).getCreateBy());
			plan.setId(902L);
			return true;
		});
		when(templates.completeRun(eq(11L), eq("F1"), anyString(), eq(902L), eq("PD-SCHEDULE-1")))
				.thenReturn(1);

		var created = service.triggerScheduled("W1", "CYCLE_A", LocalDateTime.parse("2026-10-27T02:00:00"));

		assertTrue(created.created());
		assertEquals(902L, created.planId());
		verify(suggestions).suggestScheduled("W1", "S1", "NEAR_EXPIRY", 14);
	}

	@Test
	void disabledTemplateDoesNotCreateAScheduledPlan() {
		when(templates.findForUpdate(11L, "F1", "CYCLE_A")).thenReturn(
				new WmsInventoryCountTemplateMapper.TemplateRow("CYCLE_A", "临期盘点", "W1",
						"NEAR_EXPIRY", 14, "[\"S1\"]", "SNAPSHOT", false, 3));

		var result = service.triggerScheduled("W1", "CYCLE_A", LocalDateTime.parse("2026-10-27T02:00:00"));

		assertEquals(null, result);
		verify(templates, never()).reserveRun(any(), any(), any(), any(), anyInt(), any(), any());
	}

	private WmsInventoryCountTemplateMapper.TemplateRow template() {
		return new WmsInventoryCountTemplateMapper.TemplateRow("CYCLE_A", "临期盘点", "W1",
				"NEAR_EXPIRY", 14, "[\"S1\"]", "SNAPSHOT", true, 3);
	}

	private WmsInventoryScopeSuggestionService.Suggestion suggestion() {
		return new WmsInventoryScopeSuggestionService.Suggestion("W1", "S1", 1, "NEAR_EXPIRY", 14,
				List.of(new WmsInventoryScopeSuggestionService.Row("M1", "物料一", "PCS", "B1", "S1",
						"一号库位", BigDecimal.valueOf(2), false)));
	}
}
