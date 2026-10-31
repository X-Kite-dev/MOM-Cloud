package com.kitex.mom.mes.util;

import com.kitex.mom.mes.dto.MdmBomChildDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MesMaterialSubstituteResolverNestedTest {

	private static final String SNAPSHOT = """
			[{"materialCode":"A","quantity":3,"children":[
			  {"materialCode":"B","quantity":5,"children":[
			    {"materialCode":"C","quantity":7,"replaceCodes":"C-SUB"}
			  ]}
			]}]
			""";

	@Test
	void preservesGrandchildRequirementsWhenReadingFrozenBom() {
		var requirements = MesMaterialSubstituteResolver.flattenBom(SNAPSHOT);

		assertEquals(List.of("A", "B", "C"), requirements.stream()
				.map(MdmBomChildDTO::getMaterialCode).toList());
	}

	@Test
	void resolvesSubstituteBindingFromGrandchildFrozenPolicy() {
		var binding = assertDoesNotThrow(() -> MesMaterialSubstituteResolver
				.resolveFeedBinding(SNAPSHOT, "C-SUB", "C"));

		assertEquals("C", binding.requirementMatCode());
		assertEquals("C-SUB", binding.actualMatCode());
		assertEquals(0, BigDecimal.ONE.compareTo(binding.ratio()));
		assertTrue(binding.substitute());
	}
}
