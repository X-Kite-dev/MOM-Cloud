package com.kitex.mom.common.barcode.template;

import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.util.R;
import com.kitex.common.core.exception.CheckedException;
import lombok.SneakyThrows;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class HtmlTemplate {

	private static ApplicationContext context;

	@SneakyThrows
	public static String getHtml() {
		StringBuilder resultStringBuilder = new StringBuilder();
		try (InputStream inputStream = HtmlTemplate.class.getClassLoader().getResourceAsStream("static/index.html")) {
			if (inputStream == null) {
				throw new CheckedException("打印包装资源缺失，请检查部署");
			}
			BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
			String line;
			while ((line = br.readLine()) != null) {
				resultStringBuilder.append(line).append("\n");
			}
		}

		// 设置velocity资源加载器
		Velocity.init();
		StringWriter stringWriter = new StringWriter();
		VelocityContext velocityContext = new VelocityContext();

		if (context == null) {
			throw new CheckedException("打印配置服务尚未初始化");
		}
		RemoteParamService bean = context.getBean(RemoteParamService.class);
		R<String> templateUrl = bean.getByKey("templateUrl");
		if (templateUrl == null || !Objects.equals(templateUrl.getCode(), 0) || templateUrl.getData() == null
				|| templateUrl.getData().isBlank()) {
			throw new CheckedException("打印运行资源地址未配置或不可读取，请维护templateUrl");
		}
		velocityContext.put("templateUrl", templateUrl.getData());
		Velocity.evaluate(velocityContext, stringWriter, "renderStr", resultStringBuilder.toString());
		return stringWriter.toString();
	}

	@Autowired
	public void setContext(ApplicationContext applicationContext) {
		context = applicationContext;
	}
}
