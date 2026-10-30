package com.kitex.mom.common.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation of ordered role paths and explicitly permitted level merges. */
public final class ProcessCompositionValidator {

	private static final int MAX_LEVELS = 16;

	private static final int MAX_BINDINGS = 64;

	private static final int MAX_SCHEMA_ENTRIES = 64;

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");

	private ProcessCompositionValidator() {
	}

	public record Violation(String code, String path, String message) {
	}

	/**
	 * @return immutable field errors for malformed user definitions
	 * @throws IllegalArgumentException when trusted adapter capabilities are malformed
	 */
	public static List<Violation> validate(ProcessCompositionDefinition definition, ProcessCompositionSchema schema) {
		Set<String> roles = validateSchema(schema);
		List<Violation> errors = new ArrayList<>();
		if (definition == null) {
			return List.of(new Violation("REQUIRED", "definition", "Process definition is required"));
		}
		List<ProcessCompositionDefinition.Level> levels = definition.levels();
		if (levels == null) {
			return List.of(new Violation("REQUIRED", "levels", "Process levels are required"));
		}
		if (levels.size() < schema.minLevels() || levels.size() > schema.maxLevels()) {
			errors.add(new Violation("LEVEL_COUNT", "levels", "Level count is outside adapter bounds"));
		}
		if (levels.size() > MAX_LEVELS) {
			return List.copyOf(errors);
		}

		Set<String> keys = new HashSet<>();
		Set<String> bindings = new HashSet<>();
		List<String> flattened = new ArrayList<>();
		int bindingCount = 0;
		for (int index = 0; index < levels.size(); index++) {
			ProcessCompositionDefinition.Level level = levels.get(index);
			String path = "levels[" + index + "]";
			if (level == null) {
				errors.add(new Violation("REQUIRED", path, "Level is required"));
				continue;
			}
			if (!identifier(level.key())) {
				errors.add(new Violation("INVALID_KEY", path + ".key", "Key must be a 1–32 character identifier"));
			}
			else if (!keys.add(level.key())) {
				errors.add(new Violation("DUPLICATE_KEY", path + ".key", "Level key must be unique"));
			}
			if (!label(level.label())) {
				errors.add(new Violation("INVALID_LABEL", path + ".label",
						"Label must be nonblank, at most 60 characters and contain no controls"));
			}
			List<String> group = level.bindings();
			if (group == null || group.isEmpty()) {
				errors.add(new Violation("REQUIRED", path + ".bindings", "Level bindings are required"));
				continue;
			}
			if (group.size() > MAX_BINDINGS) {
				errors.add(new Violation("BINDING_COUNT", path + ".bindings", "A level may contain at most 64 bindings"));
				continue;
			}
			bindingCount += group.size();
			if (group.size() > 1 && !schema.mergeableBindings().contains(group)) {
				errors.add(new Violation("MERGE_NOT_ALLOWED", path + ".bindings", "This ordered merge is not supported"));
			}
			for (int roleIndex = 0; roleIndex < group.size(); roleIndex++) {
				String code = group.get(roleIndex);
				String rolePath = path + ".bindings[" + roleIndex + "]";
				if (code == null || !roles.contains(code)) {
					errors.add(new Violation("UNKNOWN_ROLE", rolePath, "Binding must name a supported role"));
				}
				else if (!bindings.add(code)) {
					errors.add(new Violation("DUPLICATE_ROLE", rolePath,
							"A role may be bound only once"));
				}
				flattened.add(code);
			}
		}
		if (bindingCount > MAX_BINDINGS) {
			errors.add(new Violation("BINDING_COUNT", "levels", "A definition may contain at most 64 bindings"));
		}
		if (!schema.rolePaths().contains(flattened)) {
			errors.add(new Violation("INVALID_ROLE_PATH", "levels",
					"Bindings must exactly match an ordered supported role path"));
		}
		return List.copyOf(errors);
	}

	private static Set<String> validateSchema(ProcessCompositionSchema schema) {
		require(schema != null, "schema", "Capabilities are required");
		require(ProcessCompositionSchema.CONTRACT_VERSION.equals(schema.contractVersion()),
				"schema.contractVersion", "Unsupported contract version");
		require(identifier(schema.adapterId()), "schema.adapterId", "Invalid adapter identifier");
		require(schema.minLevels() >= 1 && schema.maxLevels() <= MAX_LEVELS
				&& schema.minLevels() <= schema.maxLevels(), "schema.minLevels", "Invalid level bounds");
		require(sizeBetween(schema.roles(), 1, MAX_SCHEMA_ENTRIES), "schema.roles", "Expected 1–64 roles");
		require(sizeBetween(schema.rolePaths(), 1, MAX_SCHEMA_ENTRIES), "schema.rolePaths", "Expected 1–64 paths");
		require(sizeBetween(schema.mergeableBindings(), 0, MAX_SCHEMA_ENTRIES),
				"schema.mergeableBindings", "Expected at most 64 merge groups");

		Set<String> roles = new HashSet<>();
		for (int index = 0; index < schema.roles().size(); index++) {
			ProcessCompositionSchema.Role role = schema.roles().get(index);
			String path = "schema.roles[" + index + "]";
			require(role != null, path, "Role is required");
			require(identifier(role.code()), path + ".code", "Invalid role identifier");
			require(roles.add(role.code()), path + ".code", "Duplicate role identifier");
			require(label(role.label()), path + ".label", "Invalid role label");
		}
		validateGroups(schema.rolePaths(), roles, 1, "schema.rolePaths");
		validateGroups(schema.mergeableBindings(), roles, 2, "schema.mergeableBindings");
		for (int index = 0; index < schema.mergeableBindings().size(); index++) {
			List<String> group = schema.mergeableBindings().get(index);
			require(schema.rolePaths().stream().anyMatch(path -> Collections.indexOfSubList(path, group) >= 0),
					"schema.mergeableBindings[" + index + "]", "Merge must be contiguous in a supported path");
		}
		for (int index = 0; index < schema.rolePaths().size(); index++) {
			require(realizable(schema.rolePaths().get(index), schema), "schema.rolePaths[" + index + "]",
					"Path cannot be composed within the level bounds using allowed merges");
		}
		return roles;
	}

	private static void validateGroups(List<List<String>> groups, Set<String> roles, int minimum, String field) {
		Set<List<String>> unique = new HashSet<>();
		for (int index = 0; index < groups.size(); index++) {
			List<String> group = groups.get(index);
			String path = field + "[" + index + "]";
			require(sizeBetween(group, minimum, MAX_BINDINGS), path, "Invalid role group size");
			require(unique.add(group), path, "Duplicate role group");
			Set<String> seen = new HashSet<>();
			for (int roleIndex = 0; roleIndex < group.size(); roleIndex++) {
				String role = group.get(roleIndex);
				require(role != null && roles.contains(role) && seen.add(role),
						path + "[" + roleIndex + "]", "Role must be known and unique in the group");
			}
		}
	}

	// Bounded dynamic programming avoids enumerating all possible merge combinations.
	private static boolean realizable(List<String> path, ProcessCompositionSchema schema) {
		boolean[][] reachable = new boolean[path.size() + 1][schema.maxLevels() + 1];
		reachable[0][0] = true;
		for (int offset = 0; offset < path.size(); offset++) {
			for (int count = 0; count < schema.maxLevels(); count++) {
				if (!reachable[offset][count]) {
					continue;
				}
				reachable[offset + 1][count + 1] = true;
				for (List<String> group : schema.mergeableBindings()) {
					int end = offset + group.size();
					if (end <= path.size() && path.subList(offset, end).equals(group)) {
						reachable[end][count + 1] = true;
					}
				}
			}
		}
		for (int count = schema.minLevels(); count <= schema.maxLevels(); count++) {
			if (reachable[path.size()][count]) {
				return true;
			}
		}
		return false;
	}

	private static boolean identifier(String value) {
		return value != null && IDENTIFIER.matcher(value).matches();
	}

	private static boolean label(String value) {
		return value != null && !value.isBlank() && value.length() <= 60
				&& value.codePoints().noneMatch(Character::isISOControl);
	}

	private static boolean sizeBetween(List<?> values, int minimum, int maximum) {
		return values != null && values.size() >= minimum && values.size() <= maximum;
	}

	private static void require(boolean condition, String path, String message) {
		if (!condition) {
			throw new IllegalArgumentException("Invalid " + path + ": " + message);
		}
	}
}
