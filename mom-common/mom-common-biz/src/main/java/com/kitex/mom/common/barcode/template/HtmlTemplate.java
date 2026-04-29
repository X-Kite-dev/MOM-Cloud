package com.kitex.mom.common.barcode.template;

import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.util.R;
import lombok.SneakyThrows;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.init.ResourceReader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

@Component
public class HtmlTemplate {

    private static ApplicationContext context;

    @SneakyThrows
    public static String getHtml() {
        StringBuilder resultStringBuilder = new StringBuilder();
        try (InputStream inputStream = ResourceReader.class.getClassLoader().getResourceAsStream("static/index.html");
             BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                resultStringBuilder.append(line).append("\n");
            }
        }

        // 设置velocity资源加载器
        Velocity.init();
        StringWriter stringWriter = new StringWriter();
        VelocityContext velocityContext = new VelocityContext();

        RemoteParamService bean = context.getBean(RemoteParamService.class);
        R<String> templateUrl = bean.getByKey("templateUrl");
        velocityContext.put("templateUrl", templateUrl.getData());
        Velocity.evaluate(velocityContext, stringWriter, "renderStr", resultStringBuilder.toString());
        return stringWriter.toString();
    }

    @Autowired
    public void setContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }
}
