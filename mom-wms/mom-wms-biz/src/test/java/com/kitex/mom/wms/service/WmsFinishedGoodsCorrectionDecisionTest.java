package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.repository.WmsFinishedGoodsCorrectionDecisionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsFinishedGoodsCorrectionDecisionTest {

	private JdbcTemplate db;
	private WmsFinishedGoodsCorrectionDecisionService service;
	private PermissionService permissions;

	@BeforeEach
	void setup() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:fg-decision-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		db.execute("CREATE TABLE mom_wms.wms_finished_goods_receipt_correction(id bigint,tenant_id bigint,"
				+ "factory_code varchar,notice_id bigint,correction_no varchar,status varchar,del_flag varchar,stock_line_count int)");
		db.execute("CREATE TABLE mom_wms.wms_finished_goods_receipt_correction_detail(tenant_id bigint,"
				+ "factory_code varchar,correction_id bigint,warehouse_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_wms.wms_finished_goods_receipt_correction_operation(tenant_id bigint,"
				+ "factory_code varchar,correction_id bigint,command_id varchar,command_fingerprint varchar,"
				+ "operation_type varchar,before_status varchar,after_status varchar,content varchar,operator_code varchar,"
				+ "operation_time timestamp,del_flag varchar)");
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_bill_review_in_product_W1")).thenReturn(true);
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(true);
		service = new WmsFinishedGoodsCorrectionDecisionService(
				new WmsFinishedGoodsCorrectionDecisionRepository(db), permissions);
		scope("operator", 1L, "F1");
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction VALUES(300,1,'F1',100,'FGR-300','SUBMITTED','0',1)");
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_detail VALUES(1,'F1',300,'W1','0')");
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void missingOperationDoesNotProveNonAcceptance() {
		assertEquals("NOT_CONFIRMED", service.lookup(query("WITHDRAW")).status());
	}

	@Test
	void withdrawalRequiresItsExactOriginalBodyAndImmutableActor() throws Exception {
		insert("WITHDRAW", "WITHDRAWN");
		var result = service.lookup(query("WITHDRAW"));

		assertEquals("REGISTERED", result.status());
		assertEquals("WITHDRAWN", result.afterStatus());
		assertTrue(result.requestPayload().contains("核对\\n撤回"));
		assertThrows(CheckedException.class, () -> service.lookup(new Query("WITHDRAW", 300L, 100L,
				"W1", "FGR-DECISION-0001", "不同原因")));
		scope("another", 1L, "F1");
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
	}

	@Test
	void rejectedOperationUsesRejectReasonRatherThanWithdrawalReason() throws Exception {
		insert("REJECT", "REJECTED");

		assertEquals("REGISTERED", service.lookup(query("REJECT")).status());
	}

	@Test
	void approvalCanConfirmEvidenceChangedWithoutClaimingPhysicalExecution() throws Exception {
		insert("STALE", "STALE");

		assertEquals("STALE", service.lookup(query("APPROVE")).afterStatus());
	}

	@Test
	void approvalRequiresServerDefinedOriginalNumber() throws Exception {
		insert("APPROVE_EXECUTE", "EXECUTED");

		assertEquals("REGISTERED", service.lookup(query("APPROVE")).status());
		assertThrows(CheckedException.class, () -> service.lookup(new Query("APPROVE", 300L, 100L,
				"W1", "OTHER-APPROVAL", "批准执行")));
	}

	@Test
	void wrongScopeAndWarehouseNeverReturnAnAcknowledgement() throws Exception {
		insert("WITHDRAW", "WITHDRAWN");
		scope("operator", 2L, "F1");
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
		scope("operator", 1L, "F2");
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
		scope("operator", 1L, "F1");
		assertThrows(CheckedException.class, () -> service.lookup(new Query("WITHDRAW", 300L, 100L,
				"W2", "FGR-DECISION-0001", "核对\n撤回")));
		reset(permissions);
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
	}

	@Test
	void duplicateOrDeletedOrUnprovenTerminalOperationCannotClearPending() throws Exception {
		insert("WITHDRAW", "WITHDRAWN");
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_operation SELECT * FROM mom_wms.wms_finished_goods_receipt_correction_operation");
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
		db.update("DELETE FROM mom_wms.wms_finished_goods_receipt_correction_operation");
		insert("WITHDRAW", "WITHDRAWN");
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction_operation SET del_flag='1'");
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction_operation SET del_flag='0',after_status='EXECUTED'");
		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
	}

	@Test
	void partiallyLostFrozenDetailsCannotConfirmTheOriginalOperation() throws Exception {
		insert("WITHDRAW", "WITHDRAWN");
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET stock_line_count=2");

		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
	}

	@Test
	void detailOutsideOriginalFactoryCannotReplaceMissingWarehouseEvidence() throws Exception {
		insert("WITHDRAW", "WITHDRAWN");
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET stock_line_count=2");
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_detail VALUES(1,'F2',300,'W2','0')");

		assertThrows(CheckedException.class, () -> service.lookup(query("WITHDRAW")));
	}

	@Test
	void completeLargeLegacyApplicationCanStillConfirmItsDecision() throws Exception {
		insert("WITHDRAW", "WITHDRAWN");
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET stock_line_count=1001");
		for (int index = 1; index < 1001; index++) {
			db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_detail VALUES(1,'F1',300,'W1','0')");
		}

		assertEquals("REGISTERED", service.lookup(query("WITHDRAW")).status());
	}

	private Query query(String action) {
		return new Query(action, 300L, 100L, "W1", action.equals("APPROVE") ? "APPROVE:300" : "FGR-DECISION-0001",
				action.equals("APPROVE") ? "批准执行" : "核对\n撤回");
	}

	private void insert(String type, String state) throws Exception {
		boolean approval = type.equals("STALE") || type.equals("APPROVE_EXECUTE");
		String fingerprint;
		if (approval) {
			fingerprint = hash("APPROVE|300");
		} else {
			var command = new WmsFinishedGoodsReceiptCorrectionCommand();
			command.setRequestId("FGR-DECISION-0001");
			if (type.equals("WITHDRAW")) {
				command.setReason("核对\n撤回");
			} else {
				command.setRejectReason("核对\n撤回");
			}
			fingerprint = hash(JSONUtil.toJsonStr(command));
		}
		db.update("UPDATE mom_wms.wms_finished_goods_receipt_correction SET status=?", state);
		db.update("INSERT INTO mom_wms.wms_finished_goods_receipt_correction_operation VALUES "
						+ "(1,'F1',300,?,?,?,'SUBMITTED',?,'核对','operator',CURRENT_TIMESTAMP,'0')",
				approval ? "APPROVE:300" : "FGR-DECISION-0001", fingerprint, type, state);
	}

	private String hash(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private void scope(String username, Long tenant, String factory) {
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn(username);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
	}
}
