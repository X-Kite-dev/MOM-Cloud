package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.agent.service.MesStartPreparationQueryService;
import com.kitex.mom.mes.dto.MesDeviceGateDecision;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import com.kitex.mom.mes.mapper.MesToolingExecutionMapper;
import com.kitex.mom.mes.service.MesDeviceGateService;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real start/resume and public preparation observations; only external facts and persistence are substituted.
 */
class MesStartContractGuardTest {

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void startRejectsMissingRequiredDeviceBeforeCreatingProductionFacts() throws Exception {
		var fixture = new Fixture(true, false);

		assertBlockedStart(fixture, "设备");
	}

	@Test
	void resumeRechecksRequiredDevice() throws Exception {
		var fixture = new Fixture(true, false);
		fixture.arrange.setStatus("PAUSE");

		assertBlockedStart(fixture, "设备");
	}

	@Test
	void startRejectsMissingRequiredToolingBeforeCreatingProductionFacts() throws Exception {
		var fixture = new Fixture(false, true);

		assertBlockedStart(fixture, "工装");
	}

	@Test
	void resumeRechecksRequiredTooling() throws Exception {
		var fixture = new Fixture(false, true);
		fixture.arrange.setStatus("PAUSE");

		assertBlockedStart(fixture, "工装");
	}

	@Test
	void preparationMarksMissingRequiredDeviceBlocked() throws Exception {
		var fixture = new Fixture(true, false);

		assertEquals("BLOCKED", fixture.gate("DEVICE").get("status"));
	}

	@Test
	void preparationMarksMissingRequiredToolingBlocked() throws Exception {
		var fixture = new Fixture(false, true);

		assertEquals("BLOCKED", fixture.gate("TOOLING").get("status"));
	}

	@Test
	void optionalResourcesRetainExistingStartAndObservationBehavior() throws Exception {
		var fixture = new Fixture(false, false);

		assertEquals("NOT_APPLICABLE", fixture.gate("DEVICE").get("status"));
		assertEquals("NOT_APPLICABLE", fixture.gate("TOOLING").get("status"));
		fixture.start.start(11L);

		assertEquals("PRODUCT", fixture.arrange.getStatus());
		assertEquals(1, fixture.segments.size());
		assertNotNull(fixture.arrange.getStartTime());
	}

	@Test
	void requiredToolingStillNeedsActualScanAfterPlanBinding() throws Exception {
		var fixture = new Fixture(false, true);
		fixture.arrange.setPlannedToolingCodes("TOOL-1");

		assertBlockedStart(fixture, "扫码");
		assertEquals("BLOCKED", fixture.gate("TOOLING").get("status"));
	}

	@Test
	void requiredDeviceStillNeedsRunningGateAfterPlanBinding() throws Exception {
		var fixture = new Fixture(true, false);
		fixture.arrange.setPlannedDeviceCode("DEVICE-1");
		fixture.deviceDecision.setAllowed(false).setMessage("设备已停用");

		assertBlockedStart(fixture, "设备");
		assertEquals("BLOCKED", fixture.gate("DEVICE").get("status"));
	}

	@Test
	void malformedFrozenPolicyBlocksStartAndRemainsUnknownInObservation() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionPolicySnapshot("{broken");

		assertBlockedStart(fixture, "契约");
		assertEquals("UNKNOWN", fixture.gate("DEVICE").get("status"));
		assertEquals("UNKNOWN", fixture.gate("TOOLING").get("status"));
	}

	@Test
	void booleanTextCannotSilentlyDisableRequiredResources() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionPolicySnapshot(fixture.policy(false, false)
				.replace("\"deviceRequired\":false", "\"deviceRequired\":\"false\""));

		assertBlockedStart(fixture, "契约");
		assertEquals("UNKNOWN", fixture.gate("DEVICE").get("status"));
	}

	@Test
	void missingFrozenPolicyCannotFallBackToLegacyWhenTemplateIdentityExists() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionPolicySnapshot(null);

		assertBlockedStart(fixture, "契约");
		assertEquals("UNKNOWN", fixture.gate("DEVICE").get("status"));
	}

	@Test
	void frozenTemplateVersionMustMatchOriginalArrangement() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionTemplateVersion(2);

		assertBlockedStart(fixture, "契约");
		assertEquals("UNKNOWN", fixture.gate("TOOLING").get("status"));
	}

	@Test
	void genuineLegacyArrangementRetainsCompatibilityWithoutReadingTodaysTemplate() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionTemplateId(null).setExecutionTemplateCode(null)
				.setExecutionTemplateVersion(null).setExecutionPolicySnapshot(null);

		fixture.start.start(11L);

		assertEquals("PRODUCT", fixture.arrange.getStatus());
		assertEquals(1, fixture.segments.size());
	}

	@Test
	void dispatchedLegacyPolicyKeepsItsOriginalVersionZeroCompatibility() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionTemplateId(null).setExecutionTemplateCode("LEGACY_ROUTE")
				.setExecutionTemplateVersion(0).setExecutionPolicySnapshot("{\"templateFrozen\":false,"
						+ "\"templateCode\":\"LEGACY_ROUTE\",\"templateVersion\":0,"
						+ "\"reportMode\":\"QUANTITY\",\"deviceRequired\":false,\"toolingRequired\":false}");

		fixture.start.start(11L);

		assertEquals("PRODUCT", fixture.arrange.getStatus());
		assertEquals(1, fixture.segments.size());
	}

	@Test
	void requiredBoundResourcesCanStartWhenExistingPhysicalGatesPass() throws Exception {
		var fixture = new Fixture(true, true);
		fixture.arrange.setPlannedDeviceCode("DEVICE-1").setPlannedToolingCodes("TOOL-1");
		var tooling = new MesToolingExecutionEntity();
		tooling.setToolingCode("TOOL-1");
		tooling.setStatus("CHECKED_IN");
		when(fixture.dependency(MesToolingExecutionMapper.class)
				.selectList(any())).thenReturn(List.of(tooling));

		assertEquals("READY", fixture.gate("DEVICE").get("status"));
		assertEquals("READY", fixture.gate("TOOLING").get("status"));
		fixture.start.start(11L);

		assertEquals("PRODUCT", fixture.arrange.getStatus());
		assertEquals(1, fixture.segments.size());
	}

	@Test
	void legacyPolicyCannotHideFrozenTemplateIdentityInItsSnapshot() throws Exception {
		var fixture = new Fixture(false, false);
		fixture.arrange.setExecutionTemplateId(null).setExecutionTemplateCode("LEGACY_ROUTE")
				.setExecutionTemplateVersion(0).setExecutionPolicySnapshot("{\"templateFrozen\":false,"
						+ "\"templateId\":101,\"templateCode\":\"LEGACY_ROUTE\",\"templateVersion\":0,"
						+ "\"reportMode\":\"QUANTITY\",\"deviceRequired\":false,\"toolingRequired\":false}");

		assertBlockedStart(fixture, "契约");
		assertEquals("UNKNOWN", fixture.gate("DEVICE").get("status"));
	}

	private void assertBlockedStart(Fixture fixture, String reason) {
		String originalStatus = fixture.arrange.getStatus();

		var failure = assertThrows(CheckedException.class, () -> fixture.start.start(11L));

		assertTrue(failure.getMessage().contains(reason), failure.getMessage());
		assertEquals(originalStatus, fixture.arrange.getStatus());
		assertEquals(0, fixture.segments.size());
	}

	/** Existing Surefire 2.12.4 skips Jupiter; also executable through the offline javac runner. */
	public static void main(String[] args) throws Exception {
		int passed = 0;
		int failed = 0;
		for (var method : MesStartContractGuardTest.class.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Test.class)) {
				continue;
			}
			var test = new MesStartContractGuardTest();
			try {
				method.invoke(test);
				System.out.println("PASS " + method.getName());
				passed++;
			} catch (InvocationTargetException failure) {
				System.out.println("FAIL " + method.getName() + ": " + failure.getCause());
				failed++;
			} finally {
				test.clearContext();
			}
		}
		System.out.println("Tests: " + (passed + failed) + ", passed: " + passed + ", failed: " + failed);
		if (failed != 0) {
			throw new AssertionError("Start contract regressions failed: " + failed);
		}
	}

	static final class Fixture {
		private final Map<Class<?>, Object> dependencies = new HashMap<>();
		final MesTaskArrangeEntity arrange = new MesTaskArrangeEntity();
		private final List<MesTaskExecutionSegmentEntity> segments = new ArrayList<>();
		private final MesDeviceGateDecision deviceDecision = new MesDeviceGateDecision()
				.setAllowed(true).setMessage("当前工序无需设备资源，设备运行门放行")
				.setUnavailableWindows(List.of());
		final MesTaskArrangeServiceImpl start;
		private final MesStartPreparationQueryService preparation;

		@SuppressWarnings("unchecked")
		Fixture(boolean deviceRequired, boolean toolingRequired) throws Exception {
			var metadata = new MapperBuilderAssistant(new MybatisConfiguration(), "start-contract-test");
			TableInfoHelper.initTableInfo(metadata, MesToolingExecutionEntity.class);
			TableInfoHelper.initTableInfo(metadata, MesTaskArrangeEntity.class);
			TableInfoHelper.initTableInfo(metadata, com.kitex.mom.mes.entity.MesTaskEntity.class);
			TenantContextHolder.setTenantId(1L);
			FactoryContextHolder.setFactoryCode("F1");
			var worker = mock(MomUser.class);
			when(worker.getId()).thenReturn(7L);
			when(worker.getTenantId()).thenReturn(1L);
			when(worker.getUsername()).thenReturn("worker-1");
			SecurityContextHolder.getContext().setAuthentication(
					new UsernamePasswordAuthenticationToken(worker, "N/A", List.of()));

			arrange.setId(11L);
			arrange.setTenantId(1L);
			arrange.setFactoryCode("F1").setCode("A1").setTaskCode("T1").setProduceTaskCode("P1")
					.setRouteCode("R1").setWorkerCode("worker-1").setStatus("RECEIVE")
					.setActualWorksiteCode("W1").setReportMode("QUANTITY")
					.setExecutionTemplateId(101L).setExecutionTemplateCode("EXEC-1")
					.setExecutionTemplateVersion(1).setExecutionPolicySnapshot(policy(deviceRequired, toolingRequired));
			var process = new MesProduceTaskEntity().setFactoryCode("F1").setCode("P1")
					.setTaskCode("T1").setRouteCode("R1").setProcessRouteCode("ROUTE-1");
			process.setId(21L);
			process.setTenantId(1L);

			process.setStatus("RECEIVE");
			var mainTask = new com.kitex.mom.mes.entity.MesTaskEntity()
					.setCode("T1").setFactoryCode("F1").setStatus("PRODUCT");
			mainTask.setId(1L);
			mainTask.setTenantId(1L);
			when(dependency(com.kitex.mom.mes.service.MesTaskService.class).getOne(any(Wrapper.class)))
					.thenReturn(mainTask);

			var mapper = dependency(MesTaskArrangeMapper.class);
			when(mapper.selectOne(any())).thenReturn(arrange);
			when(mapper.selectOne(any(), anyBoolean())).thenReturn(arrange);
			when(mapper.selectCount(any())).thenReturn(0L);
			when(mapper.updateById(any(MesTaskArrangeEntity.class))).thenReturn(1);
			when(dependency(MesExecutionTaskLock.class).arrange(11L)).thenReturn(arrange);
			when(dependency(MesProduceTaskService.class).list(any(Wrapper.class))).thenReturn(List.of(process));
			when(dependency(MesProduceTaskService.class).updateById(any())).thenReturn(true);
			var segmentMapper = dependency(MesTaskExecutionSegmentMapper.class);
			when(segmentMapper.insert(any(MesTaskExecutionSegmentEntity.class))).thenAnswer(call -> {
				segments.add(call.getArgument(0));
				return 1;
			});
			var device = dependency(MesDeviceGateService.class);
			when(device.check(any(), any(), any(), any())).thenReturn(deviceDecision);
			when(device.previewStart(any())).thenReturn(deviceDecision);
			when(dependency(RemoteMdmFactoryAccessService.class).check(any())).thenReturn(R.ok(true));

			var codec = new MesPhysicalTransformCodec();
			dependencies.put(MesPhysicalTransformCodec.class, codec);
			var policy = new MesPhysicalTransformPolicyValidator(codec, dependency(MesActualFactoryAccessService.class));
			var engineering = new MesPhysicalTransformEngineeringFreezeService(null, codec,
					dependency(MesActualFactoryAccessService.class));
			dependencies.put(MesExecutionTemplateService.class, new MesExecutionTemplateServiceImpl(
					dependency(MesActualFactoryAccessService.class), mapper, null, null, null, policy, engineering));
			start = construct(MesTaskArrangeServiceImpl.class);
			ReflectionTestUtils.setField(start, "baseMapper", mapper);
			preparation = construct(MesStartPreparationQueryService.class);
		}

		private String policy(boolean device, boolean tooling) {
			return "{\"templateId\":101,\"templateCode\":\"EXEC-1\",\"templateVersion\":1,"
					+ "\"templateFrozen\":true,\"reportMode\":\"QUANTITY\",\"deviceRequired\":" + device
					+ ",\"toolingRequired\":" + tooling + "}";
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> gate(String code) {
			var checks = (List<Map<String, Object>>) preparation.observe(11L).get("checks");
			return checks.stream().filter(check -> code.equals(check.get("code"))).findFirst().orElseThrow();
		}

		<T> T dependency(Class<T> type) {
			return type.cast(dependencies.computeIfAbsent(type, ignored -> mock(type)));
		}

		private <T> T construct(Class<T> type) throws Exception {
			Constructor<?> constructor = type.getDeclaredConstructors()[0];
			var arguments = new ArrayList<>();
			for (Class<?> parameter : constructor.getParameterTypes()) {
				// Instantiate the shared production guard if present, including during the pre-fix RED run.
				if (parameter.getSimpleName().equals("MesStartContractGuard")) {
					arguments.add(construct(parameter));
				} else {
					arguments.add(dependency(parameter));
				}
			}
			return type.cast(constructor.newInstance(arguments.toArray()));
		}
	}
}
