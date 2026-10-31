package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.controller.MesTaskReportController;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesIdentifierNodePolicyDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.mapper.MesIdentifierNodeDeploymentMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 身份登记不依赖动态目录；省略参数的旧终端仍取得真实部署摘要。 */
class MesReportOperatorContextTest {

	private final MesActualFactoryAccessService access = mock(MesActualFactoryAccessService.class);
	private final MesIdentifierNodeDeploymentMapper deployment = mock(MesIdentifierNodeDeploymentMapper.class);
	private MesIdentifierNodeReadiness readiness;
	private MesReportCommandServiceImpl service;
	private MockMvc http;

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		var user = new MomUser(7L, "worker", 1L, null, null, null, "Worker", null,
				1L, "unused", true, true, "USER", true, true, List.of());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user, null, List.of()));
		when(access.require("read")).thenReturn("F1");
		readiness = new MesIdentifierNodeReadiness(access, deployment, mock(PermissionService.class));
		service = new MesReportCommandServiceImpl(access, null, null, readiness);
		http = MockMvcBuilders.standaloneSetup(
				new MesTaskReportController(null, null, null, access, service, null)).build();
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void identityOnlyRequestReturnsActualOwnerWithoutCatalogAccess() throws Exception {
		when(deployment.missingControls()).thenThrow(new IllegalStateException("catalog unavailable"));

		http.perform(get("/mesTask/report/command-context").param("includeNodeReadiness", "false"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tenantId").value("1"))
				.andExpect(jsonPath("$.data.factoryCode").value("F1"))
				.andExpect(jsonPath("$.data.actorId").value("7"))
				.andExpect(jsonPath("$.data.actorCode").value("worker"))
				.andExpect(jsonPath("$.data.nodeExecutionReady").value(false))
				.andExpect(jsonPath("$.data.nodeReadiness").doesNotExist());
		verify(deployment, never()).missingControls();
	}

	@Test
	void omittedParameterKeepsCatalogSummaryForExistingClients() throws Exception {
		when(deployment.missingControls()).thenReturn("[]");

		http.perform(get("/mesTask/report/command-context"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nodeExecutionReady").value(true))
				.andExpect(jsonPath("$.data.nodeReadiness.databaseReady").value(true));
		verify(deployment).missingControls();
	}

	@Test
	void noArgumentServiceMethodKeepsFailedCatalogSummary() {
		when(deployment.missingControls()).thenReturn("[\"UNIQUE:missing-protection\"]");

		var result = service.operatorContext();

		assertFalse(result.getNodeExecutionReady());
		assertFalse(result.getNodeReadiness().getReady());
		assertEquals(List.of("UNIQUE:missing-protection"), result.getNodeReadiness().getMissingControls());
	}

	@Test
	void identityOnlyRequestStillRequiresActualFactoryReadAccess() throws Exception {
		when(access.require("read")).thenThrow(new CheckedException("factory access denied"));

		var failure = assertThrows(ServletException.class, () -> http.perform(
				get("/mesTask/report/command-context").param("includeNodeReadiness", "false")));

		assertEquals("factory access denied", failure.getCause().getMessage());
		verify(deployment, never()).missingControls();
	}

	@Test
	void dynamicPublicationDispatchAndReportRemainBlockedByMissingProtection() {
		when(deployment.missingControls()).thenReturn("[\"UNIQUE:missing-protection\"]");
		var contract = new MesExecutionContractDTO().setReportMode("ONE_CODE")
				.setIdentifierNodePolicy(new MesIdentifierNodePolicyDTO().setAction("BIND"))
				.setAllowedTriggers(List.of("MANUAL")).setStationAllowed(true)
				.setRemainingQuantity(BigDecimal.ONE).setPartialReportAllowed(true);
		var arrange = new MesTaskArrangeEntity().setCode("A1").setFactoryCode("F1").setStatus("PRODUCT");
		arrange.setTenantId(1L);
		var templates = new MesExecutionTemplateServiceImpl(access, null, null, null, readiness, null, null) {
			@Override
			public MesExecutionContractDTO contract(MesTaskArrangeEntity ignored) {
				return contract;
			}
		};

		var publication = assertThrows(CheckedException.class,
				() -> readiness.assertReady(readiness.publication(contract)));
		var dispatch = assertThrows(CheckedException.class, () -> readiness.requireDispatch(contract));
		var report = assertThrows(CheckedException.class,
				() -> templates.validateReport(arrange, new MesTaskReportVO(), MesReportChannelEnum.STATION));

		assertTrue(publication.getMessage().contains("UNIQUE:missing-protection"));
		assertTrue(dispatch.getMessage().contains("UNIQUE:missing-protection"));
		assertTrue(report.getMessage().contains("UNIQUE:missing-protection"));
	}
}
