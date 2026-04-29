package com.kitex.mom.qms.handler;

import com.baomidou.mybatisplus.core.toolkit.Assert;
import com.baomidou.mybatisplus.extension.handlers.AbstractJsonTypeHandler;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * Jackson 实现 JSON 字段类型处理器, 会记录对象属性类型, 通常用于被容器(List、Set、Map)包装的属性上 T
 *
 * @author kitex
 * @date 2022/7/11
 */
@Slf4j
@MappedTypes({Object.class})
@MappedJdbcTypes(JdbcType.VARCHAR)
public class JacksonRawTypeHandler extends AbstractJsonTypeHandler<Object> {

    private final Class<?> type;
    private ObjectMapper objectMapper;

    public JacksonRawTypeHandler(Class<?> type) {
        super(type); // 假设父类有一个接受Class对象的构造函数
        if (log.isTraceEnabled()) {
            log.trace("JacksonRawTypeHandler(" + type + ")");
        }
        Assert.notNull(type, "Type argument cannot be null");
        this.type = type;
        this.init();
    }

    @SneakyThrows
    @Override
    public Object parse(String json) {
        return objectMapper.readValue(json, type);
    }

    @SneakyThrows
    @Override
    public String toJson(Object obj) {
        return objectMapper.writeValueAsString(obj);
    }

    private void init() {
        objectMapper = new ObjectMapper()
                // 指定要序列化的域
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
                // 不将日期写为时间戳
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 忽略未知属性
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // 对象属性为空时可以序列化
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).registerModule(new Jdk8Module())
                .registerModule(new SimpleModule())
                // 指定序列化输入的类型为非最终类型，除了少数“自然”类型（字符串、布尔值、整数、双精度），它们可以从 JSON 正确推断；
                // 以及所有非最终类型的数组
                .activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.WRAPPER_ARRAY)
                // null 值不序列化
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

}
