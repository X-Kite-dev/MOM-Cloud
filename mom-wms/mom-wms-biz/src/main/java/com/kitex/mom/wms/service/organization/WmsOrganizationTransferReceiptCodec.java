package com.kitex.mom.wms.service.organization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * 永久回执固定使用ISO时间与Long精度，不跟随HTTP展示日期格式的调整。
 */
@Component
public class WmsOrganizationTransferReceiptCodec {
	private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	public String writeValueAsString(Object value) throws JsonProcessingException {
		return mapper.writeValueAsString(value);
	}

	public <T> T readValue(String value, Class<T> type) throws JsonProcessingException {
		return mapper.readValue(value, type);
	}
}
