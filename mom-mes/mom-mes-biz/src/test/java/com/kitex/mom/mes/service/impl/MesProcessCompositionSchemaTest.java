package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.common.process.ProcessCompositionValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MesProcessCompositionSchemaTest {

	@AfterEach
	void cleanup() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void profileDescribesOnlyRealMesResponsibilitiesAndExplicitMerge() {
		var schema = MesProcessCompositionSchema.schema();

		assertEquals("PROCESS_COMPOSITION_SCHEMA_V1", schema.contractVersion());
		assertEquals("MES_DISCRETE_V1", schema.adapterId());
		assertEquals(2, schema.minLevels());
		assertEquals(4, schema.maxLevels());
		assertEquals(List.of("ORDER", "TASK", "OPERATION", "ASSIGNMENT"),
				schema.roles().stream().map(role -> role.code()).toList());
		assertEquals(List.of(List.of("TASK", "OPERATION", "ASSIGNMENT"),
				List.of("ORDER", "TASK", "OPERATION", "ASSIGNMENT")), schema.rolePaths());
		assertEquals(List.of(List.of("OPERATION", "ASSIGNMENT")), schema.mergeableBindings());
	}

	@Test
	void legacyDefinitionsUseSharedValidationWithoutChangingTheirFields() {
		for (boolean order : List.of(false, true)) {
			for (boolean combined : List.of(false, true)) {
				var definition = MesProcessCompositionModelTest.definition(order, combined);
				var common = MesProcessCompositionSchema.definition(definition);

				assertTrue(ProcessCompositionValidator.validate(common, MesProcessCompositionSchema.schema()).isEmpty());
				assertEquals(definition.getLevels().get(0).getKey(), common.levels().get(0).key());
				assertEquals(definition.getLevels().get(0).getBindings(), common.levels().get(0).bindings());
			}
		}
	}

	@Test
	void capabilitiesAreReadOnlyAndBoundToActualAuthenticatedOrganization() {
		var service = new MesProcessCompositionService(MesFactoryAccessTestSupport.authorized(), null, null,
				null, null, null, null);
		assertThrows(CheckedException.class, service::capabilities);
		var actor = new MomUser(7L, "reader", 1L, null, null, null, "Reader", null,
				9007199254740993L, "unused", true, true, "USER", true, true, List.of());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, "", List.of()));
		TenantContextHolder.setTenantId(9007199254740993L);
		FactoryContextHolder.setFactoryCode("F1");

		var result = service.capabilities();

		assertEquals("PROCESS_COMPOSITION_CAPABILITIES_V1", result.get("contractVersion"));
		assertEquals("9007199254740993", result.get("tenantId"));
		assertEquals("F1", result.get("factoryCode"));
		assertEquals(MesProcessCompositionSchema.schema(), result.get("schema"));
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, service::capabilities);
	}
}
