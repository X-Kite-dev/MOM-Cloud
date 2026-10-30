package com.kitex.mom.common.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Business-neutral ordered process levels. Malformed user values remain available for validation. */
public record ProcessCompositionDefinition(List<Level> levels) {

	public ProcessCompositionDefinition {
		levels = snapshot(levels);
	}

	public record Level(String key, String label, List<String> bindings) {

		public Level {
			bindings = snapshot(bindings);
		}
	}

	static <T> List<T> snapshot(List<T> values) {
		return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
	}
}
