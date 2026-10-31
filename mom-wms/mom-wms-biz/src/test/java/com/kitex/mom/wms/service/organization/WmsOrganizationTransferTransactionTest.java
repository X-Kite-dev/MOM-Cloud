package com.kitex.mom.wms.service.organization;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.feign.*;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.repository.WmsOrganizationTransferRepository;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferAccess.Scope;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实关系台账与Spring事务，库存边界也落同一JDBC库，实际执行两端上下文和原始回执。
 */
class WmsOrganizationTransferTransactionTest {
	JdbcTemplate db;
	WmsOrganizationTransferRepository repo;
	WmsOrganizationTransferService service;
	WmsOrganizationTransferPolicyService policies;
	WmsOrganizationTransferStockService stockFacade;
	WmsOrganizationTransferAccess access;
	DataSourceTransactionManager transactions;
	final AtomicLong stockIds = new AtomicLong(100);
	final ThreadLocal<String> actor = ThreadLocal.withInitial(() -> "source");
	boolean failFlow;
	boolean wrongUnit;
	boolean containerRequired;
	boolean foreignLocation;
	boolean serialManaged;
	long sequence;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:org-" + UUID.randomUUID() +
				";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		db.execute("CREATE SCHEMA mom_mdm");
		db.execute(
				"CREATE TABLE mom_mdm.mdm_warehouse(id bigint PRIMARY KEY,tenant_id bigint,factory_code varchar,warehouse_code varchar,status boolean,del_flag char(1))");
		db.update("INSERT INTO mom_mdm.mdm_warehouse VALUES(1,1,'F1','WA',true,'0'),(2,1,'F2','WB',true,'0')");
		String sql = Files.readString(Path.of("../database/wms-organization-transfer-pg.sql"));
		var matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS[\\s\\S]*?;").matcher(sql);
		while (matcher.find()) db.execute(matcher.group());
		db.execute("CREATE TABLE locks(lock_key varchar primary key)");
		db.execute("CREATE TABLE stock(id bigint primary key,factory varchar,quantity numeric,snapshot text)");
		db.execute("CREATE TABLE flow(id bigint primary key,payload text)");
		var original = sourceStock(new BigDecimal("6"));
		db.update("INSERT INTO stock VALUES(10,'F1',6,?)", JSONUtil.toJsonStr(original));
		repo = spy(new WmsOrganizationTransferRepository(db));
		doAnswer(call -> {
			String key = call.getArgument(0) + ":" + call.getArgument(1);
			db.update("MERGE INTO locks KEY(lock_key) VALUES(?)", key);
			db.queryForObject("SELECT lock_key FROM locks WHERE lock_key=? FOR UPDATE", String.class, key);
			return null;
		}).when(repo).lock(anyLong(), anyString());
		access = mock(WmsOrganizationTransferAccess.class);
		when(access.scope(anyString(), anyBoolean())).thenAnswer(
				call -> new Scope(TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(),
						call.getArgument(0), actor.get(),
						"source".equals(actor.get()) ? 1L : "target".equals(actor.get()) ? 2L : 3L));
		when(access.allowed(any(), anyString())).thenReturn(true);
		when(access.mobileEnabled(any())).thenReturn(true);
		when(access.warehouse(any())).thenAnswer(call -> {
			Scope scope = call.getArgument(0);
			var w = new MdmWarehouseEntity().setWarehouseCode(scope.warehouse()).setWarehouseName(
					scope.warehouse()).setFactoryCode(scope.factory()).setStatus(true);
			w.setTenantId(scope.tenant());
			w.setIsContainer(containerRequired ? 1 : 0);
			return w;
		});
		var stockMapper = mock(WmsLiveStockMapper.class);
		db.execute(
				"CREATE TABLE candidate(id bigint,tenant_id bigint,factory_code varchar,warehouse_code varchar,status integer,mat_code varchar,mat_barcode varchar,snapshot text)");
		when(stockMapper.selectList(any(Wrapper.class))).thenAnswer(call -> {
			var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WmsLiveStockEntity>) call.getArgument(
					0);
			String fragment = wrapper.getSqlSegment();
			var values = new ArrayList<Object>();
			var parameters = java.util.regex.Pattern.compile("#\\{ew.paramNameValuePairs.([^}]+)\\}").matcher(fragment);
			while (parameters.find()) values.add(wrapper.getParamNameValuePairs().get(parameters.group(1)));
			String candidateSql = "SELECT snapshot FROM candidate WHERE " +
					fragment.replaceAll("#\\{ew.paramNameValuePairs.([^}]+)\\}", "?");
			return db.query(candidateSql, (row, index) -> JSONUtil.toBean(row.getString(1), WmsLiveStockEntity.class),
					values.toArray());
		});
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "org-stock"),
				WmsLiveStockEntity.class);
		when(stockMapper.selectOne(any(Wrapper.class))).thenAnswer(call -> {
			BigDecimal qty = db.queryForObject("SELECT quantity FROM stock WHERE id=10", BigDecimal.class);
			return sourceStock(qty);
		});
		var live = mock(WmsLiveStockService.class);
		when(live.transferOut(anyLong(), anyDouble(), anyString())).thenAnswer(call -> {
			assertEquals("F1", FactoryContextHolder.getFactoryCode());
			BigDecimal quantity = BigDecimal.valueOf((Double) call.getArgument(1));
			BigDecimal current = db.queryForObject("SELECT quantity FROM stock WHERE id=10 FOR UPDATE",
					BigDecimal.class);
			if (current.compareTo(quantity) < 0) throw new CheckedException("源库存不足");
			db.update("UPDATE stock SET quantity=quantity-? WHERE id=10", quantity);
			return sourceStock(quantity);
		});
		when(live.transferInWithEvidence(any(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(
				call -> {
					WmsInoutFlowEntity flow = call.getArgument(0);
					assertEquals(FactoryContextHolder.getFactoryCode(), flow.getFactoryCode());
					long id = stockIds.getAndIncrement();
					db.update("INSERT INTO stock VALUES(?,?,?,?)", id, flow.getFactoryCode(), flow.getMatNum(),
							JSONUtil.toJsonStr(flow));
					return id;
				});
		var flowMapper = mock(WmsInoutFlowMapper.class);
		when(flowMapper.insert(any(WmsInoutFlowEntity.class))).thenAnswer(call -> {
			WmsInoutFlowEntity flow = call.getArgument(0);
			db.update("INSERT INTO flow VALUES(?,?)", flow.getId(), JSONUtil.toJsonStr(flow));
			if (failFlow) throw new CheckedException("模拟流水后续失败");
			return 1;
		});
		var reservations = mock(WmsMaterialReservationService.class);
		when(reservations.activeReservedNum(anyLong())).thenReturn(BigDecimal.ZERO);
		var sales = mock(WmsSalesStockReservationService.class);
		when(sales.activeReservedNums(anySet())).thenReturn(Map.of());
		var materials = mock(RemoteMaterialService.class);
		when(materials.getDetails("M1")).thenAnswer(call -> {
			var m = new MdmMaterialEntity();
			m.setMaterialCode("M1");
			m.setMaterialName("铜线");
			m.setBasicUnit(wrongUnit ? "EA" : "KG");
			m.setFactoryCode(FactoryContextHolder.getFactoryCode());
			m.setTenantId(TenantContextHolder.getTenantId());
			m.setStatus(true);
			m.setSerialManaged(serialManaged);
			return R.ok(m);
		});
		var locations = mock(RemoteWarehouseLocationService.class);
		when(locations.getByCode(anyString())).thenAnswer(call -> {
			var l = new MdmWarehouseLocationEntity();
			l.setLocationCode(call.getArgument(0));
			l.setWarehouseCode("F1".equals(FactoryContextHolder.getFactoryCode()) ? "WA" : "WB");
			l.setFactoryCode(FactoryContextHolder.getFactoryCode());
			l.setStatus(true);
			l.setTenantId(foreignLocation ? 2L : TenantContextHolder.getTenantId());
			return R.ok(l);
		});
		transactions = new DataSourceTransactionManager(source);
		policies = proxy(new WmsOrganizationTransferPolicyService(repo, access));
		var stocks = new WmsOrganizationTransferStockService(stockMapper, live, flowMapper, reservations, sales,
				materials, locations, access, mock(WmsContainerBarcodeMapper.class));
		stockFacade = stocks;
		var json = new WmsOrganizationTransferReceiptCodec();
		service = proxy(new WmsOrganizationTransferService(repo, access, policies, stocks, json));
		identity("F1", "source", 1);
		savePolicy("WA", "CA");
		identity("F2", "target", 1);
		savePolicy("WB", "CB");
		identity("F1", "source", 1);
	}

	private WmsLiveStockEntity sourceStock(BigDecimal quantity) {
		var s = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("WA").setWarehouseName(
						"源仓").setSiteCode("SA")
				.setMatCode("M1").setMatName("铜线").setMatUnit("KG").setMatBatch("B1").setSerialManaged(
						false).setMatNum(quantity.doubleValue())
				.setStockOwnerType("OWNED").setStockOwnerCode("").setStatus(0).setQualityStatus(
						"QUALIFIED").setQualityResult("QUALIFIED");
		s.setSerialManaged(serialManaged);
		s.setMatBarcode(serialManaged ? "SERIAL-1" : null);
		s.setId(10L);
		s.setTenantId(1L);
		return s;
	}

	private void savePolicy(String warehouse, String company) {
		var p = new PolicyCommand();
		p.setWarehouseCode(warehouse);
		p.setTenantId(TenantContextHolder.getTenantId());
		p.setFactoryCode(FactoryContextHolder.getFactoryCode());
		p.setOperatorId("source".equals(actor.get()) ? 1L : 2L);
		p.setCompanyCode(company);
		p.setCompanyName(company);
		p.setEnabled(true);
		p.setCrossCompany(true);
		p.setExpectedVersion(0);
		policies.save(p);
	}

	private void identity(String factory, String user, long tenant) {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
		FactoryContextHolder.setFactoryCode(factory);
		TenantContextHolder.setTenantId(tenant);
		actor.set(user);
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(T target) {
		var p = new ProxyFactory(target);
		p.setProxyTargetClass(true);
		p.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
		return (T) p.getProxy();
	}

	private Command create() {
		var c = command(Action.CREATE, null, 0, "WA");
		c.setTargetPolicyId(repo.policy(1L, "F2", "WB").getId());
		c.setAgreementRef("IC-AGREEMENT-1");
		var line = new InputLine();
		line.setStockId(10L);
		line.setQuantity(new BigDecimal("4"));
		c.setLines(List.of(line));
		return c;
	}

	private Command command(Action action, Long id, int version, String warehouse) {
		var c = new Command();
		c.setAction(action);
		c.setTenantId(TenantContextHolder.getTenantId());
		c.setFactoryCode(FactoryContextHolder.getFactoryCode());
		c.setOperatorId("source".equals(actor.get()) ? 1L : 2L);
		c.setBillId(id);
		c.setExpectedVersion(version);
		c.setWarehouseCode(warehouse);
		c.setRequestId("ORG-TEST-original-" + String.format("%08d", ++sequence));
		c.setReason("交接核对");
		c.setEvidence("HANDOVER-1");
		if (action == Action.SHIP && id != null) {
			var line = new InputLine();
			var original = repo.lines(TenantContextHolder.getTenantId(), id).get(0);
			line.setLineId(original.getId());
			line.setQuantity(original.getQuantity());
			c.setLines(List.of(line));
		}
		return c;
	}

	private Receipt ship() {
		var created = service.execute(create());
		identity("F2", "target", 1);
		service.execute(command(Action.CONFIRM, created.billId(), 1, "WB"));
		identity("F1", "source", 1);
		return service.execute(command(Action.SHIP, created.billId(), 2, "WA"));
	}

	private Command physical(Action action, Long id, int version, String warehouse, String quantity) {
		var c = command(action, id, version, warehouse);
		var line = new InputLine();
		line.setLineId(repo.lines(1L, id).get(0).getId());
		line.setQuantity(new BigDecimal(quantity));
		line.setSiteCode("F1".equals(FactoryContextHolder.getFactoryCode()) ? "SA" : "SB");
		c.setLines(List.of(line));
		return c;
	}

	@AfterEach
	void clear() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
		actor.remove();
	}

	@Test
	void mobilePartiesCreateConfirmShipAndReceiveWithPhysicalQuantityConserved() {
		var created = service.executeMobile(create());
		identity("F2", "target", 1);
		service.executeMobile(command(Action.CONFIRM, created.billId(), 1, "WB"));
		identity("F1", "source", 1);
		var shipped = service.executeMobile(command(Action.SHIP, created.billId(), 2, "WA"));
		identity("F2", "target", 1);

		var received = service.executeMobile(physical(Action.RECEIVE, created.billId(), 3, "WB", "4"));

		assertEquals("COMPLETED", received.state());
		assertEquals(1, shipped.effects().size());
		assertEquals(1, received.effects().size());
		assertEquals("F2", received.effects().get(0).factoryCode());
		assertEquals(0, new BigDecimal("6").compareTo(db.queryForObject("SELECT sum(quantity) FROM stock", BigDecimal.class)));
		verify(access, times(2)).checkMobile(new Scope(1L, "F1", "WA", "source", 1L));
		verify(access, times(2)).checkMobile(new Scope(1L, "F2", "WB", "target", 2L));
	}

	@Test
	void mobilePhysicalFailureRollsBackStockFlowStateAndOriginalReceiptTogether() {
		var created = service.execute(create());
		identity("F2", "target", 1);
		service.execute(command(Action.CONFIRM, created.billId(), 1, "WB"));
		identity("F1", "source", 1);
		var command = command(Action.SHIP, created.billId(), 2, "WA");
		failFlow = true;

		assertThrows(CheckedException.class, () -> service.executeMobile(command));

		assertNull(repo.operation(1L, command.getRequestId()));
		assertEquals(2, service.detail("WA", created.billId()).bill().getVersion());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM flow", Integer.class));
		assertEquals(0, new BigDecimal("6").compareTo(db.queryForObject("SELECT quantity FROM stock WHERE id=10", BigDecimal.class)));
	}

	@Test
	void disabledMobileNewCreationDoesNotWriteBusinessOrPermanentReceipt() {
		var command = create();
		doThrow(new CheckedException("mobile disabled")).when(access).checkMobile(any());

		var error = assertThrows(CheckedException.class, () -> service.executeMobile(command));

		assertEquals("mobile disabled", error.getMessage());
		assertNull(repo.operation(1L, command.getRequestId()));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM flow", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_org_transfer_bill", Integer.class));
		assertEquals(0, new BigDecimal("6").compareTo(db.queryForObject("SELECT quantity FROM stock WHERE id=10", BigDecimal.class)));
	}

	@Test
	void oldPcOriginalCanReplayThroughMobileAfterTheSwitchIsClosed() {
		var command = create();
		var original = service.execute(command);
		doThrow(new CheckedException("mobile disabled")).when(access).checkMobile(any());

		assertEquals(original, service.executeMobile(command));
		verify(access, never()).checkMobile(any());
		assertEquals(original, service.lookup("WA", command.getRequestId()));
	}

	@Test
	void disabledMobileUnknownRequestCanBeAbandonedAndReplayedWithoutPosting() {
		var command = create();
		doThrow(new CheckedException("mobile disabled")).when(access).checkMobile(any());

		var cancelled = service.abandon(command, "关闭后核对原请求");

		assertEquals("ABANDONED", cancelled.status());
		assertEquals(cancelled, service.executeMobile(command));
		verify(access, never()).checkMobile(any());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_org_transfer_bill", Integer.class));
	}

	@Test
	void mobileConfirmationChecksOnlyTheActualReceivingParty() {
		var created = service.execute(create());
		identity("F2", "target", 1);
		doAnswer(call -> {
			Scope actual = call.getArgument(0);
			assertEquals("F2", actual.factory());
			assertEquals("WB", actual.warehouse());
			return null;
		}).when(access).checkMobile(any());

		var receipt = service.executeMobile(command(Action.CONFIRM, created.billId(), 1, "WB"));

		assertEquals("READY_TO_SHIP", receipt.state());
		verify(access).checkMobile(new Scope(1L, "F2", "WB", "target", 2L));
	}

	@Test
	void mobileShippingDisabledCannotDeductStockAdvanceStateOrCreateReceipt() {
		var created = service.execute(create());
		identity("F2", "target", 1);
		service.execute(command(Action.CONFIRM, created.billId(), 1, "WB"));
		identity("F1", "source", 1);
		var shipping = command(Action.SHIP, created.billId(), 2, "WA");
		doThrow(new CheckedException("mobile disabled")).when(access).checkMobile(any());

		assertThrows(CheckedException.class, () -> service.executeMobile(shipping));

		assertNull(repo.operation(1L, shipping.getRequestId()));
		assertEquals(2, service.detail("WA", created.billId()).bill().getVersion());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM flow", Integer.class));
		assertEquals(0, new BigDecimal("6").compareTo(db.queryForObject("SELECT quantity FROM stock WHERE id=10", BigDecimal.class)));
	}

	@Test
	void disabledMobileContextRetainsActualIdentityForRecoveryButHasNoNewActions() {
		when(access.mobileEnabled(any())).thenReturn(false);

		var context = service.mobileContext("WA");

		assertEquals(1, context.mobileContractVersion());
		assertEquals(1L, context.tenantId());
		assertEquals("F1", context.factoryCode());
		assertEquals("source", context.operatorCode());
		assertFalse(context.mobileEnabled());
		assertFalse(context.canConfigure());
		assertTrue(context.permissions().isEmpty());
	}

	@Test
	void mobileConfigurationFailureDisablesNewActionsWithoutBlockingOriginalRecoveryContext() {
		when(access.mobileEnabled(any())).thenThrow(new IllegalStateException("catalog unavailable"));

		var context = service.mobileContext("WA");

		assertFalse(context.mobileEnabled());
		assertTrue(context.permissions().isEmpty());
		assertFalse(context.mobileMessage().isBlank());
		when(access.scope("WA", false)).thenThrow(new CheckedException("factory permission revoked"));
		assertThrows(CheckedException.class, () -> service.mobileContext("WA"));
	}

	@Test
	void disabledMobileDetailKeepsEvidenceButOffersNoNewBusinessActions() {
		var created = service.execute(create());
		when(access.mobileEnabled(any())).thenReturn(false);

		var detail = service.mobileDetail("WA", created.billId());

		assertEquals(created.billId(), detail.bill().getId());
		assertFalse(detail.lines().isEmpty());
		assertTrue(detail.actions().isEmpty());
		assertFalse(service.detail("WA", created.billId()).actions().isEmpty());
	}

	@Test
	void mobilePolicySaveIsBlockedButPcPolicySaveKeepsItsOriginalContract() {
		var command = new PolicyCommand();
		command.setTenantId(1L);
		command.setOperatorId(1L);
		command.setFactoryCode("F1");
		command.setWarehouseCode("WA");
		command.setCompanyCode("CA");
		command.setCompanyName("源公司");
		command.setEnabled(true);
		command.setCrossCompany(true);
		command.setExpectedVersion(1);
		doThrow(new CheckedException("mobile disabled")).when(access).checkMobile(any());

		assertThrows(CheckedException.class, () -> policies.saveMobile(command));
		assertEquals(1, policies.current("WA").getVersion());
		assertEquals(2, policies.save(command).getVersion());
	}

	@Test
	void mobileCannotUseTheSwitchToReplaceActualIntentOrRolePermissions() {
		var command = create();
		command.setFactoryCode("F2");

		assertThrows(CheckedException.class, () -> service.executeMobile(command));
		verify(access, never()).checkMobile(any());
		assertNull(repo.operation(1L, command.getRequestId()));
	}

	@Test
	void twoFactoriesConfirmShipAndReceiveWithoutSwitchingStockContext() {
		var issued = ship();
		assertEquals("IN_TRANSIT", issued.state());
		assertEquals(2, db.queryForObject("SELECT quantity FROM stock WHERE id=10", Integer.class));
		identity("F2", "target", 1);
		var command = physical(Action.RECEIVE, issued.billId(), 3, "WB", "4");
		var received = service.execute(command);
		assertEquals("COMPLETED", received.state());
		assertEquals(1, received.effects().size());
		assertEquals("F2", received.effects().get(0).factoryCode());
		assertEquals(received, service.execute(command));
		assertEquals(6, db.queryForObject("SELECT sum(quantity) FROM stock", Integer.class));
		assertEquals(2, db.queryForObject("SELECT count(*) FROM flow", Integer.class));
		assertEquals("OWNED", JSONUtil.parseObj(
				db.queryForObject("SELECT snapshot FROM stock WHERE factory='F2'", String.class)).getStr(
				"stockOwnerType"));
	}

	@Test
	void partialReceiptPhysicalReturnAndTwoPartyDifferenceCloseConserveAllQuantities() {
		var issued = ship();
		identity("F2", "target", 1);
		service.execute(physical(Action.RECEIVE, issued.billId(), 3, "WB", "1"));
		service.execute(command(Action.REQUEST_RETURN, issued.billId(), 4, "WB"));
		identity("F1", "source", 1);
		service.execute(physical(Action.RETURN, issued.billId(), 5, "WA", "2"));
		identity("F2", "target", 1);
		service.execute(command(Action.PROPOSE_EXCEPTION, issued.billId(), 6, "WB"));
		identity("F1", "source", 1);
		var closed = service.execute(command(Action.APPROVE_EXCEPTION, issued.billId(), 7, "WA"));
		assertEquals("CLOSED_WITH_DIFFERENCE", closed.state());
		var line = repo.lines(1L, issued.billId()).get(0);
		assertEquals(0, new BigDecimal("1").compareTo(line.getExceptionQuantity()));
		assertEquals(5, db.queryForObject("SELECT sum(quantity) FROM stock", Integer.class));
		assertEquals(0, WmsOrganizationTransferRules.remaining(line.getQuantity(), line.getReceivedQuantity(),
				line.getReturnedQuantity(), line.getExceptionQuantity()).signum());
	}

	@Test
	void failedPhysicalWriteRollsBackStockHeaderEffectsAndOriginalReceipt() {
		var created = service.execute(create());
		identity("F2", "target", 1);
		service.execute(command(Action.CONFIRM, created.billId(), 1, "WB"));
		identity("F1", "source", 1);
		var shipping = command(Action.SHIP, created.billId(), 2, "WA");
		failFlow = true;
		assertThrows(CheckedException.class, () -> service.execute(shipping));
		assertEquals(6, db.queryForObject("SELECT quantity FROM stock WHERE id=10", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM flow", Integer.class));
		assertEquals("READY_TO_SHIP", service.detail("WA", created.billId()).bill().getState());
		assertEquals("NOT_FOUND", service.lookup("WA", shipping.getRequestId()).status());
	}

	@Test
	void abandoningUnknownOriginalPreventsLateCreationAndDoesNotCreateABusinessBill() {
		var original = create();
		var abandoned = service.abandon(original, "取消录入");
		assertEquals("ABANDONED", abandoned.status());
		assertEquals(abandoned, service.execute(original));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_org_transfer_bill", Integer.class));
		assertEquals(6, db.queryForObject("SELECT sum(quantity) FROM stock", Integer.class));
	}

	@Test
	void otherTenantFactoryWarehouseActorPayloadAndStaleVersionCannotClaimTheOriginal() {
		var original = create();
		var created = service.execute(original);
		identity("F3", "intruder", 1);
		assertThrows(CheckedException.class, () -> service.detail("WA", created.billId()));
		identity("F1", "source", 2);
		assertThrows(CheckedException.class, () -> service.detail("WA", created.billId()));
		identity("F1", "intruder", 1);
		assertThrows(CheckedException.class, () -> service.lookup("WA", original.getRequestId()));
		identity("F1", "source", 1);
		original.setReason("改写内容");
		assertThrows(CheckedException.class, () -> service.execute(original));
		identity("F2", "target", 1);
		assertThrows(CheckedException.class, () -> service.execute(command(Action.CONFIRM, created.billId(), 0, "WB")));
	}

	@Test
	void wrongReceivingMaterialUnitBlocksBeforeAnyShippingOrStockWrites() {
		var created = service.execute(create());
		identity("F2", "target", 1);
		wrongUnit = true;
		assertThrows(CheckedException.class, () -> service.execute(command(Action.CONFIRM, created.billId(), 1, "WB")));
		assertEquals(6, db.queryForObject("SELECT sum(quantity) FROM stock", Integer.class));
	}

	@Test
	void requiredContainerAndForeignTenantLocationCannotCreateReceivingStock() {
		var issued = ship();
		identity("F2", "target", 1);
		containerRequired = true;
		assertThrows(CheckedException.class,
				() -> service.execute(physical(Action.RECEIVE, issued.billId(), 3, "WB", "1")));
		containerRequired = false;
		foreignLocation = true;
		assertThrows(CheckedException.class,
				() -> service.execute(physical(Action.RECEIVE, issued.billId(), 3, "WB", "1")));
		assertEquals(2, db.queryForObject("SELECT sum(quantity) FROM stock", Integer.class));
		assertEquals(3, service.detail("WB", issued.billId()).bill().getVersion());
	}

	@Test
	void contextChangedAfterPreparingCommandCannotWriteAnotherFactory() {
		var original = create();
		identity("F2", "target", 1);
		assertThrows(CheckedException.class, () -> service.execute(original));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_org_transfer_bill", Integer.class));
	}

	@Test
	void concurrentReceiptsAtSameVersionHaveExactlyOneWinner() throws Exception {
		var issued = ship();
		identity("F2", "target", 1);
		var one = physical(Action.RECEIVE, issued.billId(), 3, "WB", "3");
		var two = physical(Action.RECEIVE, issued.billId(), 3, "WB", "3");
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var start = new java.util.concurrent.CountDownLatch(1);
			var tasks = List.of(one, two).stream().map(command -> executor.submit(() -> {
				identity("F2", "target", 1);
				start.await();
				try {
					service.execute(command);
					return true;
				} catch (CheckedException expected) {
					return false;
				} finally {
					clear();
				}
			})).toList();
			start.countDown();
			int successful = 0;
			for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) successful++;
			assertEquals(1, successful);
		}
		assertEquals(3, db.queryForObject("SELECT sum(quantity) FROM stock WHERE factory='F2'", Integer.class));
		assertEquals(2, db.queryForObject("SELECT count(*) FROM flow", Integer.class));
		assertEquals(4, service.detail("WB", issued.billId()).bill().getVersion());
	}

	@Test
	void serialShippingAndReceiptRequireOriginalScanAndHistoricalReceivingSurvivesPolicyStop() {
		serialManaged = true;
		db.update("UPDATE stock SET quantity=1 WHERE id=10");
		var command = create();
		command.getLines().get(0).setQuantity(BigDecimal.ONE);
		var created = service.execute(command);
		identity("F2", "target", 1);
		service.execute(command(Action.CONFIRM, created.billId(), 1, "WB"));
		identity("F1", "source", 1);
		var ship = command(Action.SHIP, created.billId(), 2, "WA");
		assertThrows(CheckedException.class, () -> service.execute(ship));
		ship.getLines().get(0).setBarcode("SERIAL-1");
		service.execute(ship);
		db.update("UPDATE mom_wms.wms_org_transfer_policy SET enabled=false WHERE tenant_id=1");
		identity("F2", "target", 1);
		var receive = physical(Action.RECEIVE, created.billId(), 3, "WB", "1");
		receive.getLines().get(0).setBarcode("WRONG");
		assertThrows(CheckedException.class, () -> service.execute(receive));
		receive.getLines().get(0).setBarcode("SERIAL-1");
		assertEquals("COMPLETED", service.execute(receive).state());
		assertEquals(1, db.queryForObject("SELECT sum(quantity) FROM stock", Integer.class));
	}

	@Test
	void moreThanTwoHundredRowsOfOneMaterialRemainTraversableAndExactlyScannable() {
		for (int i = 1; i <= 201; i++) {
			var row = sourceStock(BigDecimal.ONE);
			row.setId((long) i);
			row.setMatBarcode("SN-" + i);
			db.update("INSERT INTO candidate VALUES(?,1,'F1','WA',0,'M1',?,?)", i, row.getMatBarcode(),
					JSONUtil.toJsonStr(row));
		}
		var scope = new Scope(1L, "F1", "WA", "source", 1L);
		var first = stockFacade.candidates(scope, "M1", "", null);
		var second = stockFacade.candidates(scope, "M1", "", first.nextCursor());
		var third = stockFacade.candidates(scope, "M1", "", second.nextCursor());
		var seen = new HashSet<Long>();
		for (var page : List.of(first, second, third)) for (var row : page.records()) assertTrue(seen.add(row.getId()));
		assertEquals(201, seen.size());
		assertNull(third.nextCursor());
		var scanned = stockFacade.candidates(scope, "M1", "SN-1", null);
		assertEquals(List.of(1L), scanned.records().stream().map(WmsLiveStockEntity::getId).toList());
	}

}
