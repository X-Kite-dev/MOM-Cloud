package com.kitex.mom.common.barcode.utils;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.tools.generic.DateTool;
import org.apache.velocity.tools.generic.MathTool;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.LinkedHashMap;
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
	 * @param jsonData 数据
	 * @return
	 */
	public static String renderStr(String str, String jsonData) {
		Map<String, Object> bean = new LinkedHashMap<>();
		JSONUtil.parseObj(jsonData).forEach(bean::put);
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
		JSONConfig config = JSONConfig.create().setWriteLongAsString(true).setIgnoreNullValue(false);
		Map<String, Object> model = new LinkedHashMap<>();
		// The browser consumes JSON text, never Java Map.toString(). Escape script-closing bytes before interpolation.
		model.put("templateJson", scriptJson(template, config));
		model.put("printDataJson", scriptJson(data, config));
		return JSONUtil.toJsonStr(model, config);
	}

	private static String scriptJson(String source, JSONConfig config) {
		return JSONUtil.quote(JSONUtil.toJsonStr(JSONUtil.parse(source, config), config))
				.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
				.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
	}

}
