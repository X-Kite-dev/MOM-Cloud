package com.kitex.mom.common.barcode.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模板配置属性
 */
@Data
@Component
@ConfigurationProperties("template")
public class TemplateProperties {

    /**
     * 配置文件地址
     */
    private String url;

}
