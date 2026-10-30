package com.kitex.mom.common.barcode;

import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.template.HtmlTemplate;
import com.kitex.mom.common.barcode.utils.VelocityKit;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.mapper.PrintTemplateMapper;
import com.kitex.mom.common.barcode.service.impl.PrintTemplateServiceImpl;
import com.kitex.mom.common.barcode.utils.PrintTemplateFingerprint;
import com.kitex.mom.common.barcode.controller.PrintController;
import com.kitex.mom.common.barcode.controller.PrintTemplateController;
import com.kitex.mom.common.barcode.controller.PrintLogController;
import com.kitex.mom.common.barcode.service.PrintTemplateService;
import com.kitex.mom.common.barcode.service.PrintLogService;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises actual Velocity output in JavaScript; HiPrint and the DOM remain browser boundaries. */
class CommonPrintRegressionTest {

	@Test
	void missingRuntimeConfigurationCannotProduceAnApparentlyUsablePreview() {
		var context = mock(ApplicationContext.class);
		var parameters = mock(RemoteParamService.class);
		when(context.getBean(RemoteParamService.class)).thenReturn(parameters);
		when(parameters.getByKey("templateUrl")).thenReturn(R.ok((String) null, null));
		new HtmlTemplate().setContext(context);
		assertThrows(com.kitex.common.core.exception.CheckedException.class, HtmlTemplate::getHtml);
	}

	@Test
	void previewControllersReturnHtmlAsDataInsteadOfUsingTheMessageOnlyStringOverload() {
		var templates = mock(PrintTemplateService.class);
		var logs = mock(PrintLogService.class);
		var request = new PreviewObj();
		when(templates.billPreview(request)).thenReturn("<html>actual dispatch</html>");
		when(templates.preview("LAYOUT")).thenReturn("<html>layout sample</html>");
		when(logs.preview(42L)).thenReturn("<html>original captured print</html>");

		assertEquals("<html>actual dispatch</html>", new PrintController(null, templates).preview(request).getData());
		assertEquals("<html>layout sample</html>", new PrintTemplateController(templates).preview("LAYOUT").getData());
		assertEquals("<html>original captured print</html>", new PrintLogController(logs).preview(42L).getData());
	}

	@AfterEach
	void clearScope() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void guardedPreviewRejectsLayoutDriftAndPartialGuards() throws Exception {
		var fixture = fixture();
		var request = guarded(fixture.template);
		fixture.template.setName("更换后的布局");
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
		request.setExpectedTemplateFingerprint(null);
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
		request.setExpectedTemplateId("");
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
	}

	@Test
	void guardedPreviewRejectsMissingOrSkippedActualScope() throws Exception {
		var fixture = fixture();
		var request = guarded(fixture.template);
		TenantContextHolder.clear();
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
		TenantContextHolder.setTenantId(1L);
		TenantContextHolder.setTenantSkip();
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
		TenantContextHolder.clear();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
		FactoryContextHolder.clear();
		assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
	}

	@Test
	void guardedPreviewUsesOneExactEnabledScopedEntityAndLegacyStillWorks() throws Exception {
		var fixture = fixture();
		fixture.service.billPreview(guarded(fixture.template));
		assertEquals(1, fixture.reads.get(), "The checked entity must be the entity rendered; no second lookup");
		var query = fixture.query.get();
		String sql = query.getSqlSegment();
		for (String column : List.of("id", "code", "tenant_id", "factory_code", "status", "del_flag", "business_type_code")) {
			assertTrue(sql.contains(column + " ="), "Missing exact layout constraint: " + column);
		}
		assertTrue(query.getParamNameValuePairs().containsValue(9007199254740993L));
		assertTrue(query.getParamNameValuePairs().containsValue("F1"));
		assertTrue(query.getParamNameValuePairs().containsValue("MES_DISPATCH"));

		clearScope();
		var legacy = new PreviewObj();
		legacy.setTemplateCode("DISPATCH_LAYOUT");
		legacy.setJsonData("{}");
		fixture.service.billPreview(legacy);
		assertEquals(2, fixture.reads.get());
	}

	@Test
	void guardedPreviewRejectsAnEntityOutsideItsExpectedIdentity() throws Exception {
		List<Consumer<PrintTemplateEntity>> changes = List.of(value -> value.setId(42L), value -> value.setTenantId(2L),
				value -> value.setFactoryCode("F2"), value -> value.setCode("OTHER"), value -> value.setStatus(false),
				value -> value.setDelFlag("1"), value -> value.setBusinessTypeCode("OTHER"));
		for (var change : changes) {
			var fixture = fixture();
			var request = guarded(fixture.template);
			change.accept(fixture.template);
			request.setExpectedTemplateFingerprint(PrintTemplateFingerprint.of(fixture.template));
			assertThrows(RuntimeException.class, () -> fixture.service.billPreview(request));
		}
	}

	private PreviewObj guarded(PrintTemplateEntity template) {
		var request = new PreviewObj();
		request.setTemplateCode(template.getCode());
		request.setJsonData("{}");
		request.setExpectedTemplateId(template.getId().toString());
		request.setExpectedTemplateFingerprint(PrintTemplateFingerprint.of(template));
		request.setExpectedBusinessTypeCode("MES_DISPATCH");
		return request;
	}

	private Fixture fixture() throws Exception {
		configureHtml();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var template = template();
		var mapper = mock(PrintTemplateMapper.class);
		var query = new AtomicReference<AbstractWrapper<?, ?, ?>>();
		var reads = new AtomicInteger();
		when(mapper.selectOne(any(), anyBoolean())).thenAnswer(call -> {
			reads.incrementAndGet();
			query.set(call.getArgument(0));
			return template;
		});
		var service = new PrintTemplateServiceImpl();
		org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", mapper);
		return new Fixture(template, service, query, reads);
	}

	private record Fixture(PrintTemplateEntity template, PrintTemplateServiceImpl service,
			AtomicReference<AbstractWrapper<?, ?, ?>> query, AtomicInteger reads) {
	}

	@Test
	void layoutFingerprintChangesWhenAnyAuthoritativeLayoutFieldChanges() throws Exception {
		var method = Class.forName("com.kitex.mom.common.barcode.utils.PrintTemplateFingerprint")
				.getMethod("of", PrintTemplateEntity.class);
		String original = (String) method.invoke(null, template());
		assertTrue(original.matches("[a-f0-9]{64}"));
		assertEquals(original, method.invoke(null, template()));
		List<Consumer<PrintTemplateEntity>> changes = List.of(
				value -> value.setId(42L), value -> value.setTenantId(2L), value -> value.setFactoryCode("F2"),
				value -> value.setCode("OTHER"), value -> value.setBusinessTypeCode("OTHER"),
				value -> value.setBusinessTypeName("其他"), value -> value.setName("新名"),
				value -> value.setContent("{}"), value -> value.setJsonData("[]"),
				value -> value.setStatus(false), value -> value.setDelFlag("1"),
				value -> value.setUpdateTime(LocalDateTime.of(2026, 10, 24, 9, 0)));
		for (var change : changes) {
			var changed = template();
			change.accept(changed);
			assertNotEquals(original, method.invoke(null, changed));
		}
	}

	@Test
	void previewCarriesOptionalOriginalLayoutGuardWithoutChangingLegacyConstruction() throws Exception {
		var legacy = new PreviewObj();
		assertEquals(null, PreviewObj.class.getMethod("getExpectedTemplateId").invoke(legacy));
		PreviewObj.class.getMethod("setExpectedTemplateId", String.class).invoke(legacy, "9007199254740993");
		assertEquals("9007199254740993", PreviewObj.class.getMethod("getExpectedTemplateId").invoke(legacy));
		PreviewObj.class.getMethod("setExpectedTemplateFingerprint", String.class).invoke(legacy, "hash");
		PreviewObj.class.getMethod("setExpectedBusinessTypeCode", String.class).invoke(legacy, "MES_DISPATCH");
	}

	private PrintTemplateEntity template() {
		var value = new PrintTemplateEntity();
		value.setId(9007199254740993L);
		value.setTenantId(1L);
		value.setFactoryCode("F1");
		value.setCode("DISPATCH_LAYOUT");
		value.setBusinessTypeCode("MES_DISPATCH");
		value.setBusinessTypeName("派工");
		value.setName("原派工布局");
		value.setContent("{\"panels\":[]}");
		value.setJsonData("{\"sample\":true}");
		value.setStatus(true);
		value.setDelFlag("0");
		value.setUpdateTime(LocalDateTime.of(2026, 10, 23, 8, 0));
		return value;
	}

	@Test
	void actualVelocityWrapperExecutesWithoutTurningBusinessDataIntoScript() throws Exception {
		assertBrowser(browserData(), false);
	}

	@Test
	void actualVelocityRetainsExistingBatchListPayloads() throws Exception {
		assertBrowser("[" + browserData() + "]", true);
	}

	private String browserData() {
		return "{\"id\":9007199254740993,\"name\":\"中文\\n第二行</script><script>globalThis.injected=true</script>\",\"quantity\":\"12.50\",\"__proto__\":{\"description\":\"原始数据\"}}";
	}

	private void assertBrowser(String data, boolean batch) throws Exception {
		configureHtml();
		String layout = "{\"panels\":[{\"name\":\"派工单\"}]}";
		String html = VelocityKit.renderStr(HtmlTemplate.getHtml(), VelocityKit.templateJsonStr(layout, data));
		Path rendered = Files.createTempFile("common-print-rendered-", ".html");
		Path browserTest = Files.createTempFile("common-print-browser-", ".cjs");
		try {
			Files.writeString(rendered, html);
			try (var script = CommonPrintRegressionTest.class.getResourceAsStream("/print-wrapper.test.cjs")) {
				org.junit.jupiter.api.Assertions.assertNotNull(script, "Print browser regression resource must be packaged with tests");
				Files.copy(script, browserTest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			var process = new ProcessBuilder("node", browserTest.toString(), rendered.toString(), batch ? "batch" : "single")
					.inheritIO().start();
			assertEquals(0, process.waitFor(), "Actual rendered print wrapper must execute and preserve original JSON values");
		} finally {
			Files.deleteIfExists(rendered);
			Files.deleteIfExists(browserTest);
		}
	}

	private void configureHtml() {
		var context = mock(ApplicationContext.class);
		var parameters = mock(RemoteParamService.class);
		when(context.getBean(RemoteParamService.class)).thenReturn(parameters);
		when(parameters.getByKey("templateUrl")).thenReturn(R.ok("/local/hiprint", null));
		new HtmlTemplate().setContext(context);
	}

	public static void main(String[] args) throws Exception {
		int passed = 0;
		int failed = 0;
		for (var method : CommonPrintRegressionTest.class.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Test.class)) {
				continue;
			}
			try {
				method.invoke(new CommonPrintRegressionTest());
				System.out.println("PASS " + method.getName());
				passed++;
			} catch (InvocationTargetException failure) {
				System.out.println("FAIL " + method.getName() + ": " + failure.getCause());
				failed++;
			} finally {
				new CommonPrintRegressionTest().clearScope();
			}
		}
		System.out.println("Common print tests: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			throw new AssertionError("Common print regressions failed");
		}
	}
}
