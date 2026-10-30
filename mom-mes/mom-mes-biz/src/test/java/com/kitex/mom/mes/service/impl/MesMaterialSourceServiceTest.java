package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.dto.MesMaterialSourceDTO.*;
import com.kitex.mom.mes.dto.MesWarehouseFeedBirthDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesMaterialSourceMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real scoped SQL; original birth verification and current stock eligibility remain their own domain boundaries. */
class MesMaterialSourceServiceTest {
	private JdbcTemplate db;
	private SqlSession sql;
	private MesMaterialSourceService service;
	private MesWarehouseLiveStockService stocks;
	private MesWarehouseFeedBirthService births;
	private MesMaterialSourceMapper mapper;

	@BeforeEach
	void setup() {
		actor("F1");
		var ds = new DriverManagerDataSource("jdbc:h2:mem:material-source-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		ds.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(ds);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mom_mes.mes_task_arrange(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,task_code varchar,produce_task_code varchar,route_code varchar,source_route_node_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,bom_snapshot varchar,process_route_snapshot varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_produce_task(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,task_code varchar,route_code varchar,source_route_node_code varchar,process_route_snapshot varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_warehouse_feed(id bigint primary key,tenant_id bigint,factory_code varchar,task_code varchar,produce_task_code varchar,arrange_code varchar,requirement_mat_code varchar,mat_code varchar,feed_num numeric(30,12),used_num numeric(30,12),mat_unit varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_warehouse_live_stock(id bigint primary key,tenant_id bigint,factory_code varchar,mat_code varchar,mat_unit varchar,num numeric(30,12),mat_batch varchar,del_flag varchar)");
		String route = "[{\"routeNodeCode\":\"N1\",\"processCode\":\"PROC\",\"routeCode\":\"PROC\",\"mdmProcessProcedureMatList\":[{\"id\":\"123\",\"materialCode\":\"M1\",\"unit\":\"kg\",\"quantity\":1}]}]";
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(1,1,'F1','A1','T1','P1','PROC','N1','0')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(2,1,'F1','T1','[]',?,'0')", route);
		db.update("INSERT INTO mom_mes.mes_produce_task VALUES(3,1,'F1','P1','T1','PROC','N1',?,'0')", route);
		db.update("INSERT INTO mom_mes.mes_warehouse_feed VALUES(9007199254740993,1,'F1','T1','P1','A1','M1','M1',1.123456789012,NULL,'kg','0')");
		db.update("INSERT INTO mom_mes.mes_warehouse_live_stock VALUES(12,1,'F1','M1','kg',2,'B1','0')");
		var config = new Configuration();
		config.setMapUnderscoreToCamelCase(true);
		config.setEnvironment(new Environment("material", new JdbcTransactionFactory(), ds));
		config.addMapper(MesMaterialSourceMapper.class);
		sql = new SqlSessionFactoryBuilder().build(config).openSession(true);
		mapper = sql.getMapper(MesMaterialSourceMapper.class);
		stocks = mock(MesWarehouseLiveStockService.class);
		births = mock(MesWarehouseFeedBirthService.class);
		when(births.evidence(9007199254740993L)).thenReturn(new MesWarehouseFeedBirthDTO("HISTORICAL_UNPROVEN", null, List.of()));
		when(stocks.previewOutCandidates(any())).thenReturn(List.of(mapper.stock(1L, "F1", 12L)));
		service = new MesMaterialSourceService(MesFactoryAccessTestSupport.authorized(), mapper, stocks, births);
	}

	@AfterEach
	void cleanup() {
		if (sql != null) {
			sql.close();
		}
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void originalFeedKeepsLargeIdentityDecimalPrecisionAndUnknownUsedQuantity() {
		var result = service.inspect(feed());

		assertEquals("9007199254740993", result.getSource().get("id"));
		assertEquals("1.123456789012", result.getSource().get("feedNum"));
		assertNull(result.getSource().get("usedNum"));
		assertEquals("HISTORICAL_UNPROVEN", result.getLineage().get("status"));
		assertFalse(result.getDataGaps().isEmpty());

		db.update("UPDATE mom_mes.mes_warehouse_feed SET feed_num=0.000000000001");
		sql.clearCache();
		assertEquals("0.000000000001", service.inspect(feed()).getSource().get("feedNum"));
	}

	@Test
	void sameIdFromAnotherFactoryOrAnotherDispatchIsNotDisclosed() {
		actor("F2");
		assertThrows(CheckedException.class, () -> service.inspect(feed()));
		actor("F1");
		db.update("UPDATE mom_mes.mes_warehouse_feed SET arrange_code='A10'");
		assertThrows(CheckedException.class, () -> service.inspect(feed()));
	}

	@Test
	void stockMustBeCurrentEligibleForExactOriginalRequirement() {
		var result = service.inspect(stock());
		assertEquals("12", result.getSource().get("id"));
		assertEquals("M1", result.getRequirementMaterialCode());
		assertEquals("CURRENT_CANDIDATE", result.getLineage().get("status"));
		when(stocks.previewOutCandidates(any())).thenReturn(List.of());
		assertThrows(CheckedException.class, () -> service.inspect(stock()));
		assertThrows(CheckedException.class, () -> service.inspect(stock().setRequirementMaterialCode("M10")));
	}

	@Test
	void stockCandidatesAreRebuiltFromServerScopeRatherThanClientSuppliedStockMetadata() {
		service.inspect(stock());
		var query = org.mockito.ArgumentCaptor.forClass(com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO.class);
		verify(stocks).previewOutCandidates(query.capture());
		assertEquals("P1", query.getValue().getProduceTaskCode());
		assertEquals("A1", query.getValue().getBillNo());
		assertEquals("M1", query.getValue().getRequirementMatCode());
		assertEquals("F1", query.getValue().getFactoryCode());
	}

	@Test
	void verifiedBirthIsPagedWithoutChangingOriginalSourceCountOrQuantity() {
		var birth = new MesWarehouseFeedBirthEntity();
		birth.setId(31L);
		birth.setTenantId(1L);
		birth.setFactoryCode("F1");
		birth.setFeedId(9007199254740993L);
		birth.setTaskId(2L);
		birth.setArrangeId(1L);
		birth.setSourceCount(3);
		birth.setQuantity(new BigDecimal("1.123456789012"));
		birth.setUnit("kg");
		var rows = new ArrayList<MesWarehouseFeedBirthLineEntity>();
		for (long id = 1; id <= 3; id++) {
			var row = new MesWarehouseFeedBirthLineEntity();
			row.setId(id);
			row.setTenantId(1L);
			row.setFactoryCode("F1");
			row.setBirthId(31L);
			rows.add(row);
		}
		when(births.evidence(9007199254740993L)).thenReturn(new MesWarehouseFeedBirthDTO("RECORDED", birth, rows));
		var result = service.inspect(feed().setLineCurrent(2).setLineSize(2));

		assertEquals(3, result.getLineage().get("total"));
		assertEquals(1, ((List<?>) result.getLineage().get("sources")).size());
		assertEquals(2, result.getLineage().get("current"));
	}

	@Test
	void mismatchedBirthAndInvalidInputCannotBeTreatedAsMissingHistoricalEvidence() {
		when(births.evidence(9007199254740993L)).thenThrow(new CheckedException("corrupt-original"));
		assertThrows(CheckedException.class, () -> service.inspect(feed()));
		assertThrows(CheckedException.class, () -> service.inspect(feed().setSourceId("1 OR 1=1")));
		assertThrows(CheckedException.class, () -> service.inspect(stock().setRequirementMaterialCode(null)));
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.inspect(feed()));
	}

	private Query feed() {
		return new Query().setArrangeId("1").setSourceKind("FEED").setSourceId("9007199254740993");
	}

	private Query stock() {
		return new Query().setArrangeId("1").setSourceKind("STOCK").setSourceId("12").setRequirementMaterialCode("M1");
	}

	private void actor(String factory) {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode(factory);
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(7L);
		when(user.getTenantId()).thenReturn(1L);
		when(user.getUsername()).thenReturn("worker-1");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
	}
}
