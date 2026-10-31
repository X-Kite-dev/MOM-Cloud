package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;
import com.kitex.mom.mes.util.MesWorksiteTriggerProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MesWorksiteTriggerProjectionTest {

	@Test
	void missingLegacyTriggerUsesManualDefaultInsteadOfStationIdentifier() {
		assertEquals("MANUAL", MesWorksiteTriggerProjection.forDispatch("S1", null, List.of("S1")));
	}

	@Test
	void selectedStationsKeepTheirOriginalTriggerPositions() {
		assertEquals("MANUAL,AUTO", MesWorksiteTriggerProjection.forDispatch(
				"S1,S2", "AUTO,MANUAL", List.of("S2", "S1")));
		assertEquals("AUTO,AUTO", MesWorksiteTriggerProjection.forDispatch(
				"S1,S2", "AUTO", List.of("S2", "S1")));
	}

	@Test
	void missingOrUnknownMappingCannotBecomeAStationIdentifier() {
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forDispatch(
				"S1,S2", "MANUAL,AUTO,MANUAL", List.of("S1")));
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forDispatch(
				"S1", "ROBOT", List.of("S1")));
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forDispatch(
				"S1", null, List.of("S2")));
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forDispatch(
				"S1,S1", null, List.of("S1")));
	}

	@Test
	void knownOldCodeFallbackIsReadFromTheExactOriginalParentWithoutRewritingFacts() {
		var parent = parent();
		var arrange = arrange("S1");

		assertEquals(WorksiteTypeEnum.MANUAL, MesWorksiteTriggerProjection.forExecution(parent, arrange));
		assertEquals("S1", arrange.getWorksiteTypes());
		assertNull(parent.getWorksiteTypes());
	}

	@Test
	void codeFallbackCannotOverrideAnAutomaticOrForeignParent() {
		var parent = parent();
		var arrange = arrange("S1");
		parent.setWorksiteTypes("AUTO");
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
		parent.setWorksiteTypes(null);
		parent.setFactoryCode("OTHER");
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
	}

	@Test
	void unrecognizedStoredTypeDoesNotUseTheLegacyDefault() {
		assertThrows(CheckedException.class,
				() -> MesWorksiteTriggerProjection.forExecution(parent(), arrange("ROBOT")));
	}

	@Test
	void validAutomaticTriggerAndActualStationMappingRemainAutomatic() {
		assertEquals(WorksiteTypeEnum.AUTO,
				MesWorksiteTriggerProjection.forExecution(parent(), arrange("AUTO")));
		var arrange = arrange("MANUAL,AUTO");
		arrange.setWorksiteCodes("S1,S2");
		arrange.setActualWorksiteCode("S2");
		assertEquals(WorksiteTypeEnum.AUTO, MesWorksiteTriggerProjection.forExecution(parent(), arrange));
		arrange.setActualWorksiteCode(null);
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent(), arrange));
	}

	@Test
	void oldCodeFallbackRequiresNonblankExactFrozenNode() {
		for (String node : new String[] {null, "", " ", " RN-1"}) {
			var parent = parent();
			var arrange = arrange("S1");
			parent.setSourceRouteNodeCode(node);
			arrange.setSourceRouteNodeCode(node);
			assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
		}
	}

	@Test
	void oldCodeFallbackRejectsEveryForeignSourceDimension() {
		var parent = parent();
		var arrange = arrange("S1");
		arrange.setTenantId(2L);
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
		arrange.setTenantId(1L);
		arrange.setTaskCode("OTHER");
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
		arrange.setTaskCode("T1");
		arrange.setProduceTaskCode("OTHER");
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
		arrange.setProduceTaskCode("P1");
		arrange.setSourceRouteNodeCode("RN-2");
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent, arrange));
	}

	@Test
	void actualStationOutsideOriginalCandidatesCannotUseOldCodeFallback() {
		var arrange = arrange("S1");
		arrange.setActualWorksiteCode("S2");
		assertThrows(CheckedException.class, () -> MesWorksiteTriggerProjection.forExecution(parent(), arrange));
	}

	private MesProduceTaskEntity parent() {
		var parent = new MesProduceTaskEntity();
		parent.setTenantId(1L);
		parent.setFactoryCode("F1");
		parent.setTaskCode("T1");
		parent.setCode("P1");
		parent.setSourceRouteNodeCode("RN-1");
		parent.setWorksiteCodes("S1,S2");
		return parent;
	}

	private MesTaskArrangeEntity arrange(String types) {
		var arrange = new MesTaskArrangeEntity();
		arrange.setTenantId(1L);
		arrange.setFactoryCode("F1");
		arrange.setTaskCode("T1");
		arrange.setProduceTaskCode("P1");
		arrange.setSourceRouteNodeCode("RN-1");
		arrange.setWorksiteCodes("S1");
		arrange.setWorksiteTypes(types);
		arrange.setActualWorksiteCode("S1");
		return arrange;
	}
}
