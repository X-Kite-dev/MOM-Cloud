package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.controller.MesWipAgingController;
import com.kitex.mom.mes.dto.MesWipAgingDTO.*;
import com.kitex.mom.mes.mapper.MesWipAgingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
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

/** 身份、固定观察时点、输入及实际Spring事务；数据口径由真实XML测试覆盖。 */
class MesWipAgingServiceTest {

	private MesWipAgingMapper mapper;
	private MesWipAgingService service;
	private final LocalDateTime observed = LocalDateTime.parse("2026-10-23T08:00:00.123456");

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(9007199254740993L);
		when(user.getTenantId()).thenReturn(1L);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
		mapper = mock(MesWipAgingMapper.class);
		when(mapper.summary(any(), any(), any(), any(), any())).thenReturn(counts());
		when(mapper.groups(any(), any(), any(), any(), any())).thenReturn(List.of());
		when(mapper.coverage(any(), any(), any(), any(), any())).thenReturn(new Coverage().setUnattributedCount("0"));
		when(mapper.detailTotal(any(), any(), any(), any(), any())).thenReturn("0");
		when(mapper.details(any(), any(), any(), any(), any(), anyLong())).thenReturn(List.of());
		service = new MesWipAgingService(MesFactoryAccessTestSupport.authorized(), mapper, new MesWipAgingCalculator(),
				Clock.fixed(Instant.parse("2026-10-23T00:00:00.123456789Z"), ZoneOffset.UTC));
	}

	@AfterEach
	void cleanup() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void observedIdentityScopeAndThresholdAreCanonicalAndSharedByAllReads() {
		var r = service.statistics(new Query().setWorkshopCode(" W1 ").setTaskCode("  ").setThresholdHours("2"));

		assertEquals("MES_WIP_AGING_V1", r.getContractVersion());
		assertEquals("1", r.getTenantId());
		assertEquals("9007199254740993", r.getActorId());
		assertEquals("F1", r.getFactoryCode());
		assertEquals("Asia/Shanghai", r.getTimeZone());
		assertEquals("2026-10-23T08:00:00.123456", r.getObservedAt());
		assertEquals("W1", r.getScope().workshopCode());
		assertNull(r.getScope().taskCode());
		assertEquals("2", r.getScope().thresholdHours());
		verify(mapper).summary(eq(1L), eq("F1"), any(), eq(observed), eq(observed.minusHours(2)));
		verify(mapper).coverage(eq(1L), eq("F1"), any(), eq(observed), eq(observed.minusHours(2)));
		verify(mapper).groups(eq(1L), eq("F1"), any(), eq(observed), eq(observed.minusHours(2)));
	}

	@Test
	void detailSelectorsEchoOutsideBaseScopeWithExactLargePageOffset() {
		Query q = new Query().setStatus("PAUSE").setAgingCategory("OVER").setSelectedGroupCode(" UNKNOWN ");
		new BeanWrapperImpl(q).setPropertyValue("current", "300239975158034");
		var r = service.details(q);

		assertEquals("300239975158034", r.getCurrent());
		assertEquals("30", r.getPageSize());
		assertEquals("PAUSE", r.getStatus());
		assertEquals("OVER", r.getAgingCategory());
		assertEquals("UNKNOWN", r.getSelectedGroupCode());
		assertFalse(r.isSelectedGroupUnattributed());
		verify(mapper).details(eq(1L), eq("F1"), any(), eq(observed), eq(observed.minusHours(24)), eq(9007199254740990L));
	}

	@Test
	void largestSafePageWorksAndOverflowIsRejected() {
		long largest = Long.MAX_VALUE / 30L + 1;
		assertEquals(Long.toString(largest), service.details(new Query().setCurrent(largest)).getCurrent());
		verify(mapper).details(eq(1L), eq("F1"), any(), any(), any(), eq((largest - 1) * 30L));
		assertThrows(CheckedException.class, () -> service.details(new Query().setCurrent(largest + 1)));
	}

	@Test
	void invalidThresholdEnumsCodesAndSelectorsFailBeforeQuery() {
		for (String threshold : new String[]{null, "0", "8761", "1.5", "01", "-1", " 24 ", "99999999999999999999"}) {
			assertThrows(CheckedException.class, () -> service.statistics(new Query().setThresholdHours(threshold)));
		}
		assertThrows(CheckedException.class, () -> service.statistics(new Query().setGroupBy("MATERIAL")));
		assertThrows(CheckedException.class, () -> service.details(new Query().setStatus("FINISH")));
		assertThrows(CheckedException.class, () -> service.details(new Query().setAgingCategory("LATE")));
		assertThrows(CheckedException.class, () -> service.details(new Query().setCurrent(0L)));
		assertThrows(CheckedException.class, () -> service.details(new Query().setWorkshopCode("X".repeat(101))));
		assertThrows(CheckedException.class, () -> service.details(new Query().setTaskCode("X\nY")));
		assertThrows(CheckedException.class, () -> service.details(new Query().setSelectedGroupCode("UNKNOWN").setSelectedGroupUnattributed(true)));
		verifyZeroInteractions(mapper);
	}

	@Test
	void authenticationAndTenantOrFactoryBypassCannotQuery() {
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.statistics(new Query()));
		TenantContextHolder.clear();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> service.details(new Query()));
		FactoryContextHolder.clear();
		FactoryContextHolder.setFactoryCode("F1");
		SecurityContextHolder.clearContext();
		assertThrows(CheckedException.class, () -> service.statistics(new Query()));
		verifyZeroInteractions(mapper);
	}

	@Test
	void overflowGroupsAndMissingCountsNeverBecomeHealthyZero() {
		when(mapper.groups(any(), any(), any(), any(), any())).thenReturn(Collections.nCopies(1001, counts()));
		assertThrows(CheckedException.class, () -> service.statistics(new Query()));
		when(mapper.groups(any(), any(), any(), any(), any())).thenReturn(List.of());
		when(mapper.summary(any(), any(), any(), any(), any())).thenReturn(new Aggregate());
		assertThrows(CheckedException.class, () -> service.statistics(new Query()));
		when(mapper.summary(any(), any(), any(), any(), any())).thenReturn(counts());
		when(mapper.coverage(any(), any(), any(), any(), any())).thenReturn(null);
		assertThrows(CheckedException.class, () -> service.details(new Query()));
	}

	@Test
	void actualSpringTransactionKeepsAllReadsReadOnlyAndRepeatable() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:wip-service-tx", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		when(mapper.summary(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
			assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
					TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
			return counts();
		});
		var proxy = new ProxyFactory(service);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
		var transactional = (MesWipAgingService) proxy.getProxy();

		assertEquals("0", transactional.statistics(new Query()).getSummary().getPopulationCount());
		assertEquals("0", transactional.details(new Query()).getTotal());
	}

	@Test
	void bothReadRoutesEnforceShowPermissionAndProductionConstructorNeedsNoClockBean() throws Exception {
		for (String name : List.of("statistics", "details")) {
			var permission = MesWipAgingController.class.getMethod(name, Query.class).getAnnotation(PreAuthorize.class);
			assertNotNull(permission);
			assertEquals("@pms.hasPermission('mesTaskArrange:show')", permission.value());
		}
		var constructor = MesWipAgingService.class.getConstructor(MesActualFactoryAccessService.class,
				MesWipAgingMapper.class, MesWipAgingCalculator.class);
		assertNotNull(constructor.getAnnotation(Autowired.class));
	}

	private Aggregate counts() {
		return new Aggregate().setPopulationCount("0").setWaitingDispatchCount("0").setWaitingReceiveCount("0")
				.setReceivedCount("0").setProducingCount("0").setPausedCount("0").setUnknownStatusCount("0")
				.setSourceIssueCount("0").setKnownQueueAgeCount("0").setUnknownQueueAgeCount("0").setOverThresholdCount("0");
	}
}
