package com.kitex.mom.qms.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bind every complete-source candidate in the actual installer catalog query. */
final class KnownReadySourceParameters {
	private static final Pattern VARIABLE =
			Pattern.compile("\\b(scope_old_ready|scope_new_ready|old_ready|new_ready)\\b");

	record BoundQuery(String sql, List<Object> parameters) {}

	static BoundQuery bind(String sql, Map<String, String> sources) {
		var matcher = VARIABLE.matcher(sql);
		var query = new StringBuilder();
		List<Object> parameters = new ArrayList<>();
		while (matcher.find()) {
			String source = Objects.requireNonNull(sources.get(matcher.group()), matcher.group());
			parameters.add(source);
			matcher.appendReplacement(query, "?");
		}
		matcher.appendTail(query);
		return new BoundQuery(query.toString(), parameters);
	}

	private KnownReadySourceParameters() {}
}
