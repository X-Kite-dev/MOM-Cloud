package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesReworkInspectionDeliveryDTO;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.mes.service.MesTaskService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * 服务认证只授权消费指定冻结原件；业务处置仍由原MES事务和累计数量合同决定。
 */
@Service
@RequiredArgsConstructor
public class MesReworkInspectionDeliveryService {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final MesTaskService tasks;

    @Value("${mom.qms-rework-result.delivery-secret:${MOM_QMS_REWORK_RESULT_DELIVERY_SECRET:}}")
    private String deliverySecret;

    private static boolean same(JsonNode node, String name, Long expected) {
        JsonNode value = node.path(name);
        return value.isIntegralNumber()
                && value.canConvertToLong()
                && Objects.equals(value.longValue(), expected);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AccessDeniedException(message);
    }

    public void accept(MesReworkInspectionDeliveryDTO d) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(
                d != null
                        && tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && factory != null
                        && !factory.isBlank()
                        && tenant.equals(d.getTenantId())
                        && factory.equals(d.getFactoryCode()),
                "复检投递组织身份不一致");
        long now = Instant.now().getEpochSecond();
        require(
                d.getEventId() != null
                        && d.getEventId() > 0
                        && d.getOrderId() != null
                        && d.getOrderId() > 0
                        && d.getSequenceNo() != null
                        && d.getSequenceNo() > 0
                        && d.getSequenceNo() <= 5000
                        && d.getPayload() != null
                        && d.getPayload().length() <= 4096
                        && d.getPayload().getBytes(StandardCharsets.UTF_8).length <= 4096
                        && d.getFingerprint() != null
                        && d.getFingerprint().matches("[0-9a-f]{64}")
                        && d.getIssuedAt() != null
                        && d.getIssuedAt() >= now - 90
                        && d.getIssuedAt() <= now + 15
                        && d.getNonce() != null
                        && d.getNonce().matches("[A-Za-z0-9_-]{16,64}")
                        && d.signatureMatches(deliverySecret),
                "复检投递签名无效、过期或配置未就绪");
        MesReworkInspectionResultDTO result;
        try {
            require(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    d.getPayload()
                                                            .getBytes(StandardCharsets.UTF_8)))
                            .equals(d.getFingerprint()),
                    "复检原文指纹不一致");
            JsonNode j = JSON.readTree(d.getPayload());
            require(
                    j.isObject()
                            && same(j, "schemaVersion", 1L)
                            && same(j, "eventId", d.getEventId())
                            && same(j, "tenantId", d.getTenantId())
                            && j.path("factoryCode").isTextual()
                            && factory.equals(j.path("factoryCode").textValue())
                            && same(j, "orderId", d.getOrderId())
                            && same(j, "sequenceNo", d.getSequenceNo())
                            && j.path("result").isObject(),
                    "复检原文与投递身份不一致");
            result = JSON.treeToValue(j.get("result"), MesReworkInspectionResultDTO.class);
            require(
                    result.getRequestId() != null
                            && !result.getRequestId().isBlank()
                            && result.getRequestId().length() <= 120
                            && result.getQmsTaskId() != null
                            && result.getQmsTaskId() > 0
                            && Set.of("PROCEDURE", "PRODUCT")
                            .contains(Objects.toString(result.getInspectionType(), ""))
                            && Set.of(
                                    "QUALIFIED",
                                    "UN_QUALIFIED",
                                    "RELEASE",
                                    "RECEIVE",
                                    "EXEMPTION")
                            .contains(Objects.toString(result.getResult(), "")),
                    "复检原文业务身份无效");
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            throw new AccessDeniedException("复检投递原文不可核验");
        }
        // 原服务负责事务、实际返工/QMS登记核验、业务锁→根行锁、数量与库存守恒。
        tasks.syncReworkInspection(result);
    }
}
