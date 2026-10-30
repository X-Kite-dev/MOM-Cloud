package com.kitex.mom.common.process;

import java.util.List;

/** Trusted adapter capabilities; validation rejects malformed or unsupported contracts explicitly. */
public record ProcessCompositionSchema(String contractVersion, String adapterId, int minLevels, int maxLevels,
		List<Role> roles, List<List<String>> rolePaths, List<List<String>> mergeableBindings) {

	public static final String CONTRACT_VERSION = "PROCESS_COMPOSITION_SCHEMA_V1";

	public ProcessCompositionSchema {
		roles = ProcessCompositionDefinition.snapshot(roles);
		rolePaths = snapshotNested(rolePaths);
		mergeableBindings = snapshotNested(mergeableBindings);
	}

	public record Role(String code, String label) {
	}

	private static List<List<String>> snapshotNested(List<List<String>> values) {
		return values == null ? null : ProcessCompositionDefinition.snapshot(values.stream()
				.map(ProcessCompositionDefinition::snapshot).toList());
	}
}
