package com.kitex.mom.plan.service;

import com.kitex.mom.plan.feign.RemotePlnWarehouseLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.openfeign.MomFeignClientsRegistrar;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证实际自动注册器在单体模式为本模块仓库生命周期客户端设置本机地址。 */
class WarehouseFeignRegistrationTest {

	@Test
	void monolithAutoConfigurationRegistersLifecycleClientAtLoopbackWithoutPackageScanning() {
		var environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("monolith", Map.of(
				"spring.cloud.nacos.discovery.enabled", false,
				"server.port", 18080,
				"server.servlet.context-path", "")));
		var registrar = new MomFeignClientsRegistrar();
		registrar.setBeanClassLoader(getClass().getClassLoader());
		registrar.setEnvironment(environment);
		var registry = new DefaultListableBeanFactory();

		registrar.registerBeanDefinitions(AnnotationMetadata.introspect(getClass()), registry);

		String name = RemotePlnWarehouseLifecycleService.class.getName();

		assertTrue(registry.containsBeanDefinition(name), "仓库生命周期客户端未通过imports自动注册");

		var properties = registry.getBeanDefinition(name).getPropertyValues();

		assertEquals(name, properties.get("type"));
		assertEquals("remotePlnWarehouseLifecycleService", properties.get("contextId"));
		assertEquals("kitex-mom-plan", properties.get("name"));
		assertEquals("http://127.0.0.1:18080", properties.get("url"));
	}

}
