package com.kitex.mom.wms.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsReservationMovementPolicyService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 正式预留建立、释放和批次重配的真实业务与本地事务。 JDBC适配Mapper并执行实际wrapper；标准合规快照无额外客户/供应商授权条件，MDM为远程边界。 不覆盖实际PG、组织拦截器、跨服务撤回、跨请求并发或不存在行唯一性。
 */
class WmsMaterialReservationLifecycleTest {
	private JdbcTemplate db;
	private WmsMaterialReservationServiceImpl service;
	private final AtomicLong ids = new AtomicLong(100);
	private String zeroWrite = "";
	private String warehouseFactory = "F1";
	private Long warehouseTenant = 1L;
	private int insertCount;
	private int failInsertAt = -1;
	private int updateCount;
	private int failUpdateAt = -1;
	private boolean denyProductionReservation;

	@BeforeEach
	void setup() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		var source = new DriverManagerDataSource("jdbc:h2:mem:reservation-lifecycle-" + UUID.randomUUID()
				+ ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("create table reservation(id bigint primary key,reservation_no varchar,source_type varchar,"
				+ "source_id bigint,status varchar,release_request_id varchar,release_reason varchar,"
				+ "release_by varchar,release_time timestamp,payload clob)");
		db.execute("create table detail(id bigint primary key,reservation_id bigint,reservation_no varchar,"
				+ "live_stock_id bigint,status varchar,reserved_num decimal(18,8),consumed_num decimal(18,8),"
				+ "released_num decimal(18,8),returned_num decimal(18,8),payload clob)");
		db.execute("create table stock(id bigint primary key,mat_code varchar,factory_code varchar,"
				+ "warehouse_code varchar,status int,mat_num double,mat_valid_end_date date,in_time timestamp)");
		db.execute("create table adjustment(id bigint primary key,reservation_id bigint,request_id varchar,"
				+ "status varchar,result_snapshot clob,payload clob)");
		addStock(1, 5);
		addStock(2, 5);
		for (var type : List.of(WmsMaterialReservationEntity.class, WmsMaterialReservationDetailEntity.class,
				WmsMaterialReservationAdjustmentEntity.class, WmsLiveStockEntity.class, WmsOutBillDetailEntity.class,
				WmsReservationMovementPolicyEntity.class)) {
			TableInfoHelper.initTableInfo(
					new MapperBuilderAssistant(new MybatisConfiguration(), "reservation-lifecycle"), type);
		}
		var headers = mock(WmsMaterialReservationMapper.class);
		when(headers.selectOne(any(Wrapper.class), eq(false)))
				.thenAnswer(c -> first(query("reservation", c.getArgument(0), headerRow())));
		when(headers.insert(any(WmsMaterialReservationEntity.class))).thenAnswer(c -> {
			WmsMaterialReservationEntity row = c.getArgument(0);
			row.setId(ids.incrementAndGet());
			if (zeroWrite.equals("header_insert")) {
				return 0;
			}
			return db.update("insert into reservation(id,reservation_no,source_type,source_id,"
					+ "status,payload) values(?,?,?,?,?,?)",
					row.getId(), row.getReservationNo(), row.getSourceType(), row.getSourceId(), row.getStatus(),
					JSONUtil.toJsonStr(row));
		});
		when(headers.update(isNull(), any(Wrapper.class)))
				.thenAnswer(c -> zeroWrite.equals("header_update") ? 0 : update("reservation", c.getArgument(1)));
		var details = mock(WmsMaterialReservationDetailMapper.class);
		when(details.selectList(any(Wrapper.class)))
				.thenAnswer(c -> query("detail", c.getArgument(0), detailRow()));
		when(details.selectCount(any(Wrapper.class)))
				.thenAnswer(c -> (long) query("detail", c.getArgument(0), detailRow()).size());
		when(details.insert(any(WmsMaterialReservationDetailEntity.class))).thenAnswer(c -> {
			WmsMaterialReservationDetailEntity row = c.getArgument(0);
			row.setId(ids.incrementAndGet());
			if (++insertCount == failInsertAt) {
				return 0;
			}
			return db.update("insert into detail values(?,?,?,?,?,?,?,?,?,?)", row.getId(), row.getReservationId(),
					row.getReservationNo(), row.getLiveStockId(), row.getStatus(), row.getReservedNum(),
					row.getConsumedNum(), row.getReleasedNum(), row.getReturnedNum(), JSONUtil.toJsonStr(row));
		});
		when(details.update(isNull(), any(Wrapper.class)))
				.thenAnswer(c -> ++updateCount == failUpdateAt ? 0 : update("detail", c.getArgument(1)));
		var adjustments = mock(WmsMaterialReservationAdjustmentMapper.class);
		when(adjustments.selectOne(any(Wrapper.class), eq(false)))
				.thenAnswer(c -> first(query("adjustment", c.getArgument(0), (r, n) -> {
					var row = JSONUtil.toBean(r.getString("payload"), WmsMaterialReservationAdjustmentEntity.class);
					row.setStatus(r.getString("status"));
					row.setResultSnapshot(r.getString("result_snapshot"));
					return row;
				})));
		when(adjustments.insert(any(WmsMaterialReservationAdjustmentEntity.class))).thenAnswer(c -> {
			WmsMaterialReservationAdjustmentEntity row = c.getArgument(0);
			row.setId(ids.incrementAndGet());
			if (zeroWrite.equals("adjustment_insert")) {
				return 0;
			}
			return db.update("insert into adjustment(id,reservation_id,request_id,status,payload) values(?,?,?,?,?)",
					row.getId(), row.getReservationId(), row.getRequestId(), row.getStatus(), JSONUtil.toJsonStr(row));
		});
		when(adjustments.update(isNull(), any(Wrapper.class)))
				.thenAnswer(c -> zeroWrite.equals("adjustment_update") ? 0 : update("adjustment", c.getArgument(1)));
		var stocks = mock(WmsLiveStockMapper.class);
		when(stocks.selectList(any(Wrapper.class)))
				.thenAnswer(c -> query("stock", c.getArgument(0), stockRow()));
		var materials = mock(RemoteMaterialService.class);
		when(materials.getDetails(anyString())).thenAnswer(c -> {
			var material = new MdmMaterialEntity();
			material.setMinStock(BigDecimal.ZERO);
			return R.ok(material);
		});
		var warehouses = mock(RemoteWarehouseService.class);
		when(warehouses.getByCode(anyString())).thenAnswer(c -> {
			var warehouse = new MdmWarehouseEntity();
			warehouse.setWarehouseCode(c.getArgument(0));
			warehouse.setWarehouseName("通用原料仓");
			warehouse.setFactoryCode(warehouseFactory);
			warehouse.setTenantId(warehouseTenant);
			warehouse.setStatus(true);
			return R.ok(warehouse);
		});
		var sales = mock(WmsSalesStockReservationService.class);
		when(sales.activeReservedNums(anySet())).thenReturn(Map.of());
		var outDetails = mock(WmsOutBillDetailMapper.class);
		when(outDetails.selectCount(any(Wrapper.class))).thenReturn(0L);
		var policyMapper = mock(WmsReservationMovementPolicyMapper.class);
		when(policyMapper.selectList(any(Wrapper.class))).thenAnswer(c -> {
			if (!denyProductionReservation) {
				return List.of();
			}
			var policy = new WmsReservationMovementPolicyEntity()
					.setMovementType("produce")
					.setReservationAllowed(false)
					.setStatus(true)
					.setFactoryCode("F1");
			policy.setTenantId(1L);
			return List.of(policy);
		});
		var target = new WmsMaterialReservationServiceImpl(headers, details, adjustments, stocks,
				materials, null, warehouses, outDetails, null, null, null, sales,
				new WmsReservationMovementPolicyService(policyMapper));
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (WmsMaterialReservationServiceImpl) proxy.getProxy();
	}

	@AfterEach
	void clearScope() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	private void addStock(long id, double quantity) {
		db.update("insert into stock values(?,'M1','F1','W1',0,?,null,?)", id, quantity,
				java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id)));
	}

	private RowMapper<WmsMaterialReservationEntity> headerRow() {
		return (r, n) -> {
			var row = JSONUtil.toBean(r.getString("payload"), WmsMaterialReservationEntity.class);
			row.setStatus(r.getString("status"));
			row.setReleaseRequestId(r.getString("release_request_id"));
			row.setReleaseReason(r.getString("release_reason"));
			return row;
		};
	}

	private RowMapper<WmsMaterialReservationDetailEntity> detailRow() {
		return (r, n) -> {
			var row = JSONUtil.toBean(r.getString("payload"), WmsMaterialReservationDetailEntity.class);
			row.setStatus(r.getString("status"));
			row.setReservedNum(r.getBigDecimal("reserved_num"));
			row.setConsumedNum(r.getBigDecimal("consumed_num"));
			row.setReleasedNum(r.getBigDecimal("released_num"));
			row.setReturnedNum(r.getBigDecimal("returned_num"));
			return row;
		};
	}

	private RowMapper<WmsLiveStockEntity> stockRow() {
		return (r, n) -> {
			var row = new WmsLiveStockEntity().setMatCode(r.getString("mat_code")).setMatUnit("KG")
					.setFactoryCode(r.getString("factory_code")).setWarehouseCode(r.getString("warehouse_code"))
					.setSiteCode("SITE-" + r.getLong("id")).setMatBatch("B-" + r.getLong("id"))
					.setStatus(r.getInt("status")).setMatNum(r.getDouble("mat_num"));
			row.setId(r.getLong("id"));
			return row;
		};
	}

	private record Bound(String sql, Object[] values) {
	}

	private Bound bind(String sql, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}").matcher(sql);
		var parameters = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
		var result = new StringBuilder();
		var values = new ArrayList<Object>();
		while (matcher.find()) {
			values.add(parameters.get(matcher.group(1)));
			matcher.appendReplacement(result, "?");
		}
		matcher.appendTail(result);
		return new Bound(result.toString(), values.toArray());
	}

	private <T> List<T> query(String table, Wrapper<?> wrapper, RowMapper<T> row) {
		var bound = bind("select * from " + table + " " + wrapper.getCustomSqlSegment(), wrapper);
		return db.query(bound.sql(), row, bound.values());
	}

	private int update(String table, Wrapper<?> wrapper) {
		var change = (LambdaUpdateWrapper<?>) wrapper;
		var bound = bind("update " + table + " set " + change.getSqlSet() + " " + change.getCustomSqlSegment(),
				wrapper);
		return db.update(bound.sql(), bound.values());
	}

	private <T> T first(List<T> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

	private int count(String table) {
		return db.queryForObject("select count(*) from " + table, Integer.class);
	}

	private String status() {
		return db.queryForObject("select status from reservation", String.class);
	}

	private double sum(String field) {
		return db.queryForObject("select coalesce(sum(" + field + "),0) from detail", Double.class);
	}

	private double reserved(long stock) {
		return service.activeReservedNum(stock).doubleValue();
	}

	private WmsMaterialReservationCreateDTO create() {
		var command = new WmsMaterialReservationCreateDTO();
		command.setRequestId("reserve-request-0001");
		command.setSourceType("PLAN_MRP_MAIN");
		command.setSourceId(77L);
		command.setSourceNo("MRP-77");
		command.setFactoryCode("F1");
		command.setWarehouseCode("W1");
		command.setCustomerCode("CUSTOMER-1");
		command.setRequiredDate(LocalDate.now().plusDays(10));
		command.setAllocations(List.of(new WmsMaterialReservationAllocationDTO()
				.setRequirementMatCode("M1").setActualMatCode("M1").setActualMatUnit("KG")
				.setSubstituteRatio(BigDecimal.ONE).setRequirementNum(new BigDecimal("8"))
				.setActualNum(new BigDecimal("8"))));
		return command;
	}

	private WmsMaterialReservationReleaseDTO release() {
		var command = new WmsMaterialReservationReleaseDTO();
		command.setRequestId("release-request-0001");
		command.setSourceType("PLAN_MRP_MAIN");
		command.setSourceId(77L);
		command.setReason("取消生产需求，释放未使用余量");
		return command;
	}

	private WmsMaterialReservationReallocateDTO reallocate() {
		var command = new WmsMaterialReservationReallocateDTO();
		command.setRequestId("reallocate-request-0001");
		command.setSourceType("PLAN_MRP_MAIN");
		command.setSourceId(77L);
		command.setReason("原批次已锁定，按原批准条件重配");
		return command;
	}

	private void makeRisky() {
		service.reserve(create());
		db.update("update stock set status=1 where id=1");
		addStock(3, 7);
	}

	private void originalAllocation() {
		assertEquals("ACTIVE", status());
		assertEquals(2, count("detail"));
		assertEquals(5d, reserved(1));
		assertEquals(3d, reserved(2));
		assertEquals(0d, reserved(3));
		assertEquals(0d, sum("released_num"));
		assertEquals(0, count("adjustment"));
	}

	@Test
	void configuredProductionMovementDenialLeavesInventoryAndReservationUntouched() {
		denyProductionReservation = true;

		assertThrows(CheckedException.class, () -> service.reserve(create()));

		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
		assertEquals(10d, db.queryForObject("select sum(mat_num) from stock", Double.class));
	}

	@Test
	void unknownSourceTypeCannotBypassConfiguredProductionDenial() {
		denyProductionReservation = true;
		var command = create();
		command.setSourceType("PLAN_MRP_MAIN_X");

		assertThrows(CheckedException.class, () -> service.reserve(command));

		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void disablingProductionMovementDoesNotInvalidateIdenticalReservationReplay() {
		var first = service.reserve(create());
		denyProductionReservation = true;

		var replay = service.reserve(create());

		assertEquals(first.getReservationNo(), replay.getReservationNo());
		assertEquals(1, count("reservation"));
		assertEquals(2, count("detail"));
	}

	@Test
	void reservePersistsEntireAllocationWithoutReducingPhysicalStock() {
		var result = service.reserve(create());

		assertEquals("MRP-M-77", result.getReservationNo());
		assertEquals(1, count("reservation"));
		assertEquals(2, count("detail"));
		assertEquals(8d, sum("reserved_num"));
		assertEquals(5d, reserved(1));
		assertEquals(3d, reserved(2));
		assertEquals(10d, db.queryForObject("select sum(mat_num) from stock", Double.class));
	}

	@Test
	void zeroReservationHeaderInsertCannotReturnAllocatedSuccess() {
		zeroWrite = "header_insert";

		assertThrows(CheckedException.class, () -> service.reserve(create()));

		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void zeroFirstAllocationInsertRollsBackHeader() {
		failInsertAt = 1;

		assertThrows(CheckedException.class, () -> service.reserve(create()));

		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void zeroLaterAllocationInsertRollsBackEarlierAllocationAndHeader() {
		failInsertAt = 2;

		assertThrows(CheckedException.class, () -> service.reserve(create()));

		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void identicalActiveSourceRetryReturnsOriginalWithoutDoubleAllocation() {
		var first = service.reserve(create());

		var retry = create();
		retry.setRequestId("reserve-request-0002");

		assertEquals(first.getReservationNo(), service.reserve(retry).getReservationNo());

		assertEquals(1, count("reservation"));
		assertEquals(2, count("detail"));
		assertEquals(8d, sum("reserved_num"));
	}

	@Test
	void sameSourceCannotSilentlyReplaceFrozenCustomer() {
		service.reserve(create());

		var changed = create();
		changed.setCustomerCode("OTHER-CUSTOMER");

		assertThrows(CheckedException.class, () -> service.reserve(changed));

		assertEquals(8d, sum("reserved_num"));
	}

	@Test
	void sameSourceCannotReuseFrozenReservationFromAnotherFactory() {
		service.reserve(create());

		warehouseFactory = "F2";
		var changed = create();
		changed.setFactoryCode("F2");

		assertThrows(CheckedException.class, () -> service.reserve(changed));

		assertEquals(1, count("reservation"));
		assertEquals(8d, sum("reserved_num"));
	}

	@Test
	void reservationRejectsWarehouseWithoutFactoryOwnershipBeforeAnyWrite() {
		warehouseFactory = "";

		assertThrows(CheckedException.class, () -> service.reserve(create()));
		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void reservationRejectsWarehouseFromAnotherTenantBeforeAnyWrite() {
		warehouseTenant = 2L;

		assertThrows(CheckedException.class, () -> service.reserve(create()));
		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void reservationRejectsMissingTenantContextBeforeAnyWrite() {
		TenantContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.reserve(create()));
		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void reservationRejectsFactoryDifferentFromSelectedContextBeforeAnyWrite() {
		var command = create();
		command.setFactoryCode("F2");

		assertThrows(CheckedException.class, () -> service.reserve(command));
		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void sameSourceCannotSilentlyReplaceFrozenUseDate() {
		service.reserve(create());

		var changed = create();
		changed.setRequiredDate(LocalDate.now().plusDays(20));

		assertThrows(CheckedException.class, () -> service.reserve(changed));

		assertEquals(8d, sum("reserved_num"));
	}

	@Test
	void releaseFreesOnlyRemainingQuantityWithoutRestoringConsumedStock() {
		service.reserve(create());

		db.update("update detail set consumed_num=2 where live_stock_id=1");
		db.update("update stock set mat_num=3 where id=1");

		assertTrue(service.release(release()));

		assertEquals("RELEASED", status());
		assertEquals(6d, sum("released_num"));
		assertEquals(2d, sum("consumed_num"));
		assertEquals(0d, reserved(1));
		assertEquals(0d, reserved(2));
		assertEquals(8d, db.queryForObject("select sum(mat_num) from stock", Double.class));

		var out = new WmsOutBillDetailEntity().setReservationNo("MRP-M-77").setMatCode("M1").setMatNum(1d);

		assertThrows(CheckedException.class, () -> service.consume(out));
		assertThrows(CheckedException.class, () -> service.availableReservedStocks("MRP-M-77", "M1", "W1"));
	}

	@Test
	void zeroReleaseDetailWriteRollsBackAllReleaseFacts() {
		service.reserve(create());

		failUpdateAt = 1;

		assertThrows(CheckedException.class, () -> service.release(release()));

		originalAllocation();
	}

	@Test
	void laterReleaseFailureRollsBackEarlierReleasedDetail() {
		service.reserve(create());

		failUpdateAt = 2;

		assertThrows(CheckedException.class, () -> service.release(release()));

		originalAllocation();
	}

	@Test
	void zeroReleaseHeaderWriteRestoresEveryReleasedDetail() {
		service.reserve(create());

		zeroWrite = "header_update";

		assertThrows(CheckedException.class, () -> service.release(release()));

		originalAllocation();
		assertNull(db.queryForObject("select release_request_id from reservation", String.class));
	}

	@Test
	void staleReleaseRequestIdCannotSkipStillActiveReservation() {
		service.reserve(create());

		db.update("update reservation set release_request_id=?", release().getRequestId());

		assertTrue(service.release(release()));

		assertEquals("RELEASED", status());
		assertEquals(8d, sum("released_num"));
		assertEquals(0d, reserved(1));
	}

	@Test
	void unknownReservationStateCannotReportSuccessfulRelease() {
		service.reserve(create());

		db.update("update reservation set status='BROKEN'");

		assertThrows(CheckedException.class, () -> service.release(release()));

		assertEquals("BROKEN", status());
		assertEquals(0d, sum("released_num"));
		assertEquals(5d, reserved(1));
	}

	@Test
	void legacyReleasedHeaderWithActiveDetailsCannotClaimSuccessfulRelease() {
		service.reserve(create());

		db.update("update reservation set status='RELEASED'");

		assertThrows(CheckedException.class, () -> service.release(release()));

		assertEquals(0d, sum("released_num"));
		assertEquals(5d, reserved(1));
		assertEquals(3d, reserved(2));
	}

	@Test
	void alreadyReleasedRetryDoesNotIncreaseReleasedQuantity() {
		service.reserve(create());
		service.release(release());

		assertTrue(service.release(release()));

		assertEquals(8d, sum("released_num"));
		assertEquals(2, count("detail"));
	}

	@Test
	void releasedReservationRejectsAChangedRequestIdOrReasonOnRetry() {
		service.reserve(create());
		service.release(release());
		var anotherRequest = release();
		anotherRequest.setRequestId("release-request-0002");
		var changedReason = release();
		changedReason.setReason("另一项取消原因");

		assertThrows(CheckedException.class, () -> service.release(anotherRequest));
		assertThrows(CheckedException.class, () -> service.release(changedReason));
		assertEquals(8d, sum("released_num"));
	}

	@Test
	void missingSourceReleaseRemainsAnIdempotentNoOp() {
		assertTrue(service.release(release()));

		assertEquals(0, count("reservation"));
		assertEquals(0, count("detail"));
	}

	@Test
	void fullyConsumedReservationCannotBeReleased() {
		service.reserve(create());

		db.update("update reservation set status='CONSUMED'");
		db.update("update detail set consumed_num=reserved_num,status='CLOSED'");

		assertThrows(CheckedException.class, () -> service.release(release()));

		assertEquals("CONSUMED", status());
		assertEquals(0d, sum("released_num"));
	}

	@Test
	void successfulReallocationPreservesTotalDemandAndOriginalProof() {
		makeRisky();

		assertTrue(service.reallocate(reallocate()));

		assertEquals("ACTIVE", status());
		assertEquals(0d, reserved(1));
		assertEquals(5d, reserved(2));
		assertEquals(3d, reserved(3));
		assertEquals(5d, sum("released_num"));
		assertEquals(4, count("detail"));
		assertEquals("SUCCEEDED", db.queryForObject("select status from adjustment", String.class));
		assertNotNull(db.queryForObject("select result_snapshot from adjustment", String.class));
		assertEquals(17d, db.queryForObject("select sum(mat_num) from stock", Double.class));
	}

	@Test
	void zeroAdjustmentInsertCannotReleaseOldAllocationOrCreateNewOne() {
		makeRisky();
		zeroWrite = "adjustment_insert";

		assertThrows(CheckedException.class, () -> service.reallocate(reallocate()));

		originalAllocation();
	}

	@Test
	void zeroOldAllocationReleaseRollsBackAdjustment() {
		makeRisky();
		failUpdateAt = 1;

		assertThrows(CheckedException.class, () -> service.reallocate(reallocate()));

		originalAllocation();
	}

	@Test
	void zeroReplacementInsertRestoresOldAllocationAndAdjustment() {
		makeRisky();
		failInsertAt = insertCount + 1;

		assertThrows(CheckedException.class, () -> service.reallocate(reallocate()));

		originalAllocation();
	}

	@Test
	void zeroLaterReplacementInsertRollsBackEarlierReplacement() {
		makeRisky();
		failInsertAt = insertCount + 2;

		assertThrows(CheckedException.class, () -> service.reallocate(reallocate()));

		originalAllocation();
	}

	@Test
	void zeroAdjustmentCompletionRestoresEntireOriginalAllocation() {
		makeRisky();
		zeroWrite = "adjustment_update";

		assertThrows(CheckedException.class, () -> service.reallocate(reallocate()));

		originalAllocation();
	}

	@Test
	void sameReallocationRequestDoesNotReplaceHealthyBatchesAgain() {
		makeRisky();

		service.reallocate(reallocate());

		assertTrue(service.reallocate(reallocate()));

		assertEquals(1, count("adjustment"));
		assertEquals(4, count("detail"));
		assertEquals(8d, reserved(2) + reserved(3));
	}

	@Test
	void reallocationReplayRejectsChangedReason() {
		makeRisky();
		service.reallocate(reallocate());
		var changed = reallocate();
		changed.setReason("改用另一套审批原因");

		assertThrows(CheckedException.class, () -> service.reallocate(changed));
		assertEquals(1, count("adjustment"));
		assertEquals(4, count("detail"));
	}

	@Test
	void insufficientReplacementRollsBackWithoutFreeingOriginalReservation() {
		makeRisky();
		db.update("update stock set mat_num=1 where id=3");

		assertThrows(CheckedException.class, () -> service.reallocate(reallocate()));

		originalAllocation();
	}
}
