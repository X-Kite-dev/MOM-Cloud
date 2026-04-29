package com.kitex.mom.common.barcode.utils;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.kitex.mom.common.barcode.dto.req.PrintTemplateObj;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.tools.generic.DateTool;
import org.apache.velocity.tools.generic.MathTool;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;

/**
 * 模板引擎工具类
 *
 * @author kitex
 * @date 2023/2/7
 */
@Service
public class VelocityKit {

    /**
     * 渲染模板
     *
     * @param template 模板相对 classpath 路径
     * @param map      数据
     * @return String
     */
    public static String render(String template, Map<String, Object> map) {
        // 设置velocity资源加载器
        Properties prop = new Properties();
        prop.put("resource.loader.file.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        Velocity.init(prop);
        VelocityContext context = new VelocityContext(map);
        // 函数库
        context.put("math", new MathTool());
        context.put("dateTool", new DateTool());
        // context.put("dict", new DictTool());
        // context.put("str", new NamingCaseTool());
        // 渲染模板
        StringWriter sw = new StringWriter();
        Template tpl = Velocity.getTemplate(template, CharsetUtil.UTF_8);
        tpl.merge(context, sw);
        return sw.toString();
    }

    /**
     * 渲染文本
     *
     * @param str
     * @param dataModel 数据
     * @return
     */
    public static String renderStr(String str, String jsonData) {
        Map bean = JSONUtil.toBean(jsonData, Map.class);
        // 设置velocity资源加载器
        Velocity.init();
        StringWriter stringWriter = new StringWriter();
        VelocityContext context = new VelocityContext(bean);
        // 函数库
        context.put("math", new MathTool());
        context.put("dateTool", new DateTool());
        // context.put("dict", new DictTool());
        // context.put("str", new NamingCaseTool());
        Velocity.evaluate(context, stringWriter, "renderStr", str);
        return stringWriter.toString();
    }

    /**
     * 构造渲染模版数据
     *
     * @param template 模板json数据
     * @param data     数据
     * @return String
     */
    public static String templateJsonStr(String template, String data) {
        PrintTemplateObj printTemplateObj = new PrintTemplateObj();
        JSONConfig jsonConfig = new JSONConfig();
        jsonConfig.setWriteLongAsString(Boolean.TRUE);
        printTemplateObj.setTemplate(JSONUtil.parse(template));
        printTemplateObj.setPrintData(JSONUtil.parse(data, jsonConfig));
        return JSONUtil.toJsonStr(printTemplateObj);
    }

}
