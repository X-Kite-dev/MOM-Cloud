package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Execute the actual allocator and transaction; JDBC adapts mapper writes and locked reads. */
class WmsStockMoveWriteTest {
	private JdbcTemplate db;
	private WmsLiveStockServiceImpl service;
	private boolean failUpdate, failDelete, failInsert;
	private boolean serializedStock;
	private boolean mixedOwnerStock;
	private Boolean insertedSerialManaged;

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var ds = new DriverManagerDataSource("jdbc:h2:mem:stock-move-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa",
				"");
		ds.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(ds);
		db.execute("create table stock(id bigint primary key, quantity double "
				+ "precision, site varchar(30), source_id bigint)");
		db.update("insert into stock values(1,10,'S1',365)");
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "stock-move-write"),
				WmsLiveStockEntity.class);
		var mapper = mock(WmsLiveStockMapper.class);
		when(mapper.selectList(any(Wrapper.class)))
				.thenAnswer(call -> {
					List<WmsLiveStockEntity> rows = db.query("select * from stock where id=1 for update", (r, n) -> {
					var row = new WmsLiveStockEntity().setMatCode("M1").setMatUnit("KG")
							.setMatNum(r.getDouble("quantity"))
							.setMatBarcode(serializedStock ? "SN-1" : null)
							.setSerialManaged(serializedStock)
							.setWarehouseCode("W1").setSiteCode(r.getString("site")).setMatBatch("B1")
							.setSupplierCode("SUP")
							.setSourceInBillId(r.getLong("source_id")).setFactoryCode("F1");
					row.setId(r.getLong("id"));
					row.setTenantId(1L);
					return row;
					});
					if (mixedOwnerStock) {
						WmsLiveStockEntity other = new WmsLiveStockEntity().setMatCode("M1").setMatBatch("B1")
								.setMatUnit("KG").setMatNum(1d).setSupplierCode("SUP")
								.setWarehouseCode("W1").setSiteCode("S1").setStockOwnerType("CONSIGNED")
								.setFactoryCode("F1");
						other.setId(3L);
						other.setTenantId(1L);
						rows.add(other);
					}
					return rows;
				});
		when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
			if (failUpdate) {
				return 0;
			}
			LambdaUpdateWrapper<?> wrapper = call.getArgument(1);
			wrapper.getSqlSet();
			wrapper.getSqlSegment();
			double quantity = wrapper.getParamNameValuePairs().values().stream().filter(Double.class::isInstance)
					.map(Double.class::cast).findFirst().orElseThrow();
			return db.update("update stock set quantity=? where id=1", quantity);
		});
		// MP's one-argument default overload delegates to update(null, wrapper).
		when(mapper.update(any(Wrapper.class))).thenCallRealMethod();
		when(mapper.deleteById(any(java.io.Serializable.class)))
				.thenAnswer(call -> failDelete ? 0 : db.update("delete from stock where id=1"));
		when(mapper.insert(any(WmsLiveStockEntity.class))).thenAnswer(call -> {
			if (failInsert) {
				return 0;
			}
			WmsLiveStockEntity row = call.getArgument(0);
			insertedSerialManaged = row.getSerialManaged();
			return db.update("insert into stock values(2,?,?,?)", row.getMatNum(), row.getSiteCode(),
					row.getSourceInBillId());
		});
		var material = mock(RemoteMaterialService.class);
		when(material.getDetails("M1")).thenReturn(R.ok(new MdmMaterialEntity()));
		var production = mock(WmsMaterialReservationService.class);
		when(production.activeReservedNum(anyLong())).thenReturn(BigDecimal.ZERO);
		var sales = mock(WmsSalesStockReservationService.class);
		when(sales.activeReservedNums(anySet())).thenReturn(Map.of());
		var target = new WmsLiveStockServiceImpl(material, null, null, null, null, null, null, production, sales, null);
		ReflectionTestUtils.setField(target, "baseMapper", mapper);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
				new AnnotationTransactionAttributeSource()));
		service = (WmsLiveStockServiceImpl) proxy.getProxy();
	}

	@AfterEach
	void clearScope() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	private WmsLiveStockMoveDTO command(double quantity) {
		var command = new WmsLiveStockMoveDTO();
		command.setMatCode("M1");
		command.setMatBatch("B1");
		command.setSupplierCode("SUP");
		command.setMatUnit("KG");
		command.setOldWarehouseCode("W1");
		command.setNewWarehouseCode("W1");
		command.setOldSiteCode("S1");
		command.setNewSiteCode("S2");
		command.setOperateNum(quantity);
		return command;
	}

	private void originalOnly() {
		assertEquals(1, db.queryForObject("select count(*) from stock", Integer.class));
		assertEquals(10d, db.queryForObject("select quantity from stock where id=1", Double.class));
	}

	@Test
	void partialMovePreservesPhysicalTotalAndOriginalLineage() {
		assertTrue(service.billStockMove(command(2)));

		assertEquals(8d, db.queryForObject("select quantity from stock where id=1", Double.class));
		assertEquals(2d, db.queryForObject("select quantity from stock where id=2", Double.class));
		assertEquals(365L, db.queryForObject("select source_id from stock where id=2", Long.class));
	}

	@Test
	void moveEvidenceKeepsTheActualSourceStockAndQuantity() {
		var slices = service.billStockMoveWithEvidence(command(2));

		assertEquals(1, slices.size());
		assertEquals(1L, slices.get(0).sourceStock().getId());
		assertEquals(2d, slices.get(0).quantity());
		assertEquals(365L, slices.get(0).sourceStock().getSourceInBillId());
		assertEquals("S1", slices.get(0).sourceStock().getSiteCode());
		assertEquals(2d, db.queryForObject("select quantity from stock where id=2", Double.class));
	}

	@Test
	void mobileMoveRejectsMixedOwnersBeforeChangingSourceStock() {
		mixedOwnerStock = true;

		assertThrows(RuntimeException.class, () -> service.billStockMove(command(2)));

		originalOnly();
	}

	@Test
	void serializedStockCannotBeSplitByPartialMove() {
		serializedStock = true;
		db.update("update stock set quantity=1 where id=1");

		assertThrows(RuntimeException.class, () -> service.billStockMove(command(0.5)));

		assertEquals(1, db.queryForObject("select count(*) from stock", Integer.class));
		assertEquals(1d, db.queryForObject("select quantity from stock where id=1", Double.class));
	}

	@Test
	void serializedStockMovesAsOnePieceAndKeepsItsIdentity() {
		serializedStock = true;
		db.update("update stock set quantity=1 where id=1");
		var request = command(1);
		request.setSourceStockId(1L);
		request.setMatBarcode("SN-1");

		assertTrue(service.billStockMove(request));

		assertEquals(1, db.queryForObject("select count(*) from stock", Integer.class));
		assertEquals(1d, db.queryForObject("select quantity from stock where id=2", Double.class));
		assertEquals(Boolean.TRUE, insertedSerialManaged);
	}

	@Test
	void serializedStockCannotMoveWithoutMatchingSourceIdAndBarcode() {
		serializedStock = true;
		db.update("update stock set quantity=1 where id=1");
		var request = command(1);
		assertThrows(RuntimeException.class, () -> service.billStockMove(request));
		request.setSourceStockId(2L);
		request.setMatBarcode("SN-1");
		assertThrows(RuntimeException.class, () -> service.billStockMove(request));
		request.setSourceStockId(1L);
		request.setMatBarcode("OTHER-SERIAL");
		assertThrows(RuntimeException.class, () -> service.billStockMove(request));
		assertEquals(1, db.queryForObject("select count(*) from stock", Integer.class));
		assertEquals(1d, db.queryForObject("select quantity from stock where id=1", Double.class));
	}

	@Test
	void aggregateFlowCannotHideSerializedStockIdentity() {
		serializedStock = true;
		db.update("update stock set quantity=1 where id=1");
		var request = command(1);
		request.setAggregateFlow(true);

		assertThrows(RuntimeException.class, () -> service.billStockMove(request));

		assertEquals(1, db.queryForObject("select count(*) from stock", Integer.class));
		assertEquals(1d, db.queryForObject("select quantity from stock where id=1", Double.class));
	}

	@Test
	void zeroTargetInsertRollsBackSourceReduction() {
		failInsert = true;

		assertThrows(RuntimeException.class, () -> service.billStockMove(command(2)));

		originalOnly();
	}

	@Test
	void zeroSourceUpdateCannotCreateExtraTargetStock() {
		failUpdate = true;

		assertThrows(RuntimeException.class, () -> service.billStockMove(command(2)));

		originalOnly();
	}

	@Test
	void zeroSourceDeleteCannotCreateExtraTargetStock() {
		failDelete = true;

		assertThrows(RuntimeException.class, () -> service.billStockMove(command(10)));

		originalOnly();
	}

	@Test
	void nonfiniteMovementCannotWriteInvalidStock() {
		assertThrows(RuntimeException.class, () -> service.billStockMove(command(Double.NaN)));

		originalOnly();
	}
}
