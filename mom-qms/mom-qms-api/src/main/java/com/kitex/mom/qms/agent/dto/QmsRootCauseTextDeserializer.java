package com.kitex.mom.qms.agent.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** 窄引用命令只接受原JSON文本，禁止数值主键已舍入后被Jackson隐式转为字符串。 */
public class QmsRootCauseTextDeserializer extends StdDeserializer<String> {

	/** 仅由本次来源/人工命令字段显式使用，不改变全局String绑定。 */
	public QmsRootCauseTextDeserializer() {
		super(String.class);
	}

	/** 保留原文供后续严格格式、Long上界及正文控制字符校验。 */
	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		if (parser.currentToken() != JsonToken.VALUE_STRING) {
			throw JsonMappingException.from(parser, "质量引用主键、摘要及正文必须为JSON字符串");
		}
		return parser.getText();
	}
}
