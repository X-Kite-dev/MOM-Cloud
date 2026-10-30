package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.controller.MesPlanAttainmentController;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.*;
import com.kitex.mom.mes.mapper.MesPlanAttainmentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证实名组织、输入、固定时钟与Spring只读事务；实际数据SQL由Mapper测试覆盖。 */
class MesPlanAttainmentServiceTest {

	private MesPlanAttainmentMapper mapper;
	private MesPlanAttainmentService service;
	private final LocalDateTime observed = LocalDateTime.parse("2026-10-23T08:00:00.123456");

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(9007199254740993L);
		when(user.getTenantId()).thenReturn(1L);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
		mapper = mock(MesPlanAttainmentMapper.class);
		when(mapper.summary(any(), any(), any(), any())).thenReturn(counts("1", "1", "0", "0", "0", "0", "0"));
		when(mapper.groups(any(), any(), any(), any())).thenReturn(List.of());
		when(mapper.coverage(any(), any(), any(), any())).thenReturn(new Coverage().setMissingDueCount("0").setUnattributedDueCount("0"));
		when(mapper.detailTotal(any(), any(), any(), any())).thenReturn("0");
		when(mapper.details(any(), any(), any(), any(), anyLong())).thenReturn(List.of());
		service = new MesPlanAttainmentService(MesFactoryAccessTestSupport.authorized(), mapper, new MesPlanAttainmentCalculator(),
				Clock.fixed(Instant.parse("2026-10-23T00:00:00.123456789Z"), ZoneOffset.UTC));
	}

	@AfterEach
	void cleanup() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void actualContextAndShanghaiClockAreTextualAndCanonical() {
		var result = service.statistics(query().setWorkshopCode(" W1 ").setTaskCode("  "));

		assertEquals("1", result.getTenantId());
		assertEquals("9007199254740993", result.getActorId());
		assertEquals("F1", result.getFactoryCode());
		assertEquals("Asia/Shanghai", result.getTimeZone());
		assertEquals("2026-10-23T08:00:00.123456", result.getObservedAt());
		assertEquals("2026-10-22T00:00:00", result.getScope().startTime());
		assertEquals("W1", result.getScope().workshopCode());
		assertNull(result.getScope().taskCode());
		assertFalse(result.isProvisional());
		assertEquals("100.0000", result.getSummary().getOnTimeRate().percentage());
		verify(mapper).summary(eq(1L), eq("F1"), any(), eq(observed));
		verify(mapper).coverage(eq(1L), eq("F1"), any(), eq(observed));
	}

	@Test
	void ongoingAndFutureWindowsAreProvisional() {
		assertTrue(service.statistics(query().setEndTime(observed.plusHours(1))).isProvisional());
		assertTrue(service.statistics(query().setStartTime(observed.plusDays(1)).setEndTime(observed.plusDays(2))).isProvisional());
	}

	@Test
	void fixedPageCategoryAndGroupSelectorEchoOutsideBaseScope() {
		var result = service.details(query().setCurrent(2L).setCategory("UNKNOWN").setSelectedGroupUnattributed(true));

		assertEquals("2", result.getCurrent());
		assertEquals("30", result.getPageSize());
		assertEquals("UNKNOWN", result.getCategory());
		assertTrue(result.isSelectedGroupUnattributed());
		assertNull(result.getSelectedGroupCode());
		verify(mapper).details(eq(1L), eq("F1"), any(), eq(observed), eq(30L));
	}

	@Test
	void bypassAndMissingAuthenticationCannotRead() {
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.statistics(query()));
		TenantContextHolder.clear();
		TenantContextHolder.setTenantId(1L);
		SecurityContextHolder.clearContext();
		assertThrows(CheckedException.class, () -> service.details(query()));
		verifyZeroInteractions(mapper);
	}

	@Test
	void invalidWindowsEnumsLayerMixAndPageAreRejected() {
		assertThrows(CheckedException.class, () -> service.statistics(query().setStartTime(null)));
		assertThrows(CheckedException.class, () -> service.statistics(query().setEndTime(query().getStartTime())));
		assertThrows(CheckedException.class, () -> service.statistics(query().setEndTime(query().getStartTime().plusDays(366).plusSeconds(1))));
		assertThrows(CheckedException.class, () -> service.statistics(query().setObjectLevel("ARRANGE")));
		assertThrows(CheckedException.class, () -> service.statistics(query().setGroupBy("ROUTE")));
		assertThrows(CheckedException.class, () -> service.statistics(query().setRouteCode("R1")));
		assertThrows(CheckedException.class, () -> service.details(query().setCategory("LATE OR 1=1")));
		assertThrows(CheckedException.class, () -> service.details(query().setClosedPolicy("OMIT")));
		assertThrows(CheckedException.class, () -> service.details(query().setCurrent(0L)));
		assertThrows(CheckedException.class, () -> service.details(query().setTaskCode("X".repeat(101))));
		assertThrows(CheckedException.class, () -> service.details(query().setSelectedGroupCode("W1").setSelectedGroupUnattributed(true)));
		verifyZeroInteractions(mapper);
	}

	@Test
	void aboveOneThousandGroupsBlocksRatherThanReturningTruncatedResult() {
		when(mapper.groups(any(), any(), any(), any())).thenReturn(Collections.nCopies(1001, counts("1", "0", "0", "1", "0", "0", "0")));

		assertThrows(CheckedException.class, () -> service.statistics(query()));
	}

	@Test
	void missingAggregateOrCoverageDoesNotDefaultToZero() {
		when(mapper.summary(any(), any(), any(), any())).thenReturn(new Aggregate());
		assertThrows(CheckedException.class, () -> service.statistics(query()));
		when(mapper.summary(any(), any(), any(), any())).thenReturn(counts("1", "1", "0", "0", "0", "0", "0"));
		when(mapper.coverage(any(), any(), any(), any())).thenReturn(null);
		assertThrows(CheckedException.class, () -> service.statistics(query()));
	}

	@Test
	void sameReadOnlyRepeatableReadTransactionOwnsAllMapperReads() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:attainment-service-tx", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		when(mapper.summary(any(), any(), any(), any())).thenAnswer(invocation -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
			assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
					TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
			return counts("1", "1", "0", "0", "0", "0", "0");
		});
		var proxy = new ProxyFactory(service);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));

		assertEquals("1", ((MesPlanAttainmentService) proxy.getProxy()).statistics(query()).getSummary().getDueCount());
	}

	@Test
	void controllerRequiresShowPermissionForBothReadRoutes() throws Exception {
		for (String name : List.of("statistics", "details")) {
			var permission = MesPlanAttainmentController.class.getMethod(name, Query.class).getAnnotation(PreAuthorize.class);
			assertNotNull(permission);
			assertEquals("@pms.hasPermission('mesTaskArrange:show')", permission.value());
		}
	}

	@Test
	void pageBeyondJavaScriptSafeIntegerCountUsesExactOffset() {
		Query q = query();
		new BeanWrapperImpl(q).setPropertyValue("current", "300239975158034");
		var result = service.details(q);

		assertEquals("300239975158034", result.getCurrent());
		verify(mapper).details(eq(1L), eq("F1"), any(), eq(observed), eq(9007199254740990L));
	}

	@Test
	void largestRepresentableOffsetWorksAndOverflowingPageIsRejected() {
		long largestPage = Long.MAX_VALUE / 30L + 1L;
		var result = service.details(query().setCurrent(largestPage));

		assertEquals(Long.toString(largestPage), result.getCurrent());
		verify(mapper).details(eq(1L), eq("F1"), any(), eq(observed), eq((largestPage - 1L) * 30L));
		assertThrows(CheckedException.class, () -> service.details(query().setCurrent(largestPage + 1L)));
		assertThrows(CheckedException.class, () -> service.details(query().setCurrent(Long.MAX_VALUE)));
	}

	private Aggregate counts(String due, String onTime, String late, String open, String closed, String draft, String unknown) {
		return new Aggregate().setDueCount(due).setOnTimeCount(onTime).setLateCount(late).setOpenCount(open)
				.setClosedCount(closed).setDraftCount(draft).setUnknownCount(unknown);
	}

	private Query query() {
		return new Query().setStartTime(LocalDateTime.parse("2026-10-22T00:00:00"))
				.setEndTime(LocalDateTime.parse("2026-10-23T00:00:00"));
	}
}
