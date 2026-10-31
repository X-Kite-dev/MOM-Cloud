package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionView;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Query;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.repository.WmsFinishedGoodsCorrectionSubmissionRepository;
import com.kitex.mom.wms.controller.app.AppFinishedGoodsCorrectionSubmissionController;
import com.kitex.mom.wms.service.impl.WmsFinishedGoodsReceiptCorrectionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class WmsFinishedGoodsCorrectionSubmissionTest {

	private JdbcTemplate db;
	private WmsFinishedGoodsCorrectionSubmissionService service;
	private PermissionService permissions;

	@BeforeEach
	void setup() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:fg-submission-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		db.execute("CREATE TABLE mom_wms.wms_finished_goods_receipt_correction (id bigint,tenant_id bigint,"
				+ "factory_code varchar,request_id varchar,request_fingerprint varchar,notice_id bigint,"
				+
				"correction_no varchar,status varchar,reason varchar,total_quantity numeric(20,8),stock_line_count int,"
				+ "applicant_code varchar,applied_time timestamp,qms_decision_reversal_id bigint,"
				+ "qms_decision_authorization_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_wms.wms_finished_goods_receipt_correction_detail (id bigint,tenant_id bigint,"
				+ "factory_code varchar,correction_id bigint,stock_id bigint,quantity numeric(20,8),"
				+ "warehouse_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_wms.wms_finished_goods_receipt_correction_operation (id bigint,tenant_id bigint,"
				+ "factory_code varchar,correction_id bigint,command_id varchar,command_fingerprint varchar,"
				+
				"operation_type varchar,operator_code varchar,operation_time timestamp,after_status varchar,del_flag varchar)");
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(true);
		service = new WmsFinishedGoodsCorrectionSubmissionService(
				new WmsFinishedGoodsCorrectionSubmissionRepository(db), permissions);
		scope("operator", "F1", 1L);
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void findsOriginalSubmissionWithoutReadingCurrentStockOrNotice() throws Exception {
		var command = command();
		insert(command);
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET status='EXECUTED'");

		var result = service.lookup(new Query("W1", command));

		assertEquals("REGISTERED", result.status());
		assertEquals("9007199254740993", result.correctionId());
		assertEquals("FGR-001", result.correctionNo());
		assertEquals("2.125", result.quantity());
		assertTrue(result.requestPayload().contains("9007199254740995"));
		assertEquals(1,
				db.queryForObject("SELECT count(*) FROM mom_wms.wms_finished_goods_receipt_correction", Integer.class));
	}

	@Test
	void absenceIsOnlyAnObservationAndDoesNotCreateAnApplication() {
		var result = service.lookup(new Query("W1", command()));

		assertEquals("NOT_CONFIRMED", result.status());
		assertNull(result.requestPayload());
		assertNull(result.correctionId());
	}

	@Test
	void refusesAnotherApplicantWarehouseFactoryTenantAndSkippedContexts() throws Exception {
		insert(command());
		scope("someone", "F1", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
		scope("operator", "F1", 1L);
		when(permissions.hasPermission("wms_bill_add_in_product_W2")).thenReturn(true);
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W2", command())));
		scope("operator", "F2", 1L);
		assertEquals("NOT_CONFIRMED", service.lookup(new Query("W1", command())).status());
		scope("operator", "F1", 2L);
		assertEquals("NOT_CONFIRMED", service.lookup(new Query("W1", command())).status());
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
	}

	@Test
	void refusesPermissionBeforeAnyRepositoryRead() {
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(false);
		db.execute("DROP TABLE mom_wms.wms_finished_goods_receipt_correction");

		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
	}

	@Test
	void detectsChangedOriginalCommandAndIncompleteFrozenEvidence() throws Exception {
		insert(command());
		var changed = command();
		changed.getStockSelections().get(0).setQuantity(BigDecimal.ONE);
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", changed)));
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction_detail SET quantity=1");
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction_detail SET quantity=2.125");
		db.update("DELETE FROM mom_wms.wms_finished_goods_receipt_correction_operation");
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
	}

	@Test
	void refusesLegacyFingerprintAndMismatchedOriginalOperatorOrAuthorization() throws Exception {
		insert(command());
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET request_fingerprint=NULL");
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET request_fingerprint=?",
				fingerprint(command()));
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction_operation SET operator_code='other'");
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction_operation SET operator_code='operator'");
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET qms_decision_reversal_id=17");
		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
	}

	@Test
	void httpLookupCarriesTheOriginalCommandInTheBodyAndReturnsStringIdentifiers() throws Exception {
		insert(command());
		var json = new ObjectMapper();
		var http = MockMvcBuilders.standaloneSetup(new AppFinishedGoodsCorrectionSubmissionController(service)).build();
		var response = http.perform(post("/finishedGoodsReceiptCorrection/submissions/lookup")
						.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new Query("W1", command()))))
				.andReturn().getResponse();

		assertEquals(200, response.getStatus());
		var body = json.readTree(response.getContentAsString()).path("data");
		assertEquals("REGISTERED", body.path("status").asText());
		assertTrue(body.path("correctionId").isTextual());
		assertEquals("9007199254740993", body.path("correctionId").asText());
	}

	@Test
	void corruptStatusCannotBecomeARegisteredReceiptOrAnUnhandledNullFailure() throws Exception {
		insert(command());
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET status=NULL");

		assertThrows(CheckedException.class, () -> service.lookup(new Query("W1", command())));
	}

	@Test
	void previewSerializesStockQuantitiesAsDecimalTextBeforeAnyJavascriptParsing() throws Exception {
		var stock = new WmsFinishedGoodsReceiptCorrectionView.StockOption();
		stock.setQuantity(new BigDecimal("1000000000"));
		stock.setProductionReservedQuantity(new BigDecimal("0.00000001"));
		stock.setSalesReservedQuantity(BigDecimal.ZERO);
		stock.setSelectableQuantity(new BigDecimal("999999999.99999999"));
		var json = new ObjectMapper();
		var wire = json.readTree(json.writeValueAsString(stock));

		assertTrue(wire.path("selectableQuantity").isTextual());
		assertEquals("999999999.99999999", wire.path("selectableQuantity").asText());
		assertTrue(wire.path("quantity").isTextual());
		assertTrue(wire.path("productionReservedQuantity").isTextual());
		assertEquals("0.00000001", wire.path("productionReservedQuantity").asText());
		assertTrue(wire.path("salesReservedQuantity").isTextual());
	}

	@Test
	void legacyApplyCannotAcknowledgeAnotherApplicantsOriginalRequest() throws Exception {
		var apply = replayService();
		scope("another-operator", "F1", 1L);

		assertThrows(CheckedException.class, () -> apply.apply(command()));
	}

	@Test
	void legacyApplyRequiresAnActualAuthenticatedContextBeforeReplay() throws Exception {
		var apply = replayService();
		SecurityContextHolder.clearContext();
		assertThrows(CheckedException.class, () -> apply.apply(command()));
		scope("operator", "F1", 1L);
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> apply.apply(command()));
	}

	@Test
	void legacyApplyStillReturnsTheSameActorsProvenOriginalApplication() throws Exception {
		var apply = replayService();

		assertEquals("operator", apply.apply(command()).getApplicantCode());
	}

	@SuppressWarnings("unchecked")
	private WmsFinishedGoodsReceiptCorrectionServiceImpl replayService() throws Exception {
		var apply = mock(WmsFinishedGoodsReceiptCorrectionServiceImpl.class, CALLS_REAL_METHODS);
		var row = new WmsFinishedGoodsReceiptCorrectionEntity();
		row.setTenantId(1L);
		row.setFactoryCode("F1").setApplicantCode("operator").setNoticeId(100L)
				.setRequestFingerprint(fingerprint(command())).setReason(command().getReason());
		doReturn(row).when(apply).getOne(any(Wrapper.class), eq(false));
		return apply;
	}

	private void insert(WmsFinishedGoodsReceiptCorrectionCommand command) throws Exception {
		String fingerprint = fingerprint(command);
		LocalDateTime time = LocalDateTime.of(2026, 10, 31, 8, 0);
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction VALUES "
						+ "(9007199254740993,1,'F1',?,?,100,'FGR-001','SUBMITTED',?,2.125,1,'operator',?,NULL,NULL,'0')",
				command.getRequestId(), fingerprint, command.getReason(), time);
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_detail VALUES "
				+ "(1,1,'F1',9007199254740993,9007199254740995,2.125,'W1','0')");
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_operation VALUES "
						+ "(1,1,'F1',9007199254740993,?,?,'SUBMIT','operator',?,'SUBMITTED','0')",
				command.getRequestId(), fingerprint, time);
	}

	private WmsFinishedGoodsReceiptCorrectionCommand command() {
		var command = new WmsFinishedGoodsReceiptCorrectionCommand();
		command.setRequestId("FG-CORRECTION-ORIGINAL-0001");
		command.setNoticeId(100L);
		command.setReason("批次核对\n现场确认");
		var selection = new WmsFinishedGoodsReceiptCorrectionCommand.StockSelection();
		selection.setStockId(9007199254740995L);
		selection.setQuantity(new BigDecimal("2.125"));
		command.setStockSelections(List.of(selection));
		return command;
	}

	private String fingerprint(WmsFinishedGoodsReceiptCorrectionCommand command) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(JSONUtil.toJsonStr(command).getBytes(StandardCharsets.UTF_8)));
	}

	private void scope(String username, String factory, Long tenant) {
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		MomUser actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn(username);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));
	}
}
