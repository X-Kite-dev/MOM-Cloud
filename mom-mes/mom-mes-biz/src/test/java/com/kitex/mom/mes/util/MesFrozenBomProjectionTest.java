package com.kitex.mom.mes.util;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MesFrozenBomProjectionTest {

	private static final String BOM = """
			[{"id":11,"materialCode":"SCREW","quantity":16,"factoryCode":"F1"},
			 {"id":12,"materialCode":"HARNESS","quantity":1},
			 {"id":13,"materialCode":"TERM","quantity":12},
			 {"id":14,"materialCode":"BOX","quantity":1}]
			""";

	@Test
	void derivesOrderDemandWithoutNoticeQuantityInEngineeringNodes() {
		var result = MesFrozenBomProjection.project(BOM, new BigDecimal("2"), List.of());

		assertEquals(4, result.size());
		assertAmount("32", result.get(0).getNeedNum());
		assertAmount("2", result.get(1).getNeedNum());
		assertAmount("24", result.get(2).getNeedNum());
		assertAmount("2", result.get(3).getNeedNum());
		assertAmount("0", result.get(0).getFeedNum());
		assertAmount("0", result.get(0).getUsedNum());
		assertEquals(11L, result.get(0).getId());
		assertEquals("F1", result.get(0).getFactoryCode());
		assertAmount("16", result.get(0).getQuantity());
	}

	@Test
	void accumulatesParentRequirementsWithoutRollingDifferentMaterialsIntoParentFeed() {
		String snapshot = """
				[{"materialCode":"A","quantity":3,"noticeNum":999,"children":[
				  {"materialCode":"B","quantity":5,"children":[
				    {"materialCode":"C","quantity":7}
				  ]}
				]}]
				""";
		var feedA = feed(1L, "A", "A", "1", "1", "0");
		var feedB = feed(2L, "B", "B", "1", "10", "5");
		var feedC = feed(3L, "C", "C", "1", "14", "7");

		var root = MesFrozenBomProjection.project(snapshot, new BigDecimal("2"),
				List.of(feedA, feedB, feedC)).get(0);
		var child = root.getChildren().get(0);
		var grandchild = child.getChildren().get(0);

		assertAmount("6", root.getNeedNum());
		assertAmount("30", child.getNeedNum());
		assertAmount("210", grandchild.getNeedNum());
		assertAmount("1", root.getFeedNum());
		assertAmount("0", root.getUsedNum());
		assertAmount("10", child.getFeedNum());
		assertAmount("5", child.getUsedNum());
		assertAmount("14", grandchild.getFeedNum());
		assertAmount("7", grandchild.getUsedNum());
	}

	@Test
	void creditsSubstituteOnlyToItsOriginalRequirementUsingFrozenFeedRatio() {
		String snapshot = """
				[{"materialCode":"A","quantity":5,
				  "substitutePolicy":"[{\\\"substituteCode\\\":\\\"SUB\\\",\\\"substituteRatio\\\":99}]"},
				 {"materialCode":"SUB","quantity":1}]
				""";
		var main = feed(1L, "A", "A", "1", "6", "2");
		var substitute = feed(2L, "A", "SUB", "2", "8", "4");
		substitute.setSubstituteFlag(Boolean.TRUE);
		String before = JSONUtil.toJsonStr(substitute);

		var result = MesFrozenBomProjection.project(snapshot, new BigDecimal("2"), List.of(main, substitute));

		assertAmount("10", result.get(0).getFeedNum());
		assertAmount("4", result.get(0).getUsedNum());
		assertAmount("0", result.get(1).getFeedNum());
		assertEquals(before, JSONUtil.toJsonStr(substitute));
		assertEquals("A", substitute.getRequirementMatCode());
		assertAmount("2", substitute.getSubstituteRatio());
	}

	@Test
	void displaysMaterialTotalsOnRepeatedPathsWithoutInventingPathAllocation() {
		String snapshot = """
				[{"materialCode":"A","quantity":2,"children":[
				  {"materialCode":"COMMON","quantity":3}
				]}, {"materialCode":"B","quantity":4,"children":[
				  {"materialCode":"COMMON","quantity":5}
				]}]
				""";
		var common = feed(1L, "COMMON", "COMMON", "1", "10", "4");

		var result = MesFrozenBomProjection.project(snapshot, BigDecimal.ONE, List.of(common));

		assertAmount("6", result.get(0).getChildren().get(0).getNeedNum());
		assertAmount("20", result.get(1).getChildren().get(0).getNeedNum());
		assertAmount("10", result.get(0).getChildren().get(0).getFeedNum());
		assertAmount("10", result.get(1).getChildren().get(0).getFeedNum());
		assertAmount("0", result.get(0).getFeedNum());
		assertAmount("0", result.get(1).getFeedNum());
	}

	@Test
	void permitsKnownLegacyMainMaterialWithoutChangingItsMissingBindingFields() {
		var legacy = feed(1L, null, "SCREW", null, "8", "2");
		legacy.setSubstituteFlag(null);
		String before = JSONUtil.toJsonStr(legacy);

		var result = MesFrozenBomProjection.project(BOM, BigDecimal.ONE, List.of(legacy));

		assertAmount("8", result.get(0).getFeedNum());
		assertAmount("2", result.get(0).getUsedNum());
		assertEquals(before, JSONUtil.toJsonStr(legacy));
	}

	@Test
	void aggregatesSameRatioBeforeRoundingForDisplay() {
		var feeds = List.of(feed(1L, "SCREW", "SUB", "3", "1", "0"),
				feed(2L, "SCREW", "SUB", "3", "1", "0"),
				feed(3L, "SCREW", "SUB", "3", "1", "0"));

		var result = MesFrozenBomProjection.project(BOM, BigDecimal.ONE, feeds);

		assertAmount("1", result.get(0).getFeedNum());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"0", "-1"})
	void rejectsUnknownOrNonpositiveOrderQuantity(String quantity) {
		BigDecimal notice = quantity == null ? null : new BigDecimal(quantity);

		assertThrows(CheckedException.class, () -> MesFrozenBomProjection.project(BOM, notice, List.of()));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "{}", "null", "[null]", "[] trailing", "[{}]",
			"[{\"materialCode\":\"A\",\"quantity\":0}]",
			"[{\"materialCode\":\"A\",\"quantity\":-1}]",
			"[{\"materialCode\":\"A\",\"quantity\":1,\"children\":{}}]",
			"[{\"materialCode\":\"A\",\"quantity\":1,\"quantity\":2}]"})
	void rejectsUnknownOrMalformedFrozenBom(String snapshot) {
		assertThrows(CheckedException.class,
				() -> MesFrozenBomProjection.project(snapshot, BigDecimal.ONE, List.of()));
	}

	@Test
	void returnsEmptyOnlyForAnExplicitEmptyBomWithNoFeed() {
		assertTrue(MesFrozenBomProjection.project("[]", BigDecimal.ONE, List.of()).isEmpty());
		assertThrows(CheckedException.class,
				() -> MesFrozenBomProjection.project(BOM, BigDecimal.ONE, null));
	}

	@Test
	void rejectsUnknownOrContradictoryFeedFactsInsteadOfReplacingThemWithZero() {
		var invalid = new ArrayList<MesWarehouseFeedEntity>();
		invalid.add(feed(1L, "SCREW", "SCREW", "1", null, "0"));
		invalid.add(feed(2L, "SCREW", "SCREW", "1", "8", null));
		invalid.add(feed(3L, "SCREW", "SCREW", "1", "8", "9"));
		invalid.add(feed(4L, "SCREW", "SCREW", "0", "8", "0"));
		invalid.add(feed(5L, "SCREW", "SCREW", "-1", "8", "0"));
		invalid.add(feed(6L, "SCREW", "SUB", null, "8", "0"));
		invalid.add(feed(7L, null, "SUB", "2", "8", "0"));
		invalid.add(feed(8L, "UNKNOWN", "UNKNOWN", "1", "8", "0"));
		invalid.add(feed(9L, "SCREW", "SCREW", "1", "-1", "0"));
		invalid.add(feed(10L, "SCREW", "SCREW", "1", "8", "-1"));

		for (var row : invalid) {
			assertThrows(CheckedException.class,
					() -> MesFrozenBomProjection.project(BOM, BigDecimal.ONE, List.of(row)),
					"invalid feed " + row.getId());
		}
	}

	private static MesWarehouseFeedEntity feed(Long id, String requirement, String actual,
			String ratio, String quantity, String used) {
		var feed = new MesWarehouseFeedEntity();
		feed.setId(id);
		feed.setRequirementMatCode(requirement);
		feed.setMatCode(actual);
		feed.setSubstituteRatio(ratio == null ? null : new BigDecimal(ratio));
		feed.setFeedNum(quantity == null ? null : new BigDecimal(quantity));
		feed.setUsedNum(used == null ? null : new BigDecimal(used));
		feed.setSubstituteFlag(requirement != null && !requirement.equals(actual));
		return feed;
	}

	private static void assertAmount(String expected, BigDecimal actual) {
		assertEquals(0, new BigDecimal(expected).compareTo(actual));
	}
}
