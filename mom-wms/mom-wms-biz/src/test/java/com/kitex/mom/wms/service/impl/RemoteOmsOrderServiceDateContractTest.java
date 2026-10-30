package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.util.R;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import feign.Feign;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.format.Formatter;
import org.springframework.format.support.DefaultFormattingConversionService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteOmsOrderServiceDateContractTest {

    @Test
    void orderDetailsRequestUsesDateFormatAcceptedByOms() {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
        DateTimeFormatter slashDate = DateTimeFormatter.ofPattern("yyyy/MM/dd");
        conversionService.addFormatterForFieldType(LocalDate.class, new Formatter<LocalDate>() {
            @Override
            public LocalDate parse(String text, Locale locale) {
                return LocalDate.parse(text, slashDate);
            }

            @Override
            public String print(LocalDate date, Locale locale) {
                return date.format(slashDate);
            }
        });
        RemoteOmsOrderService client = Feign.builder()
                .contract(new SpringMvcContract(List.of(), conversionService))
                .client((request, options) -> {
                    requestUrl.set(request.url());
                    return Response.builder()
                            .status(200)
                            .reason("OK")
                            .headers(Map.of())
                            .request(request)
                            .body("{}", StandardCharsets.UTF_8)
                            .build();
                })
                .decoder((response, type) -> R.ok())
                .target(RemoteOmsOrderService.class, "http://oms.test");

        client.getOrderDetails("WMS-VERIFY-20261024-01", "SZ01", LocalDate.of(2026, 10, 25).toString());

        assertTrue(requestUrl.get().contains("planDeliverTime=2026-10-25"), requestUrl.get());
    }
}
