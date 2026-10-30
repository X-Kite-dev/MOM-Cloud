package com.kitex.mom.iot.service.impl;

import com.kitex.mom.iot.controller.IotSceneController;
import com.kitex.mom.iot.entity.IotSceneEntity;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;
import com.kitex.mom.iot.service.IotSceneHistoryService;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Offline behavior regression; run main with assertions enabled, no device/database required. */
public class IotSceneExecutionRegressionTest {
	@org.junit.jupiter.api.Test
	void unimplementedActionsCannotProduceSuccessfulExecutionsOrResponses() {
		main(new String[0]);
	}

	public static void main(String[] args) {
		List<String> failures = new ArrayList<>();
		List<List<Map<String, Object>>> cases = Arrays.asList(
				List.of(Map.of("type", "alarm")), List.of(Map.of("type", "clearAlarm")),
				List.of(Map.of("type", "notification", "content", "secret-should-not-appear")),
				List.of(Map.of("type", "deviceOutput", "deviceId", "1", "outputType", "property")),
				List.of(Map.of("type", "deviceOutput", "deviceId", "1", "outputType", "function")),
				List.of(Map.of("type", "deviceOutput", "deviceId", "1", "outputType", "read")),
				List.of(Map.of("type", "delay")), List.of(Map.of("type", "unknown")),
				List.of(Map.of()), Arrays.asList((Map<String, Object>) null), List.of(), null,
				List.of(Map.of("type", 123)),
				List.of(Map.of("type", "alarm"), Map.of("type", "notification")));
		for (int i = 0; i < cases.size(); i++) {
			List<IotSceneHistoryEntity> history = new ArrayList<>();
			IotSceneHistoryService store = (IotSceneHistoryService) Proxy.newProxyInstance(
					IotSceneHistoryService.class.getClassLoader(), new Class<?>[] { IotSceneHistoryService.class },
					(proxy, method, values) -> {
						if (!method.getName().equals("addHistory")) {
							throw new AssertionError(method.getName());
						}
						history.add((IotSceneHistoryEntity) values[0]);
						return true;
					});
			IotSceneEntity scene = new IotSceneEntity();
			scene.setName("regression");
			scene.setStatus(1);
			scene.setTriggerType("manual");
			scene.setActions(cases.get(i));
			IotSceneServiceImpl service = new IotSceneServiceImpl(store) {
				@Override
				public IotSceneEntity getById(Serializable id) {
					return scene;
				}
			};
			boolean result = service.executeScene(1L);
			if (result || history.size() != 1 || history.get(0).getStatus() != 0
					|| history.get(0).getErrorMessage() == null || history.get(0).getErrorMessage().isBlank()) {
				failures.add("unexecuted action case " + i + " must return false and persist explained failure");
			}
			if (history.toString().contains("secret-should-not-appear")) {
				failures.add("action payload must not leak into execution history");
			}
			IotSceneController controller = new IotSceneController(service);
			if (controller.executeScene(1L).isOk() || controller.remoteExecuteScene(1L).isOk()) {
				failures.add("API must reject unexecuted action case " + i);
			}
			scene.setStatus(0);
			history.clear();
			if (service.executeScene(1L) || history.size() != 1 || history.get(0).getStatus() != 0) {
				failures.add("disabled scene must fail and retain failure history");
			}
		}
		IotSceneServiceImpl missing = new IotSceneServiceImpl(null) {
			@Override
			public IotSceneEntity getById(Serializable id) {
				return null;
			}
		};
		if (missing.executeScene(404L)) {
			failures.add("missing scene must not succeed");
		}
		IotSceneHistoryService brokenStore = (IotSceneHistoryService) Proxy.newProxyInstance(
				IotSceneHistoryService.class.getClassLoader(), new Class<?>[] { IotSceneHistoryService.class },
				(proxy, method, values) -> false);
		IotSceneServiceImpl brokenAudit = new IotSceneServiceImpl(brokenStore) {
			@Override
			public IotSceneEntity getById(Serializable id) {
				IotSceneEntity scene = new IotSceneEntity();
				scene.setStatus(1);
				return scene;
			}
		};
		try {
			brokenAudit.executeScene(1L);
			failures.add("failed history write must surface instead of pretending an audit record exists");
		} catch (IllegalStateException expected) {
			// The transaction must fail when the audit store refuses the record.
		}
		if (!failures.isEmpty()) {
			throw new AssertionError(String.join("\n", failures));
		}
		System.out.println(
				"PASS: 14 unexecuted scene cases, both APIs, disabled/missing scenes, audit failure and payload redaction");
	}
}
