package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.controller.app.AppInboundReceiptController;
import com.kitex.mom.wms.controller.app.AppOutboundReceiptController;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsMobileInboundReceiptMapper;
import com.kitex.mom.wms.mapper.WmsMobileOutboundReceiptMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class WmsMobileReceiptHistoryTest {

	private JdbcTemplate db;
	private MockMvc http;
	private PermissionService permissions;
	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:receipt-history-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		for (String direction : new String[]{"inbound", "outbound"}) {
			String sql = Files.readString(Path.of("../database/wms-mobile-" + direction + "-receipt-pg.sql"));
			int start = sql.indexOf("CREATE TABLE");
			db.execute(sql.substring(start, sql.indexOf(';', start)));
		}
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsMobileInboundReceiptMapper.class);
		configuration.addMapper(WmsMobileOutboundReceiptMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		var sql = new SqlSessionTemplate(factory.getObject());
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_notice_inout_purchase_W1")).thenReturn(true);
		when(permissions.hasPermission("wms_notice_inout_other_out_W1")).thenReturn(true);
		var inbound = new WmsMobileInboundReceiptService(sql.getMapper(WmsMobileInboundReceiptMapper.class),
				mock(WmsInNoticeMapper.class), mock(WmsInNoticeDetailMapper.class), permissions, json, mock(WmsWarehouseBusinessGuard.class));
		var outbound = new WmsMobileOutboundReceiptService(sql.getMapper(WmsMobileOutboundReceiptMapper.class),
				mock(WmsOutNoticeMapper.class), mock(WmsOutNoticeDetailMapper.class), mock(WmsLiveStockMapper.class),
				permissions, json, mock(WmsWarehouseBusinessGuard.class));
		http = MockMvcBuilders.standaloneSetup(new AppInboundReceiptController(inbound),
				new AppOutboundReceiptController(outbound)).build();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		MomUser actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("operator");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@ValueSource(strings = {"inbound", "outbound"})
	void historyIsScopedToTheActualPersonAndDoesNotRequireRemainingNoticeOrStock(String direction) throws Exception {
		insert(direction, "own", 1, "F1", "operator", "W1", type(direction), "CONFIRMED", 0);
		insert(direction, "tenant", 2, "F1", "operator", "W1", type(direction), "CONFIRMED", 0);
		insert(direction, "factory", 1, "F2", "operator", "W1", type(direction), "CONFIRMED", 0);
		insert(direction, "person", 1, "F1", "someone", "W1", type(direction), "CONFIRMED", 0);
		insert(direction, "warehouse", 1, "F1", "operator", "W2", type(direction), "CONFIRMED", 0);
		insert(direction, "old", 1, "F1", "operator", "W1", type(direction), "CONFIRMED", 31);
		insert(direction, "pending", 1, "F1", "operator", "W1", type(direction), "PENDING", 0);

		JsonNode page = history(direction, "", "", 1);

		assertEquals(1, page.path("records").size());
		assertEquals("history-request-own", page.path("records").get(0).path("requestId").asText());
		assertFalse(page.path("hasMore").asBoolean());
		assertEquals(7,
				db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_" + direction + "_receipt", Integer.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"inbound", "outbound"})
	void exactReferenceAndStatusFiltersDoNotMatchOtherRequests(String direction) throws Exception {
		insert(direction, "confirmed", 1, "F1", "operator", "W1", type(direction), "CONFIRMED", 0);
		insert(direction, "cancelled", 1, "F1", "operator", "W1", type(direction), "CANCELLED", 0);

		assertEquals(1, history(direction, "BILL-confirmed", "CONFIRMED", 1).path("records").size());
		assertEquals(1, history(direction, "history-request-cancelled", "CANCELLED", 1).path("records").size());
		assertEquals(0, history(direction, "%", "", 1).path("records").size());
		assertEquals(0, history(direction, "BILL-confirmed", "CANCELLED", 1).path("records").size());
	}

	@ParameterizedTest
	@ValueSource(strings = {"inbound", "outbound"})
	void historyReturnsTwentyRowsAndAnExplicitNextPage(String direction) throws Exception {
		for (int i = 0; i < 21; i++) {
			insert(direction, String.format("%02d", i), 1, "F1", "operator", "W1", type(direction), "CONFIRMED", 0);
		}

		JsonNode first = history(direction, "", "", 1);
		JsonNode second = history(direction, "", "", 2);

		assertEquals(20, first.path("records").size());
		assertTrue(first.path("hasMore").asBoolean());
		assertEquals(1, second.path("records").size());
		assertFalse(second.path("hasMore").asBoolean());
		assertNotEquals(first.path("records").get(19).path("requestId"),
				second.path("records").get(0).path("requestId"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"inbound", "outbound"})
	void invalidScopePermissionAndPaginationFailBeforeReadingHistory(String direction) {
		when(permissions.hasPermission("wms_notice_inout_" + type(direction) + "_W1")).thenReturn(false);
		assertThrows(Exception.class, () -> history(direction, "", "", 1));
		when(permissions.hasPermission("wms_notice_inout_" + type(direction) + "_W1")).thenReturn(true);
		assertThrows(Exception.class, () -> history(direction, "", "", 0));
		assertThrows(Exception.class, () -> history(direction, "", "PENDING", 1));
		TenantContextHolder.setTenantSkip();
		assertThrows(Exception.class, () -> history(direction, "", "", 1));
	}

	@ParameterizedTest
	@ValueSource(strings = {"inbound", "outbound"})
	void businessEntryDoesNotIncludeOtherBusinessReceipts(String direction) throws Exception {
		insert(direction, "different", 1, "F1", "operator", "W1", "sales", "CONFIRMED", 0);
		if ("outbound".equals(direction)) {
			insert(direction, "retail", 1, "F1", "operator", "W1", "other_retail", "CONFIRMED", 0);
			insert(direction, "outsource", 1, "F1", "operator", "W1", "other_outsource", "CONFIRMED", 0);
		}

		assertEquals("outbound".equals(direction) ? 2 : 0, history(direction, "", "", 1).path("records").size());
	}

	private JsonNode history(String direction, String reference, String status, int page) throws Exception {
		String base = "inbound".equals(direction) ? "inBill" : "outBill";
		var response = http.perform(get("/app/" + base + "/receipts/history")
						.param("warehouseCode", "W1").param("billType", type(direction))
						.param("reference", reference).param("status", status).param("page", Integer.toString(page)))
				.andReturn().getResponse();
		assertEquals(200, response.getStatus(), response.getContentAsString());
		JsonNode value = json.readTree(response.getContentAsString());
		assertEquals(0, value.path("code").asInt());
		return value.path("data");
	}

	private String type(String direction) {
		return "inbound".equals(direction) ? "purchase" : "other_out";
	}

	private void insert(String direction, String suffix, long tenant, String factory, String operator,
	                    String warehouse, String type, String status, int ageDays) {
		boolean pending = "PENDING".equals(status);
		String stockColumn = "outbound".equals(direction) ? ",source_stock_id" : "";
		String stockValue = "outbound".equals(direction) ? ",300" : "";
		db.update("INSERT INTO mom_wms.wms_mobile_" + direction + "_receipt "
						+ "(tenant_id,request_id,factory_code,operator_code,notice_id,notice_detail_id,warehouse_code,"
						+
						"bill_type,request_payload,request_fingerprint,requested_quantity,status,bill_no,completed_time,cancellation_reason"
						+ stockColumn + ") VALUES (?,?,?,?,100,200,?,?,'{}',?,2,?,?,?,?" + stockValue + ")",
				tenant, "history-request-" + suffix, factory, operator, warehouse, type, "a".repeat(64), status,
				"CONFIRMED".equals(status) ? "BILL-" + suffix : null,
				pending ? null : LocalDateTime.now().minusDays(ageDays),
				"CANCELLED".equals(status) ? "现场取消" : null);
	}
}
