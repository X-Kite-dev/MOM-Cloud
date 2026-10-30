package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeTemplateService;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.utils.PrintTemplateFingerprint;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.*;
import com.kitex.mom.mes.entity.MesDispatchHistoryNoteEntity;
import com.kitex.mom.mes.mapper.MesDispatchHistoryMapper;
import com.kitex.mom.mes.mapper.MesExecutionCommandCancellationMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real notes SQL and Spring rollback; only remote services and PG advisory primitive are substituted. */
class MesDispatchHistoryServiceTest {
	private JdbcTemplate db;
	private MesDispatchHistoryService service;
	private RemoteBarcodeTemplateService templates;
	private RemotePrintService prints;
	private PrintTemplateEntity layout;
	private boolean rejectNoteWrite;

	@BeforeEach
	void setup() throws Exception {
		actor(7L, "F1");
		var source = new DriverManagerDataSource("jdbc:h2:mem:history-service-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_mes");
		db.execute("CREATE TABLE mutex(id int primary key)");
		db.update("INSERT INTO mutex VALUES(1)");
		db.execute("CREATE TABLE mom_mes.mes_task_arrange(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,task_code varchar,produce_task_code varchar,worker_code varchar,status varchar,source_route_node_code varchar,route_code varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_task(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,status varchar,engineering_release_id bigint,engineering_content_hash varchar,engineering_version_no integer,engineering_bundle_snapshot varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_produce_task(id bigint primary key,tenant_id bigint,factory_code varchar,code varchar,task_code varchar,route_code varchar,source_route_node_code varchar,process_route_code varchar,process_route_snapshot varchar,del_flag varchar)");
		db.execute("CREATE TABLE mom_mes.mes_dispatch_history_note(id bigint primary key,tenant_id bigint,factory_code varchar,arrange_id bigint,arrange_code varchar,task_code varchar,produce_task_code varchar,request_id varchar,request_hash varchar,actor_id bigint,actor_code varchar,note varchar,source_fingerprint varchar,source_snapshot varchar,create_time timestamp(6),del_flag varchar,unique(tenant_id,factory_code,request_id))");
		db.execute("CREATE TABLE mom_mes.mes_execution_command_cancellation(id bigint primary key,tenant_id bigint,factory_code varchar,command_kind varchar,request_id varchar,request_hash varchar,actor_id bigint,create_by varchar,create_time timestamp(6),del_flag varchar,unique(tenant_id,factory_code,command_kind,request_id))");
		db.update("INSERT INTO mom_mes.mes_task_arrange VALUES(1,1,'F1','A1','T1','P1','worker','FINISH','N1','PROC','0')");
		db.update("INSERT INTO mom_mes.mes_task VALUES(2,1,'F1','T1','FINISH',11,'original-engineering',3,'{}','0')");
		db.update("INSERT INTO mom_mes.mes_produce_task VALUES(3,1,'F1','P1','T1','PROC','N1','ROUTE',NULL,'0')");
		var config = new Configuration();
		config.setMapUnderscoreToCamelCase(true);
		config.setEnvironment(new Environment("history", new SpringManagedTransactionFactory(), source));
		try (var xml = getClass().getResourceAsStream("/mapper/MesDispatchHistoryMapper.xml")) {
			new XMLMapperBuilder(xml, config, "history.xml", config.getSqlFragments()).parse();
		}
		config.addMapper(MesExecutionCommandCancellationMapper.class);
		var sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
		var original = sql.getMapper(MesDispatchHistoryMapper.class);
		var mapper = (MesDispatchHistoryMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MesDispatchHistoryMapper.class}, (proxy, method, arguments) -> {
			if (method.getName().equals("ready")) {
				return true;
			}
			if (method.getName().equals("lockRequest")) {
				db.queryForObject("SELECT id FROM mutex WHERE id=1 FOR UPDATE", Integer.class);
				return null;
			}
			if (method.getName().equals("insertNote") && rejectNoteWrite) {
				return 0;
			}
			try {
				return method.invoke(original, arguments);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
		var access = MesFactoryAccessTestSupport.authorized();
		var cancellations = new MesExecutionCommandCancellationService(access, sql.getMapper(MesExecutionCommandCancellationMapper.class));
		templates = mock(RemoteBarcodeTemplateService.class);
		prints = mock(RemotePrintService.class);
		var target = new MesDispatchHistoryService(access, mapper, mock(MesExecutionTaskLock.class), cancellations,
				new MesSopFreezeService(null), templates, prints);
		var proxy = new ProxyFactory(target);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
		service = (MesDispatchHistoryService) proxy.getProxy();
		layout = new PrintTemplateEntity();
		layout.setId(19L);
		layout.setTenantId(1L);
		layout.setFactoryCode("F1");
		layout.setDelFlag("0");
		layout.setCode("DISPATCH");
		layout.setName("派工单");
		layout.setBusinessTypeCode("MES_DISPATCH");
		layout.setStatus(true);
		layout.setContent("{}");
		layout.setJsonData("{}");
		when(templates.getTemplateById(19L)).thenReturn(R.ok(layout));
		when(prints.preview(any())).thenReturn(R.ok("<html>rendered</html>", "ok"));
	}

	@AfterEach
	void cleanup() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void historicalProjectionUsesFrozenNodeAndReportsLegacyEvidenceGap() {
		var detail = service.detail("1");
		assertEquals("original-engineering", detail.getEngineering().get("contentHash"));
		assertTrue(detail.getDataGaps().stream().anyMatch(s -> s.contains("未保存")));
		db.update("UPDATE mom_mes.mes_produce_task SET process_route_snapshot=?", "[{\"routeNodeCode\":\"N1\",\"processCode\":\"PROC\",\"mdmProcessProcedureSopList\":[{\"name\":\"原图纸\",\"fileName\":\"v1.pdf\",\"url\":\"/files/old.pdf\"}]}]");
		var frozen = service.detail("1");
		assertEquals("原图纸", frozen.getSopDocuments().get(0).get("name"));
		assertEquals("/files/old.pdf", frozen.getSopDocuments().get(0).get("fileRef"));
		assertNotEquals(detail.getSourceFingerprint(), frozen.getSourceFingerprint());
	}

	@Test
	void corruptOrAmbiguousSnapshotIsGapRatherThanLatestSopGuess() {
		db.update("UPDATE mom_mes.mes_produce_task SET process_route_snapshot='broken-json'");
		assertTrue(service.detail("1").getDataGaps().stream().anyMatch(s -> s.contains("损坏")));
		db.update("UPDATE mom_mes.mes_produce_task SET process_route_snapshot=?", "[{\"routeNodeCode\":\"N2\",\"processCode\":\"PROC\"}]");
		assertTrue(service.detail("1").getSopDocuments().isEmpty());
	}

	@Test
	void noteIsAdditiveSameRawReplaysAndChangedRawCannotReuseId() {
		var raw = note();
		var first = service.note(raw);
		var replay = service.note(raw);
		assertEquals(first.get("result"), replay.get("result"));
		assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_dispatch_history_note", Integer.class));
		assertThrows(CheckedException.class, () -> service.note(raw.setNote("changed")));
		assertEquals("FINISH", db.queryForObject("SELECT status FROM mom_mes.mes_task_arrange", String.class));
	}

	@Test
	void staleSourceAndFailedMandatoryWriteLeaveNoAudit() {
		var raw = note();
		db.update("UPDATE mom_mes.mes_task_arrange SET status='CLOSE'");
		assertThrows(CheckedException.class, () -> service.note(raw));
		rejectNoteWrite = true;
		assertThrows(CheckedException.class, () -> service.note(note()));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_dispatch_history_note", Integer.class));
	}

	@Test
	void permanentCancellationBlocksLateOriginalPost() {
		var raw = note();
		assertEquals("ABSENT", service.noteReceipt(raw.getRequestId()).get("state"));
		assertEquals("CANCELLED", service.cancelNote(raw).get("state"));
		assertThrows(CheckedException.class, () -> service.note(raw));
		assertEquals("CANCELLED", service.noteReceipt(raw.getRequestId()).get("state"));
	}

	@Test
	void historyAndOriginalReceiptsCannotLeakOtherFactoryOrActor() {
		var raw = note();
		service.note(raw);
		actor(8L, "F1");
		assertEquals("ABSENT", service.noteReceipt(raw.getRequestId()).get("state"));
		actor(7L, "F2");
		assertThrows(CheckedException.class, () -> service.detail("1"));
	}

	@Test
	void printUsesServerFrozenDataAndGuardedSameLayout() {
		var source = service.detail("1");
		var result = service.printPreview(new PrintCommand().setArrangeId("1").setExpectedSourceFingerprint(source.getSourceFingerprint())
				.setTemplateId("19").setExpectedTemplateFingerprint(PrintTemplateFingerprint.of(layout)));
		assertEquals("<html>rendered</html>", result.get("html"));
		var sent = org.mockito.ArgumentCaptor.forClass(com.kitex.mom.common.barcode.dto.req.PreviewObj.class);
		verify(prints).preview(sent.capture());
		assertEquals("19", sent.getValue().getExpectedTemplateId());
		assertTrue(sent.getValue().getJsonData().contains("original-engineering"));
		assertTrue(sent.getValue().getJsonData().contains("A1"));
	}

	@Test
	void changedOrForeignLayoutAndRemoteRenderFailureCannotBecomePrintSuccess() {
		var raw = new PrintCommand().setArrangeId("1").setExpectedSourceFingerprint(service.detail("1").getSourceFingerprint())
				.setTemplateId("19").setExpectedTemplateFingerprint(PrintTemplateFingerprint.of(layout));
		layout.setContent("new-layout");
		assertThrows(CheckedException.class, () -> service.printPreview(raw));
		raw.setExpectedTemplateFingerprint(PrintTemplateFingerprint.of(layout));
		layout.setFactoryCode("F2");
		assertThrows(CheckedException.class, () -> service.printPreview(raw));
		layout.setFactoryCode("F1");
		raw.setExpectedTemplateFingerprint(PrintTemplateFingerprint.of(layout));
		when(prints.preview(any())).thenReturn(R.failed("broken-render"));
		assertThrows(CheckedException.class, () -> service.printPreview(raw));
	}

	@Test
	void invalidIdentitySkipAndUnboundedGlobalQueryAreRejected() {
		assertThrows(CheckedException.class, () -> service.detail("1 OR 1=1"));
		assertThrows(CheckedException.class, () -> service.page(new Query()));
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.detail("1"));
	}

	private NoteCommand note() {
		return new NoteCommand().setRequestId("HISTORY-NOTE-0001").setArrangeId("1")
				.setExpectedSourceFingerprint(service.detail("1").getSourceFingerprint()).setNote("核对原派工说明，实绩不变");
	}

	private void actor(Long id, String factory) {
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(id);
		when(user.getTenantId()).thenReturn(1L);
		when(user.getUsername()).thenReturn("actor-"+id);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "", List.of()));
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode(factory);
	}
}
