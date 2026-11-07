package com.kitex.mom.qms.agent.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** 引用目标周期只接受JSON正整数字面量，禁止浮点截断或字符串强制转换。 */
public class QmsRootCauseCycleDeserializer extends StdDeserializer<Integer> {

	/** 绑定窄入口Integer周期，不改变全局Jackson或普通QMS接口。 */
	public QmsRootCauseCycleDeserializer() {
		super(Integer.class);
	}

	/** 在完整命令登记前拒绝无法精确对应原CAPA周期的输入。 */
	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
			throw JsonMappingException.from(parser, "原CAPA周期必须为JSON正整数");
		}
		long cycle = parser.getLongValue();
		if (cycle < 1 || cycle > Integer.MAX_VALUE) {
			throw JsonMappingException.from(parser, "原CAPA周期超出合法正整数范围");
		}
		return (int) cycle;
	}
}
