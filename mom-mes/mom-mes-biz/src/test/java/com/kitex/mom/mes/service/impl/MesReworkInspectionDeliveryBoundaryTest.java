package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.constant.SecurityConstants;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.MomSecurityInnerAspect;
import com.kitex.mom.mes.controller.MesReworkInspectionDeliveryController;
import com.kitex.mom.mes.dto.MesReworkInspectionDeliveryDTO;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.mes.service.MesTaskService;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 真实服务验签、MVC和Inner切面；原MES数量执行端口受控，其数据库行为由既有专项覆盖。 */
class MesReworkInspectionDeliveryBoundaryTest {
    private static final String KEY = "test-only-rework-write-key-0123456789";
    private final ObjectMapper json = new ObjectMapper();
    private final List<MesReworkInspectionResultDTO> consumed = new ArrayList<>();
    private MesReworkInspectionDeliveryService delivery;
    private MesTaskService tasks;

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        tasks = mock(MesTaskService.class);
        doAnswer(
                        c -> {
                            consumed.add(c.getArgument(0));
                            return null;
                        })
                .when(tasks)
                .syncReworkInspection(any());
        delivery = new MesReworkInspectionDeliveryService(tasks);
        ReflectionTestUtils.setField(delivery, "deliverySecret", KEY);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void anAuthenticatedFrozenResultUsesTheOriginalQuantityCommand(String type) throws Exception {
        var d = command();
        d.setPayload(d.getPayload().replace("PROCEDURE", type));
        seal(d);
        delivery.accept(d);
        assertEquals(1, consumed.size());
        var r = consumed.get(0);
        assertEquals("RW-1", r.getRequestId());
        assertEquals(700L, r.getQmsTaskId());
        assertEquals(type, r.getInspectionType());
        assertEquals("QUALIFIED", r.getResult());
        assertEquals(5, r.getTotalNum());
        assertEquals(5, r.getUsableNum());
        assertEquals(5, r.getHandleNum());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TENANT",
                "FACTORY",
                "TENANT_SKIP",
                "FACTORY_SKIP",
                "OLD_TIME",
                "FUTURE_TIME",
                "NONCE",
                "NO_SIGNATURE",
                "BAD_SIGNATURE",
                "WRONG_KEY",
                "NO_KEY",
                "RAW_CHANGED",
                "HEADER_EVENT",
                "HEADER_ORDER",
                "HEADER_SEQUENCE",
                "HEADER_VERSION",
                "DUPLICATE_KEY",
                "UTF8_LIMIT"
            })
    void aServiceMarkerCannotAuthorizeUnverifiableOrReboundEvidence(String invalid)
            throws Exception {
        var d = command();
        switch (invalid) {
            case "TENANT" -> TenantContextHolder.setTenantId(2L);
            case "FACTORY" -> FactoryContextHolder.setFactoryCode("F2");
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
            case "OLD_TIME" -> {
                d.setIssuedAt(Instant.now().getEpochSecond() - 91);
                seal(d);
            }
            case "FUTURE_TIME" -> {
                d.setIssuedAt(Instant.now().getEpochSecond() + 30);
                seal(d);
            }
            case "NONCE" -> {
                d.setNonce("short");
                seal(d);
            }
            case "NO_SIGNATURE" -> d.setSignature(null);
            case "BAD_SIGNATURE" -> d.setSignature("0".repeat(64));
            case "WRONG_KEY" -> d.setSignature(d.sign("different-test-only-key-0123456789"));
            case "NO_KEY" -> ReflectionTestUtils.setField(delivery, "deliverySecret", "");
            case "RAW_CHANGED" -> {
                d.setPayload(d.getPayload().replace("RW-1", "RW-2"));
                d.setSignature(d.sign(KEY));
            }
            case "HEADER_EVENT" -> {
                d.setEventId(43L);
                seal(d);
            }
            case "HEADER_ORDER" -> {
                d.setOrderId(11L);
                seal(d);
            }
            case "HEADER_SEQUENCE" -> {
                d.setSequenceNo(2L);
                seal(d);
            }
            case "HEADER_VERSION" -> {
                d.setPayload(d.getPayload().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
                seal(d);
            }
            case "DUPLICATE_KEY" -> {
                d.setPayload(
                        d.getPayload()
                                .replace(
                                        "\"schemaVersion\":1",
                                        "\"schemaVersion\":1,\"schemaVersion\":1"));
                seal(d);
            }
            case "UTF8_LIMIT" -> {
                d.setPayload(
                        d.getPayload()
                                .replace(
                                        "\"handleResult\":null",
                                        "\"handleResult\":\"" + "汉".repeat(1400) + "\""));
                seal(d);
            }
            default -> throw new AssertionError(invalid);
        }
        assertThrows(AccessDeniedException.class, () -> delivery.accept(d));
        assertTrue(consumed.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "eventId",
                "tenantId",
                "factoryCode",
                "orderId",
                "sequenceNo",
                "payload",
                "fingerprint",
                "issuedAt",
                "nonce"
            })
    void everyTransportFieldIsBoundByTheDedicatedWriteSignature(String field) throws Exception {
        var d = command();
        switch (field) {
            case "eventId" -> d.setEventId(43L);
            case "tenantId" -> d.setTenantId(2L);
            case "factoryCode" -> d.setFactoryCode("F2");
            case "orderId" -> d.setOrderId(11L);
            case "sequenceNo" -> d.setSequenceNo(2L);
            case "payload" -> d.setPayload(d.getPayload() + " ");
            case "fingerprint" -> d.setFingerprint("0".repeat(64));
            case "issuedAt" -> d.setIssuedAt(d.getIssuedAt() + 1);
            case "nonce" -> d.setNonce("different-nonce-12345678");
        }
        assertFalse(d.signatureMatches(KEY));
    }

    @Test
    void anAuthenticatedTransportCannotSwallowTheBusinessQuantityRejection() throws Exception {
        doThrow(new IllegalStateException("quantity rejected"))
                .when(tasks)
                .syncReworkInspection(any());
        assertThrows(IllegalStateException.class, () -> delivery.accept(command()));
        assertTrue(consumed.isEmpty());
    }

    @Test
    void theRealMvcRouteRequiresBothInnerMarkerAndWriteSignature() throws Exception {
        var d = command();
        MockMvc mvc = mvc();
        String body = json.writeValueAsString(d);
        mvc.perform(
                        post("/mesTask/internal/rework-inspection-delivery")
                                .header(SecurityConstants.FROM, SecurityConstants.FROM_IN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, consumed.size());
        consumed.clear();
        mvc.perform(
                        post("/mesTask/internal/rework-inspection-delivery")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        assertTrue(consumed.isEmpty());
        d.setSignature(null);
        mvc.perform(
                        post("/mesTask/internal/rework-inspection-delivery")
                                .header(SecurityConstants.FROM, SecurityConstants.FROM_IN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(d)))
                .andExpect(status().isForbidden());
        assertTrue(consumed.isEmpty());
    }

    private MockMvc mvc() {
        HttpServletRequest current =
                (HttpServletRequest)
                        Proxy.newProxyInstance(
                                HttpServletRequest.class.getClassLoader(),
                                new Class<?>[] {HttpServletRequest.class},
                                (p, m, a) ->
                                        m.invoke(
                                                ((ServletRequestAttributes)
                                                                RequestContextHolder
                                                                        .currentRequestAttributes())
                                                        .getRequest(),
                                                a));
        var factory = new AspectJProxyFactory(new MesReworkInspectionDeliveryController(delivery));
        factory.setProxyTargetClass(true);
        factory.addAspect(new MomSecurityInnerAspect(current));
        Object controller = factory.getProxy();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new DeniedAdvice())
                .build();
    }

    private MesReworkInspectionDeliveryDTO command() throws Exception {
        String raw =
                "{\"schemaVersion\":1,\"eventId\":42,\"tenantId\":1,\"factoryCode\":\"F1\",\"orderId\":10,\"sequenceNo\":1,\"result\":{\"requestId\":\"RW-1\",\"qmsTaskId\":700,\"inspectionType\":\"PROCEDURE\",\"result\":\"QUALIFIED\",\"totalNum\":5,\"usableNum\":5,\"handleNum\":5,\"handleResult\":null}}";
        var d =
                new MesReworkInspectionDeliveryDTO()
                        .setEventId(42L)
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setOrderId(10L)
                        .setSequenceNo(1L)
                        .setPayload(raw)
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce("test-nonce-1234567890");
        seal(d);
        return d;
    }

    private void seal(MesReworkInspectionDeliveryDTO d) throws Exception {
        d.setFingerprint(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(d.getPayload().getBytes(StandardCharsets.UTF_8))));
        d.setSignature(d.sign(KEY));
    }

    @RestControllerAdvice
    static class DeniedAdvice {
        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        public Map<String, Object> denied() {
            return Map.of("code", 403);
        }
    }
}
