package com.kitex.mom.common.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;

/** 用于原件协议的显式字符串字段，禁止 Jackson 将数字等标量隐式转成身份文本。 */
public final class StrictJsonTextDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING))
            throw MismatchedInputException.from(
                    parser, String.class, "原件字段必须为JSON字符串，不接受数字或其他标量转换");
        return parser.getText();
    }
}
