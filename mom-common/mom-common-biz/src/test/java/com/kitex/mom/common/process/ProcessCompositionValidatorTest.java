package com.kitex.mom.common.process;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ProcessCompositionValidatorTest {

	private static final List<String> PATH = List.of("REQUEST", "REVIEW", "PREPARE", "DELIVER", "ACCEPT");

	private ProcessCompositionSchema schema() {
		return schema(PATH, 1, 16, List.of(List.of("DELIVER", "ACCEPT")));
	}

	private ProcessCompositionSchema schema(List<String> path, int min, int max, List<List<String>> merges) {
		return new ProcessCompositionSchema("PROCESS_COMPOSITION_SCHEMA_V1", "SERVICE_V1", min, max,
				path.stream().map(code -> new ProcessCompositionSchema.Role(code, code)).toList(),
				List.of(path), merges);
	}

	private ProcessCompositionDefinition definition(List<String> path) {
		return new ProcessCompositionDefinition(path.stream()
				.map(code -> new ProcessCompositionDefinition.Level(code, code, List.of(code))).toList());
	}

	private ProcessCompositionDefinition level(String key, String label, List<String> bindings) {
		return new ProcessCompositionDefinition(List.of(new ProcessCompositionDefinition.Level(key, label, bindings)));
	}

	private void violation(ProcessCompositionDefinition definition, String path) {
		assertTrue(ProcessCompositionValidator.validate(definition, schema()).stream()
				.anyMatch(value -> value.path().equals(path)), path);
	}

	@Test
	void acceptsIndependentFiveLevelPath() {
		assertTrue(ProcessCompositionValidator.validate(definition(PATH), schema()).isEmpty());
	}

	@Test
	void acceptsOnlyExplicitMergeAndExactOrderedPath() {
		List<ProcessCompositionDefinition.Level> levels = new ArrayList<>(definition(PATH).levels().subList(0, 3));
		levels.add(new ProcessCompositionDefinition.Level("delivery", "Delivery and acceptance", List.of("DELIVER", "ACCEPT")));

		assertTrue(ProcessCompositionValidator.validate(new ProcessCompositionDefinition(levels), schema()).isEmpty());
		violation(level("all", "All", PATH), "levels[0].bindings");
		violation(definition(List.of("REVIEW", "REQUEST", "PREPARE", "DELIVER", "ACCEPT")), "levels");
		violation(definition(PATH.subList(0, 4)), "levels");
	}

	@Test
	void acceptsAnyExplicitAlternativePath() {
		ProcessCompositionSchema base = schema();
		ProcessCompositionSchema alternatives = new ProcessCompositionSchema(base.contractVersion(), base.adapterId(),
				1, 16, base.roles(), List.of(PATH, PATH.subList(1, 5)), base.mergeableBindings());

		assertTrue(ProcessCompositionValidator.validate(definition(PATH.subList(1, 5)), alternatives).isEmpty());
	}

	@Test
	void acceptsSixteenLevelsAndRejectsSeventeen() {
		List<String> sixteen = IntStream.range(0, 16).mapToObj(index -> "ROLE" + index).toList();
		List<String> seventeen = IntStream.range(0, 17).mapToObj(index -> "ROLE" + index).toList();
		ProcessCompositionSchema boundary = schema(sixteen, 1, 16, List.of());

		assertTrue(ProcessCompositionValidator.validate(definition(sixteen), boundary).isEmpty());
		assertFalse(ProcessCompositionValidator.validate(definition(seventeen), boundary).isEmpty());
	}

	@Test
	void respectsAdapterLevelMinimumAndMaximum() {
		ProcessCompositionSchema strict = schema(PATH, 5, 5, List.of(List.of("DELIVER", "ACCEPT")));
		List<ProcessCompositionDefinition.Level> levels = new ArrayList<>(definition(PATH).levels().subList(0, 3));
		levels.add(new ProcessCompositionDefinition.Level("delivery", "Delivery", List.of("DELIVER", "ACCEPT")));

		assertFalse(ProcessCompositionValidator.validate(new ProcessCompositionDefinition(levels), strict).isEmpty());
	}

	@Test
	void rejectsDuplicateKeysAndRolesWithFieldPaths() {
		List<ProcessCompositionDefinition.Level> levels = new ArrayList<>(definition(PATH).levels());
		levels.set(1, new ProcessCompositionDefinition.Level("REQUEST", "Review", List.of("REQUEST")));

		violation(new ProcessCompositionDefinition(levels), "levels[1].key");
		violation(new ProcessCompositionDefinition(levels), "levels[1].bindings[0]");
	}

	@Test
	void rejectsUnknownEmptyAndNullBindings() {
		violation(level("key", "Label", List.of("UNKNOWN")), "levels[0].bindings[0]");
		violation(level("key", "Label", List.of()), "levels[0].bindings");
		violation(level("key", "Label", null), "levels[0].bindings");
		violation(level("key", "Label", Arrays.asList((String) null)), "levels[0].bindings[0]");
	}

	@Test
	void validatesKeysAndLabelsWithoutThrowing() {
		for (String key : Arrays.asList(null, "", "1key", "a b", "k".repeat(33))) {
			violation(level(key, "Label", List.of("REQUEST")), "levels[0].key");
		}
		for (String label : Arrays.asList(null, "", "   ", "a".repeat(61), "Label\n", "Label\u007f")) {
			violation(level("key", label, List.of("REQUEST")), "levels[0].label");
		}
		ProcessCompositionDefinition valid = level("K".repeat(32), "标".repeat(60), List.of("REQUEST"));
		ProcessCompositionSchema one = schema(List.of("REQUEST"), 1, 1, List.of());

		assertTrue(ProcessCompositionValidator.validate(valid, one).isEmpty());
	}

	@Test
	void rejectsNullAndMissingDefinitionFieldsAsViolations() {
		violation(null, "definition");
		violation(new ProcessCompositionDefinition(null), "levels");
		violation(new ProcessCompositionDefinition(List.of()), "levels");
		violation(new ProcessCompositionDefinition(Arrays.asList((ProcessCompositionDefinition.Level) null)), "levels[0]");
	}

	@Test
	void acceptsSixtyFourBindingsAndRejectsLargerPerLevelAndTotal() {
		List<String> roles = IntStream.range(0, 64).mapToObj(index -> "R" + index).toList();
		ProcessCompositionSchema full = schema(roles, 1, 16, List.of(roles));
		List<String> excess = new ArrayList<>(roles);
		excess.add("R0");

		assertTrue(ProcessCompositionValidator.validate(level("all", "All", roles), full).isEmpty());
		assertFalse(ProcessCompositionValidator.validate(level("all", "All", excess), full).isEmpty());
		ProcessCompositionDefinition total = new ProcessCompositionDefinition(List.of(
				new ProcessCompositionDefinition.Level("first", "First", roles),
				new ProcessCompositionDefinition.Level("last", "Last", List.of("R0"))));
		assertFalse(ProcessCompositionValidator.validate(total, full).isEmpty());
	}

	@Test
	void snapshotsDefinitionListsAndKeepsMalformedNullsForValidation() {
		List<String> bindings = new ArrayList<>(List.of("REQUEST"));
		ProcessCompositionDefinition.Level first = new ProcessCompositionDefinition.Level("first", "First", bindings);
		List<ProcessCompositionDefinition.Level> levels = new ArrayList<>(List.of(first));
		ProcessCompositionDefinition value = new ProcessCompositionDefinition(levels);

		bindings.clear();
		levels.clear();

		assertEquals(List.of("REQUEST"), value.levels().get(0).bindings());
		assertThrows(UnsupportedOperationException.class, () -> value.levels().clear());
		assertThrows(UnsupportedOperationException.class, () -> first.bindings().clear());
	}

	@Test
	void snapshotsEverySchemaListIncludingNestedLists() {
		List<String> path = new ArrayList<>(PATH);
		List<String> group = new ArrayList<>(List.of("DELIVER", "ACCEPT"));
		List<ProcessCompositionSchema.Role> roles = new ArrayList<>(schema().roles());
		List<List<String>> paths = new ArrayList<>(List.of(path));
		List<List<String>> groups = new ArrayList<>(List.of(group));
		ProcessCompositionSchema value = new ProcessCompositionSchema("PROCESS_COMPOSITION_SCHEMA_V1", "SERVICE_V1",
				1, 16, roles, paths, groups);

		path.clear();
		group.clear();
		roles.clear();
		paths.clear();
		groups.clear();

		assertTrue(ProcessCompositionValidator.validate(definition(PATH), value).isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> value.roles().clear());
		assertThrows(UnsupportedOperationException.class, () -> value.rolePaths().clear());
		assertThrows(UnsupportedOperationException.class, () -> value.rolePaths().get(0).clear());
		assertThrows(UnsupportedOperationException.class, () -> value.mergeableBindings().clear());
		assertThrows(UnsupportedOperationException.class, () -> value.mergeableBindings().get(0).clear());
	}

	@Test
	void returnsImmutableViolationsWithCodesAndMessages() {
		List<ProcessCompositionValidator.Violation> violations = ProcessCompositionValidator.validate(null, schema());

		assertFalse(violations.isEmpty());
		assertTrue(violations.stream().allMatch(value -> !value.code().isBlank() && !value.message().isBlank()));
		assertThrows(UnsupportedOperationException.class, () -> violations.clear());
	}

	@Test
	void rejectsMalformedTrustedSchemaClearly() {
		ProcessCompositionSchema base = schema();
		List<ProcessCompositionSchema> malformed = Arrays.asList(null,
				new ProcessCompositionSchema("wrong", base.adapterId(), 1, 16, base.roles(), base.rolePaths(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), null, 1, 16, base.roles(), base.rolePaths(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 0, 16,
						base.roles(), base.rolePaths(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 5, 4,
						base.roles(), base.rolePaths(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 17,
						base.roles(), base.rolePaths(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16, null, base.rolePaths(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16, base.roles(), null, List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16, base.roles(), List.of(), List.of()),
				new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16,
						base.roles(), base.rolePaths(), null));

		for (ProcessCompositionSchema value : malformed) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> ProcessCompositionValidator.validate(definition(PATH), value));
			assertTrue(error.getMessage().contains("schema"));
		}
	}

	@Test
	void rejectsInvalidSchemaRolesPathsAndMergeGroups() {
		ProcessCompositionSchema base = schema();
		List<List<ProcessCompositionSchema.Role>> invalidRoles = List.of(
				Arrays.asList((ProcessCompositionSchema.Role) null),
				List.of(new ProcessCompositionSchema.Role("bad code", "Label")),
				List.of(new ProcessCompositionSchema.Role("REQUEST", "Label\t")),
				Collections.nCopies(65, base.roles().get(0)),
				List.of(base.roles().get(0), base.roles().get(0)));
		for (List<ProcessCompositionSchema.Role> roles : invalidRoles) {
			assertThrows(IllegalArgumentException.class, () -> ProcessCompositionValidator.validate(definition(PATH),
					new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16,
							roles, base.rolePaths(), List.of())));
		}
		List<List<List<String>>> invalidPaths = List.of(
				Arrays.asList((List<String>) null), List.of(List.of()), List.of(List.of("UNKNOWN")),
				List.of(List.of("REQUEST", "REQUEST")), Collections.nCopies(65, PATH));
		for (List<List<String>> paths : invalidPaths) {
			assertThrows(IllegalArgumentException.class, () -> ProcessCompositionValidator.validate(definition(PATH),
					new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16,
							base.roles(), paths, List.of())));
		}
		List<List<List<String>>> invalidGroups = List.of(
				Arrays.asList((List<String>) null), List.of(List.of()), List.of(List.of("REQUEST")),
				List.of(List.of("ACCEPT", "DELIVER")), List.of(List.of("UNKNOWN", "REQUEST")),
				Collections.nCopies(65, List.of("DELIVER", "ACCEPT")));
		for (List<List<String>> groups : invalidGroups) {
			assertThrows(IllegalArgumentException.class, () -> ProcessCompositionValidator.validate(definition(PATH),
					new ProcessCompositionSchema(base.contractVersion(), base.adapterId(), 1, 16,
							base.roles(), base.rolePaths(), groups)));
		}
	}
}
