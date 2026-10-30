package com.kitex.mom.mes.service.impl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * Real generated MyBatis SQL and stock service in Spring/H2 transactions; no target PostgreSQL claim.
 */
class MesMaterialSourceStockTest {
	private JdbcTemplate db;
	private TransactionTemplate tx;
	private SqlSessionTemplate sql;
	private MesWarehouseLiveStockServiceImpl stocks;
	private RemoteMaterialService materials;
	private MesActualFactoryAccessService access;
	private MesMaterialSourceEligibility eligibility;
	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var source = new DriverManagerDataSource("jdbc:h2:mem:material-sources-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS mom_mes\\;SET SCHEMA mom_mes",
				"sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		tx = new TransactionTemplate(new DataSourceTransactionManager(source));
		var config = new MybatisConfiguration();
		com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
		config.setMapUnderscoreToCamelCase(true);
		config.setEnvironment(new Environment("material-sources", new SpringManagedTransactionFactory(), source));
		for (var type : List.of(MesWarehouseLiveStockMapper.class, MesWarehouseInoutMapper.class, MesTaskMapper.class,
				MesTaskArrangeMapper.class, MesWarehouseFeedMapper.class)) {
			config.addMapper(type);
		}
		sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
		for (var type : List.of(MesWarehouseLiveStockEntity.class, MesWarehouseInoutEntity.class, MesTaskEntity.class,
				MesTaskArrangeEntity.class, MesWarehouseFeedEntity.class)) {
			createTable(type);
		}
		var material = new MdmMaterialEntity();
		material.setId(1L);
		material.setTenantId(1L);
		material.setFactoryCode("F1");
		material.setMaterialCode("M1");
		material.setBasicUnit("EA");
		material.setStatus(true);
		material.setFifo(false);
		materials = mock(RemoteMaterialService.class);
		when(materials.getDetails("M1")).thenReturn(R.ok(material));
		access = mock(MesActualFactoryAccessService.class);
		when(access.require(anyString())).thenReturn("F1");
		stocks = mock(MesWarehouseLiveStockServiceImpl.class, CALLS_REAL_METHODS);
		ReflectionTestUtils.setField(stocks, "baseMapper", sql.getMapper(MesWarehouseLiveStockMapper.class));
		ReflectionTestUtils.setField(stocks, "remoteMaterialService", materials);
		ReflectionTestUtils.setField(stocks, "mesWarehouseInoutMapper", sql.getMapper(MesWarehouseInoutMapper.class));
		ReflectionTestUtils.setField(stocks, "factoryAccess", access);
		ReflectionTestUtils.setField(stocks, "proofCodec", new MesPhysicalTransformCodec());
		eligibility = new MesMaterialSourceEligibility(sql.getMapper(MesTaskMapper.class), sql.getMapper(MesTaskArrangeMapper.class));
		ReflectionTestUtils.setField(stocks, "sourceEligibility", eligibility);
		db.update("INSERT INTO mes_task(id,tenant_id,factory_code,code,status,bom_snapshot,process_route_snapshot,material_reservation_snapshot) VALUES(10,1,'F1','T1','PRODUCT',?,?,?)",
				"[{\"materialCode\":\"M1\",\"quantity\":2,\"unit\":\"EA\"}]", "[{\"processCode\":\"R1\",\"mdmProcessProcedureMatList\":[{\"materialCode\":\"M1\",\"quantity\":2,\"unit\":\"EA\"}]}]",
				"[{\"reservationNo\":\"RES-A\",\"warehouseCode\":\"W1\",\"requirementMatCode\":\"M1\",\"actualMatCode\":\"M1\",\"substituteRatio\":1,\"reservedActualNum\":10}]");
		db.update("INSERT INTO mes_task_arrange(id,tenant_id,factory_code,code,task_code,produce_task_code,route_code,status) VALUES(20,1,'F1','A1','T1','P1','R1','PRODUCT')");
		stock(1, "RES-B", "M1", "W1", "5");
		stock(2, "RES-A", "M1", "W1", "5");
		stock(3, null, null, "W1", "5");
	}
	@AfterEach
	void cleanup() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
		org.springframework.security.core.context.SecurityContextHolder.clearContext();
	}
	@Test
	void previewExcludesAnotherTasksReservedStockButKeepsPublicStock() {
		var rows = stocks.previewOutCandidates(request("1"));

		assertEquals(List.of(2L, 3L), rows.stream().map(MesWarehouseLiveStockEntity::getId).toList());
	}
	@Test
	void actualDeductionUsesSameEligibleSourcesAndPreservesOtherReservation() {

		tx.executeWithoutResult(status -> stocks.outStock(request("6")));

		assertEquals(0, quantity(1).compareTo(new BigDecimal("5")));
		assertEquals(0, quantity(2).signum());
		assertEquals(0, quantity(3).compareTo(new BigDecimal("4")));
		assertEquals(List.of(2L, 3L), db.queryForList("SELECT source_stock_id FROM mes_warehouse_inout ORDER BY source_stock_id", Long.class));
	}
	@Test
	void shortageDoesNotConsumeAnotherTasksReservationAndRollsBack() {

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> stocks.outStock(request("11"))));
		assertEquals(0, quantity(1).compareTo(new BigDecimal("5")));
		assertEquals(0, quantity(2).compareTo(new BigDecimal("5")));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mes_warehouse_inout", Integer.class));
	}
	@Test
	void frozenWarehouseAndRequirementBothRestrictEligibleReservation() {
		db.update("UPDATE mes_warehouse_live_stock SET warehouse_code='W2' WHERE id=2");

		assertEquals(List.of(3L), ids(stocks.previewOutCandidates(request("1"))));

		db.update("UPDATE mes_warehouse_live_stock SET warehouse_code='W1',requirement_mat_code='M2' WHERE id=2");

		assertEquals(List.of(3L), ids(stocks.previewOutCandidates(request("1"))));
	}
	@Test
	void missingOldFrozenReservationAllowsPublicButDoesNotGuessReservedOwnership() {
		db.update("UPDATE mes_task SET material_reservation_snapshot=NULL WHERE id=10");

		assertEquals(List.of(3L), ids(stocks.previewOutCandidates(request("1"))));
	}
	@Test
	void malformedFrozenReservationIsUnknownEvenWhenPublicStockExists() {
		for (String broken : List.of("{}", "[null]", "[{}]", "[", "[] []", " ", "[{\"reservationNo\":\"RES-A\",\"reservationNo\":\"RES-B\"}]")) {
			db.update("UPDATE mes_task SET material_reservation_snapshot=? WHERE id=10", broken);

			assertThrows(CheckedException.class, () -> stocks.previewOutCandidates(request("1")), broken);
			assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> stocks.outStock(request("1"))), broken);
		}

		assertEquals(0, quantity(1).compareTo(new BigDecimal("5")));
	}
	@Test
	void previewDoesNotAuthorizeLaterStockWhoseReservationChanged() {

		assertEquals(List.of(2L, 3L), ids(stocks.previewOutCandidates(request("1"))));

		db.update("UPDATE mes_warehouse_live_stock SET reservation_no='RES-B' WHERE id=2");

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> stocks.outStock(request("6"))));
		assertEquals(0, quantity(2).compareTo(new BigDecimal("5")));
		assertEquals(0, quantity(3).compareTo(new BigDecimal("5")));
	}
	@Test
	void failedNecessaryOutEvidenceRollsBackActualStockDeduction() {
		db.execute("ALTER TABLE mes_warehouse_inout ADD CONSTRAINT reject_out CHECK(operate_type<>'out')");

		assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> stocks.outStock(request("1"))));
		assertEquals(0, quantity(2).compareTo(new BigDecimal("5")));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mes_warehouse_inout", Integer.class));
	}
	@Test
	void requestCannotSpoofActualDispatchProcessOrUseAnotherFactory() {
		var wrongProcess = request("1");
		wrongProcess.setProduceTaskCode("P2");

		assertThrows(CheckedException.class, () -> stocks.previewOutCandidates(wrongProcess));

		db.update("UPDATE mes_task_arrange SET factory_code='F2' WHERE id=20");

		assertThrows(CheckedException.class, () -> stocks.previewOutCandidates(request("1")));
	}
	@Test
	void feedingActualReceiptCannotBorrowAnotherProcessOrFrozenWarehouse() {
		db.update("UPDATE mes_warehouse_live_stock SET produce_task_code='P2' WHERE id=2");
		var r = request("1");
		r.setBizType("feeding");
		r.setProduceTaskCode(null);

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> stocks.outStockWithReceipt(r,
				new com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO.Lineage("RES-A", "M1"))));
		db.update("UPDATE mes_warehouse_live_stock SET produce_task_code='P1',warehouse_code='W2' WHERE id=2");

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> stocks.outStockWithReceipt(r,
				new com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO.Lineage("RES-A", "M1"))));

		assertEquals(0, quantity(2).compareTo(new BigDecimal("5")));
	}
	@Test
	void nonProductionReverseOutKeepsItsOriginalReservedSourceCompatible() {
		var r = request("1");
		r.setBizType("report_reverse_product_out");
		r.setReservationNo("RES-B");
		r.setRequirementMatCode(null);
		r.setBillNo("ORIGINAL-REPORT");

		tx.executeWithoutResult(status -> stocks.outStock(r));

		assertEquals(0, quantity(1).compareTo(new BigDecimal("4")));
		assertEquals(0, quantity(2).compareTo(new BigDecimal("5")));
	}
	@Test
	void sharedSubstituteDoesNotSwitchItsDemandOrRatioUsingSameActualMaterial() {
		db.update("UPDATE mes_task SET bom_snapshot=?,process_route_snapshot=?,material_reservation_snapshot=? WHERE id=10",
				"[{\"materialCode\":\"M2\",\"quantity\":2,\"replaceCodes\":\"M1\"}]", "[{\"processCode\":\"R1\",\"mdmProcessProcedureMatList\":[{\"materialCode\":\"M2\",\"quantity\":2,\"unit\":\"EA\"}]}]",
				"[{\"reservationNo\":\"RES-A\",\"warehouseCode\":\"W1\",\"requirementMatCode\":\"M2\",\"actualMatCode\":\"M1\",\"substituteRatio\":1,\"reservedActualNum\":10}]");
		var r = request("1");
		r.setRequirementMatCode("M2");
		r.setSubstituteFlag(true);

		assertEquals(List.of(3L), ids(stocks.previewOutCandidates(r)));

		db.update("UPDATE mes_warehouse_live_stock SET requirement_mat_code='M2' WHERE id=2");

		assertEquals(List.of(2L, 3L), ids(stocks.previewOutCandidates(r)));

		r.setSubstituteRatio(new BigDecimal("2"));

		assertThrows(CheckedException.class, () -> stocks.previewOutCandidates(r));
	}
	@Test
	void pureSourceCheckMatchesQueryEligibilityAndRejectsOrganizationMismatch() {
		var helper = new MesMaterialSourceEligibility(sql.getMapper(MesTaskMapper.class), sql.getMapper(MesTaskArrangeMapper.class));
		var task = sql.getMapper(MesTaskMapper.class).selectById(10L);
		var stockMapper = sql.getMapper(MesWarehouseLiveStockMapper.class);

		assertFalse(helper.eligible(task, stockMapper.selectById(1L), "M1", BigDecimal.ONE));
		assertTrue(helper.eligible(task, stockMapper.selectById(2L), "M1", BigDecimal.ONE));
		assertTrue(helper.eligible(task, stockMapper.selectById(3L), "M1", BigDecimal.ONE));
		assertFalse(helper.eligible(task, stockMapper.selectById(2L), "M1", new BigDecimal("2")));

		var otherFactory = stockMapper.selectById(2L);
		otherFactory.setFactoryCode("F2");

		assertThrows(CheckedException.class, () -> helper.assertEligible(task, otherFactory, "M1", BigDecimal.ONE));
	}
	@Test
	void actualPreparationUsesRealStockAndDoesNotOfferAnotherTasksOrProcessesSource() {
		var measure = new com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO();
		measure.setMaterialId("1");
		measure.setTenantId("1");
		measure.setFactoryCode("F1");
		measure.setMaterialCode("M1");
		measure.setEnabled(true);
		measure.setBasicUnit("EA");
		when(materials.productionMeasure("M1")).thenReturn(R.ok(measure));
		var modes = mock(MesMaterialConsumptionModeResolver.class);
		when(modes.resolve(any())).thenReturn(MesMaterialConsumptionModeResolver.Mode.FEED);
		var reader = new MesWarehouseFeedPreparationReader(sql.getMapper(MesTaskArrangeMapper.class),
				sql.getMapper(MesTaskMapper.class), stocks, materials, modes, new MesPhysicalTransformCodec(),
				mock(MesTerminalExecutionGuard.class), mock(MesReworkMaterialPlanService.class), eligibility);
		var query = new com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO.Query();
		query.setArrangeCode("A1");
		query.setMaterialCode("M1");
		var context = new com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO.Context("1", "F1", "7", "MES_FEED_COMMAND_V1");

		assertEquals(List.of("2", "3"), reader.read(query, context).sources().stream().map(s -> s.stockId()).toList());

		db.update("UPDATE mes_warehouse_live_stock SET produce_task_code='P2' WHERE id=2");

		assertEquals(List.of("3"), reader.read(query, context).sources().stream().map(s -> s.stockId()).toList());

		db.update("UPDATE mes_task SET material_reservation_snapshot='{}' WHERE id=10");

		assertThrows(CheckedException.class, () -> reader.read(query, context));
	}
	@Test
	void actualSaveFeedUsesOnlyItsFrozenReservationAndSavesItsPreciseOutSource() {
		db.update("UPDATE mes_warehouse_live_stock SET del_flag='1' WHERE id=3");
		var feed = feed("1");
		var service = actualFeedService();

		tx.executeWithoutResult(status -> assertTrue(service.saveFeed(feed)));

		assertEquals("RES-A", feed.getReservationNo());
		assertEquals(0, quantity(1).compareTo(new BigDecimal("5")));
		assertEquals(0, quantity(2).compareTo(new BigDecimal("4")));
		assertEquals(2L, db.queryForObject("SELECT source_stock_id FROM mes_warehouse_inout", Long.class));
		assertEquals("RES-A", db.queryForObject("SELECT reservation_no FROM mes_warehouse_feed", String.class));
	}
	@Test
	void actualSaveFeedRejectsForeignReservationBeforeSavingOrDeducting() {
		var service = actualFeedService();
		var feed = feed("1");
		feed.setReservationNo("RES-B");

		assertThrows(CheckedException.class, () -> tx.executeWithoutResult(status -> service.saveFeed(feed)));
		assertEquals(0, quantity(1).compareTo(new BigDecimal("5")));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mes_warehouse_feed", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mes_warehouse_inout", Integer.class));
	}

	@Test
	void actualSaveFeedDoesNotTreatAnotherProcessesReservationAsAnAmbiguousPublicSource() {
		db.update("UPDATE mes_warehouse_live_stock SET produce_task_code='P2' WHERE id=2");
		var service = actualFeedService();
		var feed = feed("1");
		var candidateRequest = request("1");
		candidateRequest.setBizType("feeding");
		assertEquals(List.of(3L), ids(stocks.previewOutCandidates(candidateRequest)));

		tx.executeWithoutResult(status -> assertTrue(service.saveFeed(feed)));

		assertNull(feed.getReservationNo());
		assertEquals(0, quantity(2).compareTo(new BigDecimal("5")));
		assertEquals(0, quantity(3).compareTo(new BigDecimal("4")));
		assertEquals(3L, db.queryForObject("SELECT source_stock_id FROM mes_warehouse_inout", Long.class));
	}
	private MesWarehouseFeedEntity feed(String quantity) {
		var feed = new MesWarehouseFeedEntity();
		feed.setTaskCode("T1");
		feed.setArrangeCode("A1");
		feed.setMatCode("M1");
		feed.setRequirementMatCode("M1");
		feed.setFeedNum(new BigDecimal(quantity));
		return feed;
	}
	private MesWarehouseFeedServiceImpl actualFeedService() {
		var actor = mock(com.kitex.common.security.service.MomUser.class);
		when(actor.getId()).thenReturn(7L);
		when(actor.getUsername()).thenReturn("worker-7");
		when(actor.getName()).thenReturn("Operator");
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,
				"", List.of()));
		var service = mock(MesWarehouseFeedServiceImpl.class, CALLS_REAL_METHODS);
		ReflectionTestUtils.setField(service, "baseMapper", sql.getMapper(MesWarehouseFeedMapper.class));
		ReflectionTestUtils.setField(service, "liveStockService", stocks);
		ReflectionTestUtils.setField(service, "mesTaskMapper", sql.getMapper(MesTaskMapper.class));
		ReflectionTestUtils.setField(service, "arrangeMapper", sql.getMapper(MesTaskArrangeMapper.class));
		ReflectionTestUtils.setField(service, "sourceEligibility", eligibility);
		ReflectionTestUtils.setField(service, "factoryAccess", access);
		ReflectionTestUtils.setField(service, "proofCodec", new MesPhysicalTransformCodec());
		ReflectionTestUtils.setField(service, "remoteMaterialService", materials);
		ReflectionTestUtils.setField(service, "feedBirths", mock(com.kitex.mom.mes.service.MesWarehouseFeedBirthService.class));
		ReflectionTestUtils.setField(service, "executionLocks", mock(MesPhysicalTransformSourceScopeLock.class));
		ReflectionTestUtils.setField(service, "terminalGuard", mock(MesTerminalExecutionGuard.class));
		ReflectionTestUtils.setField(service, "reworkMaterialPlans", mock(MesReworkMaterialPlanService.class));
		ReflectionTestUtils.setField(service, "applicationEventPublisher", mock(org.springframework.context.ApplicationEventPublisher.class));
		var modes = mock(MesMaterialConsumptionModeResolver.class);
		when(modes.resolve(any())).thenReturn(MesMaterialConsumptionModeResolver.Mode.FEED);
		ReflectionTestUtils.setField(service, "consumptionModes", modes);
		var purchase = mock(com.kitex.mom.qms.api.feign.RemotePurchaseService.class);
		when(purchase.getTaskByMatCodeAndSupplierCodeAndBatchNo(anyString(), any(), any())).thenReturn(R.ok(null));
		ReflectionTestUtils.setField(service, "remotePurchaseService", purchase);
		return service;
	}
	private List<Long> ids(List<MesWarehouseLiveStockEntity> rows) {
		return rows.stream().map(MesWarehouseLiveStockEntity::getId).toList();
	}
	private MesWarehouseLiveStockInoutDTO request(String amount) {
		var r = new MesWarehouseLiveStockInoutDTO();
		r.setBizType("back_flush_out");
		r.setFactoryCode("F1");
		r.setBillNo("A1");
		r.setBillId(90L);
		r.setProduceTaskCode("P1");
		r.setAllowUnassignedProduceTask(true);
		r.setRequirementMatCode("M1");
		r.setSubstituteRatio(new BigDecimal("1.000000000000"));
		r.setSubstituteFlag(false);
		r.setMatCode("M1");
		r.setMatUnit("EA");
		r.setMatDetailType("RAW");
		r.setNum(new BigDecimal(amount));
		return r;
	}
	private BigDecimal quantity(long id) {
		return db.queryForObject("SELECT num FROM mes_warehouse_live_stock WHERE id=?", BigDecimal.class, id);
	}
	private void stock(long id, String reservation, String requirement, String warehouse, String amount) {
		db.update("INSERT INTO mes_warehouse_live_stock(id,tenant_id,factory_code,mat_code,mat_unit,mat_detail_type,reservation_no,requirement_mat_code,warehouse_code,num,status,inspection_status,in_time) VALUES(?,1,'F1','M1','EA','RAW',?,?,?,?, '0','NOT_REQUIRED',?)",
				id, reservation, requirement, warehouse, new BigDecimal(amount), LocalDateTime.of(2026, 10,
				23, 8, 0));
	}
	private void createTable(Class<?> type) {
		var info = TableInfoHelper.getTableInfo(type);
		var columns = new ArrayList<String>();
		columns.add(info.getKeyColumn() + " bigint primary key");
		for (var f : info.getFieldList()) {
			Class<?> t = f.getPropertyType();
			String sqlType = t == Long.class || t == Integer.class ? "bigint" : t == Boolean.class ? "boolean" : t == BigDecimal.class ? "numeric(38,12)" : t == LocalDateTime.class || t == java.util.Date.class ? "timestamp" : t == LocalDate.class ? "date" : "varchar";
			columns.add(f.getColumn() + " " + sqlType + (f.getColumn().equals("del_flag") ? " default '0'" : ""));
		}
		db.execute("CREATE TABLE " + info.getTableName() + "(" + String.join(",", columns) + ")");
	}
}
