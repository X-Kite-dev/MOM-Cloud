package com.kitex.mom.mes.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 原单位证据量只输出精确普通小数文本，避免JS浮点或小量指数形式重新解释。
 */
public class MesNativeQuantitySerializer extends JsonSerializer<BigDecimal> {
    @Override
    public void serialize(BigDecimal quantity, JsonGenerator output, SerializerProvider provider) throws IOException {
        output.writeString(quantity.stripTrailingZeros().toPlainString());
    }
}
