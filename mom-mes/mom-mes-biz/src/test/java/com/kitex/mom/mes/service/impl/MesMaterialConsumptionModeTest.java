package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.dto.MesWarehouseUseDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.process.impl.QuantityProcessOperation;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesReportMaterialConsumeService;
import com.kitex.mom.mes.service.MesProductionFeedBatchService;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs production mode selection and the real GOOD inventory branch; only persistence/remote boundaries are substituted. */
class MesMaterialConsumptionModeTest {

	@AfterEach
	void clearContext() {
		TenantContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void feedStartedBeforeGlobalSwitchNeverBackflushesAdditionalPhysicalStock() throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");
		f.feedCount = 1;
		f.useGood();

		assertEquals(1, f.feedUses.size());
		assertEquals(new java.math.BigDecimal("3"), f.feedUses.get(0).getReportNum());
		assertEquals(900L, f.feedUses.get(0).getReportId());
		assertTrue(f.stockUses.isEmpty());
	}

	@Test
	void backflushStartedBeforeGlobalSwitchStillConsumesOnlyItsOriginalInventoryMode() throws Exception {
		var f = new Fixture("0");
		f.anchor("BACKFLUSH");
		f.flowCount = 1;
		f.arrange.setCompleteNum(new java.math.BigDecimal("2"));
		f.useGood();

		assertTrue(f.feedUses.isEmpty());
		assertEquals(1, f.stockUses.size());
		assertEquals(new java.math.BigDecimal("6"), f.stockUses.get(0).getNum());
	}

	@Test
	void partialCompletionAndResumeKeepOriginalFeedModeEvenWhenDefaultIsUnavailable() throws Exception {
		var f = new Fixture(null);
		f.anchor("FEED");
		f.arrange.setStatus("PAUSE");
		f.arrange.setCompleteNum(new java.math.BigDecimal("4"));

		assertEquals("FEED", f.mode());
		assertEquals(0, f.defaultReads);
	}

	@Test
	void partialCompletionAndResumeKeepOriginalBackflushMode() throws Exception {
		var f = new Fixture("0");
		f.anchor("BACKFLUSH");
		f.arrange.setStatus("PAUSE");
		f.arrange.setCompleteNum(new java.math.BigDecimal("7"));

		assertEquals("BACKFLUSH", f.mode());
		assertEquals(0, f.defaultReads);
	}

	@Test
	void firstStartMayUseDefaultFeedWithExistingRealPrestartFeed() throws Exception {
		var f = new Fixture("0");
		f.feedCount = 1;

		assertEquals("FEED", f.mode());
	}

	@Test
	void firstStartMayUseDefaultBackflushWithoutAnyExistingFeedOrReport() throws Exception {
		assertEquals("BACKFLUSH", new Fixture("1").mode());
	}

	@Test
	void prestartPhysicalFeedConflictsWithNewBackflushDefault() throws Exception {
		var f = new Fixture("1");
		f.feedCount = 1;

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void startedLegacyTaskWithMissingAnchorCannotGuessFromTodaysDefault() throws Exception {
		var f = new Fixture("0");
		f.arrange.setStartTime(LocalDateTime.of(2026, 10, 23, 8, 0));
		f.arrange.setStatus("PRODUCT");

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void oldReportedTaskWithoutStartTimeStillCannotAcquireANewMode() throws Exception {
		var f = new Fixture("0");
		f.reportCount = 1;

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void referencedMissingAnchorNeverFallsBackToCurrentDefault() throws Exception {
		var f = new Fixture("0");
		f.arrange.setMaterialGateRecordId(77L);

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void previewBlockedAndForeignAnchorsAreNeverAcceptedAsSuccessfulStartEvidence() throws Exception {
		for (String defect : List.of("PREVIEW", "START_BLOCKED", "factory", "tenant", "arrange", "task", "process", "record", "result")) {
			var f = new Fixture("0");
			f.anchor("FEED");
			switch (defect) {
				case "PREVIEW", "START_BLOCKED" -> f.record.setStage(defect);
				case "factory" -> f.record.setFactoryCode("F2");
				case "tenant" -> f.record.setTenantId(2L);
				case "arrange" -> f.record.setArrangeId(22L);
				case "task" -> f.record.setTaskCode("T2");
				case "process" -> f.record.setProduceTaskCode("P2");
				case "record" -> f.record.setId(88L);
				default -> f.record.setResult("UNKNOWN");
			}

			assertThrows(CheckedException.class, f::mode, defect);
		}
	}

	@Test
	void malformedManifestVersionDigestOrModeCannotChooseAConsumptionBranch() throws Exception {
		for (String defect : List.of("digest", "json", "version", "mode", "manifestArrange", "result", "policy")) {
			var f = new Fixture("0");
			f.anchor("FEED");
			if ("digest".equals(defect)) {
				f.record.setFingerprint("0".repeat(64));
			} else if ("json".equals(defect)) {
				f.record.setManifestJson("{broken");
			} else {
				var json = JSONUtil.parseObj(f.record.getManifestJson());
				switch (defect) {
					case "version" -> json.set("version", "MATERIAL_START_V0");
					case "mode" -> json.set("consumptionMode", "MIXED");
					case "manifestArrange" -> json.set("arrangeId", "99");
					case "result" -> json.set("result", "UNKNOWN");
					default -> json.set("mode", "UNCONTROLLED");
				}
				f.setManifest(json.toString());
			}

			assertThrows(CheckedException.class, f::mode, defect);
		}
	}

	@Test
	void explicitObservedShortageStartKeepsItsModeButStrictShortageIsNotAStartAnchor() throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");
		f.record.setMode("OBSERVE_REMAINING");
		f.record.setResult("SHORTAGE");
		var json = JSONUtil.parseObj(f.record.getManifestJson()).set("mode", "OBSERVE_REMAINING").set("result", "SHORTAGE");
		f.setManifest(json.toString());
		assertEquals("FEED", f.mode());
		f.record.setMode("STRICT_REMAINING");
		f.setManifest(JSONUtil.parseObj(f.record.getManifestJson()).set("mode", "STRICT_REMAINING").toString());

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void anchoredFeedRejectsAnyExistingBackflushHistory() throws Exception {
		var f = new Fixture("0");
		f.anchor("FEED");
		f.flowCount = 1;

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void anchoredBackflushRejectsAnyPhysicalFeedEvenIfItsUsedQuantityCouldBeZero() throws Exception {
		var f = new Fixture("1");
		f.anchor("BACKFLUSH");
		f.feedCount = 1;

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void historicalMixedConsumptionCannotBeReinterpretedAsEitherMode() throws Exception {
		var f = new Fixture("0");
		f.feedCount = 1;
		f.flowCount = 1;

		assertThrows(CheckedException.class, f::mode);
	}

	@Test
	void unknownGlobalDefaultCannotOpenANewConsumptionMode() throws Exception {
		for (String value : Arrays.asList(null, "", "true", "2")) {
			var f = new Fixture(value);

			assertThrows(CheckedException.class, f::mode);
		}
	}

	@Test
	void actualBackflushHistoryQueryUsesStoredOutCodeAndAuthenticatedScope() throws Exception {
		var f = new Fixture("0");
		when(f.dependency(MesWarehouseInoutMapper.class).selectCount(any())).thenAnswer(call -> {
			LambdaQueryWrapper<?> query = call.getArgument(0);
			query.getSqlSegment();
			var values = query.getParamNameValuePairs().values();

			assertTrue(values.contains("out"), "real inventory operate_type is lowercase out");
			assertTrue(values.contains("back_flush_out"));
			assertTrue(values.contains("F1"));
			assertTrue(values.contains(1L));
			assertTrue(values.contains("A1"));
			return 0L;
		});

		assertEquals("FEED", f.mode());
	}

	@Test
	void realBoundResumeMaterialGateChecksOnlySelectedSourcesAndPersistsTheSameMode() throws Exception {
		assertResumeMaterialPool(false);
	}

	@Test
	void realResumeMaterialGateUsesConfirmedAdditionalSourceWhenTheOriginalIsExhausted() throws Exception {
		assertResumeMaterialPool(true);
	}

	private void assertResumeMaterialPool(boolean supplemented) throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");
		f.feedCount = 1;
		f.arrange.setStatus("PAUSE");
		f.arrange.setCompleteNum(new java.math.BigDecimal("4"));
		var task = f.task();
		var process = new MesProduceTaskEntity();
		process.setId(21L);
		process.setTenantId(1L);
		process.setFactoryCode("F1");
		process.setTaskCode("T1");
		process.setCode("P1");
		process.setRouteCode("R1");
		process.setProcessRouteSnapshot(task.getProcessRouteSnapshot());
		when(f.dependency(MesTaskMapper.class).selectOne(any())).thenReturn(task);
		when(f.dependency(MesProduceTaskMapper.class).selectOne(any())).thenReturn(process);
		f.boundPoolQuery(supplemented ? Set.of(51L, 52L) : Set.of(51L));
		var written = new ArrayList<MesMaterialStartCheckEntity>();
		when(f.dependency(MesMaterialStartCheckMapper.class).insert(any(MesMaterialStartCheckEntity.class))).thenAnswer(call -> {
			MesMaterialStartCheckEntity row = call.getArgument(0);
			row.setId(78L);
			written.add(row);
			f.record = row;
			return 1;
		});
		var gate = f.construct(MesMaterialStartGateService.class);
		ReflectionTestUtils.setField(gate, "mode", "STRICT_REMAINING");

		gate.assertStart(f.arrange);

		assertEquals(1, written.size());
		assertEquals("READY", written.get(0).getResult());
		assertEquals("FEED", JSONUtil.parseObj(written.get(0).getManifestJson()).getStr("consumptionMode"));
		assertEquals("6", JSONUtil.parseObj(written.get(0).getManifestJson()).getStr("remainingQuantity"));
		assertEquals(supplemented ? "FEED:52" : "FEED:51", JSONUtil.parseObj(written.get(0).getManifestJson()).getJSONArray("materials")
				.getJSONObject(0).getJSONArray("allocations").getJSONObject(0).getStr("id"));
		assertEquals(78L, f.arrange.getMaterialGateRecordId());
		assertEquals("FEED", f.mode());
		assertEquals(0, f.defaultReads);
	}

	@Test
	void actualManualFeedRejectsBackflushAnchorBeforeAnyPhysicalOutEvenWhenGlobalIsFeed() throws Exception {
		var f = new Fixture("0");
		f.anchor("BACKFLUSH");
		when(f.dependency(MesTaskMapper.class).selectList(any())).thenReturn(List.of(f.task()));
		when(f.dependency(MesTaskArrangeMapper.class).selectList(any())).thenReturn(List.of(f.arrange));
		var service = f.construct(MesWarehouseFeedServiceImpl.class);
		var feed = f.feed();
		feed.setId(null);

		var error = assertThrows(CheckedException.class, () -> service.saveFeed(feed));

		assertTrue(error.getMessage().contains("倒冲"));
		assertEquals(0, f.defaultReads);
		verify(f.dependency(MesWarehouseFeedMapper.class), never()).insert(any(MesWarehouseFeedEntity.class));
		verify(f.dependency(MesWarehouseLiveStockService.class), never()).outStockWithReceipt(any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void actualGoodFeedUseKeepsFrozenModeAndWritesExactOriginalGoodShareAfterGlobalSwitch() throws Exception {
		assertActualGoodConsumption(false);
	}

	@Test
	void actualGoodUsesConfirmedAdditionalSourceAfterTheOriginalPoolIsExhausted() throws Exception {
		assertActualGoodConsumption(true);
	}

	@SuppressWarnings("unchecked")
	private void assertActualGoodConsumption(boolean supplemented) throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");
		f.feedCount = 1;
		var task = f.task();
		f.arrange.setMatCode("FG1");
		var report = new MesTaskReportEntity();
		report.setId(900L);
		report.setTenantId(1L);
		report.setFactoryCode("F1");
		report.setRequestId("REQ-1");
		report.setStatus("EFFECTIVE");
		report.setTaskCode("T1");
		report.setArrangeCode("A1");
		report.setProduceTaskCode("P1");
		report.setMatCode("FG1");
		report.setReportUnit("PCS");
		report.setReportNum(new java.math.BigDecimal("3"));
		report.setEffectiveReportNum(report.getReportNum());
		report.setCorrectedReportNum(java.math.BigDecimal.ZERO);
		when(f.dependency(MesTaskReportMapper.class).selectOne(any())).thenReturn(report);
		when(f.dependency(MesExecutionTaskLock.class).arrange("A1")).thenReturn(f.arrange);
		when(f.dependency(MesTaskArrangeMapper.class).selectList(any())).thenReturn(List.of(f.arrange));
		when(f.dependency(MesTaskMapper.class).selectList(any())).thenReturn(List.of(task));
		when(f.dependency(MesProductionMaterialReversalMapper.class).ready()).thenReturn(true);
		f.boundPoolQuery(supplemented ? Set.of(51L, 52L) : Set.of(51L));
		when(f.dependency(MesWarehouseFeedMapper.class).update(any(), any())).thenReturn(1);
		var savedFeed = f.feed();
		savedFeed.setUsedNum(new java.math.BigDecimal("6"));
		when(f.dependency(MesWarehouseFeedMapper.class).selectById(any())).thenAnswer(call -> {
			var stored = f.feed();
			stored.setId(call.getArgument(0));
			stored.setUsedNum(savedFeed.getUsedNum());
			return stored;
		});
		var consumes = new ArrayList<MesReportMaterialConsumeEntity>();
		var consumptionService = f.dependency(MesReportMaterialConsumeService.class);
		when(consumptionService.saveBatch(any())).thenAnswer(call -> {
			Collection<MesReportMaterialConsumeEntity> rows = call.getArgument(0);
			consumes.addAll(rows);
			return true;
		});
		when(consumptionService.list(any(Wrapper.class))).thenAnswer(call -> consumes);
		var measure = new MdmMaterialMeasureDTO();
		measure.setMaterialId("101");
		measure.setTenantId("1");
		measure.setFactoryCode("F1");
		measure.setMaterialCode("M1");
		measure.setBasicUnit("EA");
		measure.setEnabled(true);
		measure.setUpdatedAt("2026-10-23T08:00:00.000000");
		when(f.dependency(RemoteMaterialService.class).productionMeasure("M1")).thenReturn(R.ok(measure));
		var service = f.construct(MesWarehouseFeedServiceImpl.class);
		ReflectionTestUtils.setField(service, "baseMapper", f.dependency(MesWarehouseFeedMapper.class));
		var command = new MesWarehouseUseDTO().setReportId(900L).setReportRequestId("REQ-1").setReportNum(report.getReportNum())
				.setArrangeCode("A1").setFactoryCode("F1").setSnapshot(task.getProcessRouteSnapshot())
				.setBomSnapshot(task.getBomSnapshot()).setRouteCode("R1").setProcessRouteCode(task.getProcessRouteCode());

		service.use(command);

		assertEquals(1, consumes.size());
		assertEquals(new java.math.BigDecimal("6"), consumes.get(0).getConsumeNum());
		assertEquals(900L, consumes.get(0).getReportId());
		assertEquals(supplemented ? 52L : 51L, consumes.get(0).getFeedId(), "未绑定且FIFO更早的50号来源不能进入实际GOOD耗料账");
		assertEquals(0, f.defaultReads);
		verify(f.dependency(MesWarehouseLiveStockService.class), never()).outStock(any());
	}

	@Test
	void ngCannotTreatUnknownBindingAsUnrestrictedFeedSelection() throws Exception {
		var f = new Fixture("0");
		f.anchor("FEED");
		f.arrange.setStatus("PRODUCT");
		doThrow(new CheckedException("原绑定证据损坏"))
				.when(f.dependency(MesStartMaterialBindingReader.class)).constrain(any(), any());

		var error = assertThrows(CheckedException.class, f::recordNg);

		assertTrue(error.getMessage().contains("原绑定证据损坏"));
		verify(f.dependency(MesWarehouseFeedMapper.class), never()).update(any(), any());
	}

	@Test
	void combinedKernelCannotTreatUnknownBindingAsUnrestrictedFeedSelection() throws Exception {
		var f = new Fixture("0");
		f.anchor("FEED");
		f.arrange.setStatus("PRODUCT");
		doThrow(new CheckedException("原绑定证据损坏"))
				.when(f.dependency(MesStartMaterialBindingReader.class)).constrain(any(), any());

		var error = assertThrows(CheckedException.class, f::recordCombined);

		assertTrue(error.getMessage().contains("原绑定证据损坏"));
		verify(f.dependency(MesWarehouseFeedMapper.class), never()).update(any(), any());
	}

	@Test
	void preparationReadKeepsActualFeedSourcesAvailableAfterTheDefaultChangesToBackflush() throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");

		var observation = f.prepare();

		assertEquals("A1", observation.arrangeCode());
		assertEquals("EA", observation.unit());
		assertEquals("61", observation.sources().get(0).stockId());
		assertEquals(0, f.defaultReads);
	}

	@Test
	void preparationReadRejectsBackflushAnchorEvenWhenTodaysDefaultAllowsFeed() throws Exception {
		var f = new Fixture("0");
		f.anchor("BACKFLUSH");

		var error = assertThrows(CheckedException.class, f::prepare);

		assertTrue(error.getMessage().contains("倒冲"));
		verify(f.dependency(MesWarehouseLiveStockService.class), never()).previewFeedSources(any());
	}

	@Test
	void preparationReadStillRejectsForeignMaterialMeasuresAfterResolvingAFrozenFeedMode() throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");
		var measure = f.measure();
		measure.setFactoryCode("F2");
		when(f.dependency(RemoteMaterialService.class).productionMeasure("M1")).thenReturn(R.ok(measure));

		var error = assertThrows(CheckedException.class, f::prepare);

		assertTrue(error.getMessage().contains("组织"));
		verify(f.dependency(MesWarehouseLiveStockService.class), never()).previewFeedSources(any());
	}

	@Test
	void ngFeedModeReachesActualMaterialShortageChecksAfterTheGlobalDefaultChanges() throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");

		var error = assertThrows(CheckedException.class, f::recordNg);

		assertTrue(error.getMessage().contains("原需求禁止混用"), error.getMessage());
		verify(f.dependency(MesProductionMaterialOperationMapper.class), never()).insert(any(MesProductionMaterialOperationEntity.class));
	}

	@Test
	void ngBackflushAnchorCannotEnterFeedConsumptionAfterTheGlobalDefaultChanges() throws Exception {
		var f = new Fixture("0");
		f.anchor("BACKFLUSH");

		var error = assertThrows(CheckedException.class, f::recordNg);

		assertTrue(error.getMessage().contains("倒冲"), error.getMessage());
		verify(f.dependency(MesWarehouseFeedMapper.class), never()).selectList(any());
	}

	@Test
	void combinedFeedModeReachesActualMaterialShortageChecksAfterTheGlobalDefaultChanges() throws Exception {
		var f = new Fixture("1");
		f.anchor("FEED");

		var error = assertThrows(CheckedException.class, f::recordCombined);

		assertTrue(error.getMessage().contains("原需求禁止混用"), error.getMessage());
		verify(f.dependency(MesProductionFeedBatchMapper.class), never()).insert(any(MesProductionFeedBatchEntity.class));
	}

	@Test
	void combinedBackflushAnchorCannotEnterFeedConsumptionAfterTheGlobalDefaultChanges() throws Exception {
		var f = new Fixture("0");
		f.anchor("BACKFLUSH");

		var error = assertThrows(CheckedException.class, f::recordCombined);

		assertTrue(error.getMessage().contains("倒冲"), error.getMessage());
		verify(f.dependency(MesWarehouseFeedMapper.class), never()).selectList(any());
	}

	public static void main(String[] args) throws Exception {
		int passed = 0;
		int failed = 0;
		for (var method : MesMaterialConsumptionModeTest.class.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Test.class)) {
				continue;
			}
			var test = new MesMaterialConsumptionModeTest();
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
			throw new AssertionError("Consumption mode regressions failed: " + failed);
		}
	}

	private static final class Fixture {
		private final Map<Class<?>, Object> dependencies = new HashMap<>();
		private final MesTaskArrangeEntity arrange = new MesTaskArrangeEntity();
		private MesMaterialStartCheckEntity record;
		private long feedCount, flowCount, reportCount;
		private int defaultReads;
		private final List<MesWarehouseUseDTO> feedUses = new ArrayList<>();
		private final List<MesWarehouseLiveStockInoutDTO> stockUses = new ArrayList<>();

		Fixture(String global) {
			TenantContextHolder.setTenantId(1L);
			var worker = mock(MomUser.class);
			when(worker.getId()).thenReturn(7L);
			when(worker.getUsername()).thenReturn("worker-1");
			SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(worker, "N/A", List.of()));
			for (var entity : List.of(MesMaterialStartCheckEntity.class, MesWarehouseFeedEntity.class, MesWarehouseInoutEntity.class,
					MesTaskReportEntity.class, MesTaskEntity.class, MesProduceTaskEntity.class, MesReportMaterialConsumeEntity.class)) {
				TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mode-test"), entity);
			}
			arrange.setId(11L);
			arrange.setTenantId(1L);
			arrange.setFactoryCode("F1");
			arrange.setTaskCode("T1");
			arrange.setProduceTaskCode("P1");
			arrange.setCode("A1");
			arrange.setRouteCode("R1");
			arrange.setStatus("RECEIVE");
			arrange.setNoticeNum(java.math.BigDecimal.TEN);
			arrange.setCompleteNum(java.math.BigDecimal.ZERO);
			when(dependency(MesActualFactoryAccessService.class).require(any())).thenReturn("F1");
			when(dependency(RemoteParamService.class).getByKey("BACK_FLUSH")).thenAnswer(call -> {
				defaultReads++;
				return R.<String>ok().setData(global);
			});
			when(dependency(MesMaterialStartCheckMapper.class).selectOne(any())).thenAnswer(call -> record);
			when(dependency(MesWarehouseFeedMapper.class).selectCount(any())).thenAnswer(call -> feedCount);
			when(dependency(MesWarehouseInoutMapper.class).selectCount(any())).thenAnswer(call -> flowCount);
			when(dependency(MesTaskReportMapper.class).selectCount(any())).thenAnswer(call -> reportCount);
			dependencies.put(MesPhysicalTransformCodec.class, new MesPhysicalTransformCodec());
			dependencies.put(MesProductionFeedAllocationPlanner.class, new MesProductionFeedAllocationPlanner(dependency(MesPhysicalTransformCodec.class)));
			var material = new MdmMaterialEntity();
			material.setMaterialCode("M1");
			material.setTenantId(1L);
			material.setFactoryCode("F1");
			material.setBasicUnit("EA");
			material.setStatus(true);
			when(dependency(RemoteMaterialService.class).getDetails("M1")).thenReturn(R.ok(material));
			when(dependency(RemoteMaterialService.class).productionMeasure("M1")).thenReturn(R.ok(measure()));
		}

		void anchor(String mode) throws Exception {
			arrange.setMaterialGateRecordId(77L);
			arrange.setStartTime(LocalDateTime.of(2026, 10, 23, 8, 0));
			arrange.setStatus("PRODUCT");
			record = new MesMaterialStartCheckEntity();
			record.setId(77L);
			record.setTenantId(1L);
			record.setFactoryCode("F1");
			record.setArrangeId(11L);
			record.setArrangeCode("A1");
			record.setTaskCode("T1");
			record.setProduceTaskCode("P1");
			record.setStage("START_CHECK");
			record.setMode("STRICT_REMAINING");
			record.setResult("READY");
			setManifest(JSONUtil.toJsonStr(Map.of("version", "MATERIAL_START_V1", "mode", "STRICT_REMAINING", "result", "READY", "arrangeId", "11", "consumptionMode", mode)));
		}

		void setManifest(String json) throws Exception {
			record.setManifestJson(json);
			record.setFingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))));
		}

		MesMaterialConsumptionModeResolver resolver() {
			return new MesMaterialConsumptionModeResolver(dependency(MesActualFactoryAccessService.class),
					dependency(RemoteParamService.class), dependency(MesMaterialStartCheckMapper.class),
					dependency(MesWarehouseFeedMapper.class), dependency(MesWarehouseInoutMapper.class),
					dependency(MesTaskReportMapper.class));
		}

		String mode() throws Exception {
			return resolver().resolve(arrange).name();
		}

		void useGood() throws Exception {
			var stocks = dependency(MesWarehouseLiveStockService.class);
			var feed = dependency(MesWarehouseFeedService.class);
			var stock = new MesWarehouseLiveStockEntity();
			stock.setNum(new java.math.BigDecimal("100"));
			when(stocks.previewOutCandidates(any())).thenReturn(List.of(stock));
			doAnswer(call -> { stockUses.add(call.getArgument(0)); return null; }).when(stocks).outStock(any());
			doAnswer(call -> { feedUses.add(call.getArgument(0)); return null; }).when(feed).use(any());
			var material = new MdmMaterialEntity();
			material.setMaterialCode("M1");
			material.setBasicUnit("EA");
			material.setStatus(true);
			when(dependency(RemoteMaterialService.class).getDetails("M1")).thenReturn(R.ok(material));
			var operation = new QuantityProcessOperation(stocks, dependency(RemoteParamService.class), dependency(RemoteMaterialService.class), feed);
			ReflectionTestUtils.setField(operation, "consumptionModes", resolver());
			var task = new MesTaskEntity();
			task.setCode("T1");
			task.setProcessRouteCode("ROUTE-1");
			task.setProcessRouteSnapshot("[{\"processCode\":\"R1\",\"mdmProcessProcedureMatList\":[{\"materialCode\":\"M1\",\"quantity\":2,\"unit\":\"EA\"}]}]");
			var vo = new MesTaskReportVO();
			vo.setTask(task);
			vo.setTaskArrange(arrange);
			vo.setReportId(900L);
			vo.setRequestId("REQ-1");
			vo.setPassNum(new java.math.BigDecimal("3"));
			operation.use(vo);
		}

		MesTaskEntity task() {
			var task = new MesTaskEntity();
			task.setId(31L);
			task.setTenantId(1L);
			task.setFactoryCode("F1");
			task.setCode("T1");
			task.setStatus("PRODUCT");
			task.setProcessRouteCode("ROUTE-1");
			task.setProcessRouteSnapshot("[{\"processCode\":\"R1\",\"mdmProcessProcedureMatList\":[{\"materialCode\":\"M1\",\"quantity\":2,\"unit\":\"EA\"}]}]");
			return task;
		}

		MesWarehouseFeedEntity feed() {
			var feed = new MesWarehouseFeedEntity();
			feed.setId(51L);
			feed.setTenantId(1L);
			feed.setFactoryCode("F1");
			feed.setTaskCode("T1");
			feed.setProduceTaskCode("P1");
			feed.setArrangeCode("A1");
			feed.setRequirementMatCode("M1");
			feed.setMatCode("M1");
			feed.setMatUnit("EA");
			feed.setFeedNum(new java.math.BigDecimal("20"));
			feed.setUsedNum(java.math.BigDecimal.ZERO);
			feed.setSubstituteRatio(java.math.BigDecimal.ONE);
			feed.setSubstituteFlag(false);
			feed.setDelFlag("0");
			feed.setIsLock(false);
			return feed;
		}

		void boundPoolQuery() {
			boundPoolQuery(Set.of(51L));
		}

		void boundPoolQuery(Set<Long> ids) {
			doAnswer(call -> {
				LambdaQueryWrapper<MesWarehouseFeedEntity> query = call.getArgument(1);
				query.in(MesWarehouseFeedEntity::getId, ids);
				return null;
			}).when(dependency(MesStartMaterialBindingReader.class)).constrain(any(), any());
			when(dependency(MesWarehouseFeedMapper.class).selectList(any())).thenAnswer(call -> {
				LambdaQueryWrapper<MesWarehouseFeedEntity> query = call.getArgument(0);
				query.getSqlSegment();
				if (ids.contains(52L) && ids.stream().allMatch(query.getParamNameValuePairs()::containsValue)) {
					var exhausted = feed();
					exhausted.setUsedNum(exhausted.getFeedNum());
					var added = feed();
					added.setId(52L);
					return List.of(exhausted, added);
				}
				if (!ids.contains(52L) && query.getParamNameValuePairs().containsValue(51L)) {
					return List.of(feed());
				}
				var unbound = feed();
				unbound.setId(50L);
				return List.of(unbound);
			});
		}

		<T> T construct(Class<T> type) throws Exception {
			var constructor = type.getDeclaredConstructors()[0];
			var arguments = new ArrayList<>();
			for (var parameter : constructor.getParameterTypes()) {
                arguments.add(parameter == MesMaterialConsumptionModeResolver.class ? resolver()
                        : parameter == MesMaterialSourceEligibility.class ? new MesMaterialSourceEligibility(
                                dependency(MesTaskMapper.class), dependency(MesTaskArrangeMapper.class)) : dependency(parameter));
			}
			return type.cast(constructor.newInstance(arguments.toArray()));
		}

		MdmMaterialMeasureDTO measure() {
			var measure = new MdmMaterialMeasureDTO();
			measure.setMaterialId("101");
			measure.setTenantId("1");
			measure.setFactoryCode("F1");
			measure.setMaterialCode("M1");
			measure.setBasicUnit("EA");
			measure.setEnabled(true);
			measure.setUpdatedAt("2026-10-23T08:00:00.000000");
			return measure;
		}

		MesWarehouseFeedPreparationDTO.Observation prepare() throws Exception {
			var task = task();
			task.setBomSnapshot("[{\"materialCode\":\"M1\"}]");
			when(dependency(MesTaskArrangeMapper.class).selectList(any())).thenReturn(List.of(arrange));
			when(dependency(MesTaskMapper.class).selectList(any())).thenReturn(List.of(task));
			var stock = new MesWarehouseLiveStockEntity();
			stock.setId(61L);
            stock.setTenantId(1L);
            stock.setFactoryCode("F1");
            stock.setMatCode("M1");
			stock.setNum(new java.math.BigDecimal("50"));
			stock.setMatUnit("EA");
			when(dependency(MesWarehouseLiveStockService.class).previewFeedSources(any())).thenReturn(List.of(stock));
			var query = new MesWarehouseFeedPreparationDTO.Query();
			query.setArrangeCode("A1");
			query.setMaterialCode("M1");
			return construct(MesWarehouseFeedPreparationReader.class).read(query,
					new MesWarehouseFeedCommandDTO.Context("1", "F1", "7", "MES_FEED_COMMAND_V1"));
		}

		void recordNg() throws Exception {
			arrange.setMatCode("M1");
			var row = new MesTaskReportUnqualifiedEntity();
			row.setId(901L);
			row.setTenantId(1L);
			row.setFactoryCode("F1");
			row.setCapturedActorId(7L);
			row.setTaskCode("T1");
			row.setProduceTaskCode("P1");
			row.setArrangeCode("A1");
			row.setMatCode("M1");
			row.setRequestId("REQ-NG-0001");
			row.setType("DEFECT-1");
			row.setWorkerCode("worker-1");
			row.setReportTime(LocalDateTime.of(2026, 10, 23, 8, 30));
			row.setUnqualifiedNum(new java.math.BigDecimal("3"));
			var codec = dependency(MesPhysicalTransformCodec.class);
			var sources = new MesProductionNgSourceCodec(codec, new MesNgIdentificationCodec(codec));
			sources.freeze(row, arrange, measure(), ReportModeEnum.QUANTITY);
			dependencies.put(MesProductionNgSourceCodec.class, sources);
			var mapper = dependency(MesProductionMaterialOperationMapper.class);
			when(mapper.ready()).thenReturn(true);
			when(dependency(MesProductionMaterialReversalMapper.class).ready()).thenReturn(true);
			var service = construct(MesProductionMaterialOperationServiceImpl.class);
			ReflectionTestUtils.setField(service, "baseMapper", mapper);
			service.recordFeed(row, arrange, task());
		}

		void recordCombined() throws Exception {
			arrange.setMatCode("M1");
			var mapper = dependency(MesProductionFeedBatchMapper.class);
			when(mapper.ready()).thenReturn(true);
			when(mapper.tryRequest(any())).thenReturn(true);
			when(dependency(MesExecutionTaskLock.class).arrange("A1")).thenReturn(arrange);
			when(dependency(MesTaskArrangeMapper.class).selectList(any())).thenReturn(List.of(arrange));
			when(dependency(MesTaskMapper.class).selectList(any())).thenReturn(List.of(task()));
			var birth = new MesProductionFeedBatchMapper.SourceBirth();
			birth.setId(901L);
			birth.setSourceId(900L);
			birth.setTenantId(1L);
			birth.setFactoryCode("F1");
			birth.setOutputKind("GOOD");
			birth.setSameTransaction(true);
			birth.setQuantity("3");
			birth.setEffectiveQuantity("3");
			birth.setCorrectedQuantity("0");
			birth.setSourceSnapshot("{\"id\":\"900\",\"tenant_id\":\"1\",\"factory_code\":\"F1\",\"task_code\":\"T1\",\"produce_task_code\":\"P1\",\"arrange_code\":\"A1\",\"mat_code\":\"M1\",\"del_flag\":\"0\",\"report_unit\":\"EA\",\"status\":\"EFFECTIVE\",\"execution_kind\":\"NORMAL\"}");
			when(mapper.actualBirth(1L, "F1", "GOOD", 900L)).thenReturn(birth);
			dependencies.put(MesProductionFeedEvidenceCodec.class, new MesProductionFeedEvidenceCodec(dependency(MesPhysicalTransformCodec.class)));
			var service = construct(MesProductionFeedBatchServiceImpl.class);
			ReflectionTestUtils.setField(service, "baseMapper", mapper);
			service.recordFeed("REQ-COMBINED-001", "A1", List.of(new MesProductionFeedBatchService.SourceRef("GOOD-1", MesProductionFeedAllocationPlanner.OutputKind.GOOD, 900L)));
		}

		private <T> T dependency(Class<T> type) {
			return type.cast(dependencies.computeIfAbsent(type, ignored -> mock(type)));
		}
	}
}
