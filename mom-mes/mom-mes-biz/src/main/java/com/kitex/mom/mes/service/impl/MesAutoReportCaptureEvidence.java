package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONConfig;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 设备原采集证据的唯一编码器，不做查询、不解析原报文，也不签发报工许可。
 *
 * <p>源输入、原实名、换算口径和冻结节点分别保存，再合成不可变全文。
 * 后续处理只能核对这些原值；不能从当前配置补出历史的产品或实例。</p>
 */
public final class MesAutoReportCaptureEvidence {

    public static final String VERSION = "MES_AUTO_EVENT_V2";
    private static final int MAX_BYTES = 131072;

    private MesAutoReportCaptureEvidence() { }

    /** 原输入编码与旧字段trim/无损十进制规则一致；新增外码保留原值。 */
    public static String inputSnapshot(MesAutoReportEventDTO input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", input.getCaptureVersion());
        body.put("sourceEventId", trimmed(input.getSourceEventId()));
        body.put("deviceCode", trimmed(input.getDeviceCode()));
        body.put("eventIdentifier", trimmed(input.getEventIdentifier()));
        body.put("eventTime", input.getEventTime() == null ? null : input.getEventTime().toString());
        body.put("value", decimal(input.getValue()));
        body.put("arrangeCode", trimmed(input.getArrangeCode()));
        body.put("transferCode", trimmed(input.getTransferCode()));
        body.put("externalIdentifier", input.getExternalIdentifier());
        body.put("expectedBindingId", input.getExpectedBindingId());
        body.put("expectedBindingVersion", input.getExpectedBindingVersion());
        body.put("rawPayload", input.getRawPayload());
        body.put("factoryCode", input.getFactoryCode());
        return JSONUtil.toJsonStr(body, JSONConfig.create().setIgnoreNullValue(false));
    }

    /** 用落库原字段恢复原输入；不借成功处理结果反推采集事实。 */
    public static MesAutoReportEventDTO originalInput(MesAutoReportEventEntity event) {
        MesAutoReportEventDTO input = new MesAutoReportEventDTO();
        input.setCaptureVersion(event.getCaptureVersion());
        input.setSourceEventId(event.getSourceEventId());
        input.setDeviceCode(event.getDeviceCode());
        input.setEventIdentifier(event.getEventIdentifier());
        input.setEventTime(event.getSourceEventTime());
        input.setValue(event.getRawValue());
        input.setArrangeCode(event.getRequestedArrangeCode());
        input.setTransferCode(event.getTransferCode());
        input.setExternalIdentifier(event.getExternalIdentifier());
        input.setExpectedBindingId(text(event.getExpectedBindingId()));
        input.setExpectedBindingVersion(event.getExpectedBindingVersion());
        input.setRawPayload(event.getRawPayload());
        input.setFactoryCode(event.getFactoryCode());
        return input;
    }

    /** 只冻结实际换算/匹配口径；启停和累计游标不是原单件输入。 */
    public static String ruleSnapshot(MesAutoReportRuleEntity rule) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "MES_AUTO_RULE_CAPTURE_V1");
        body.put("id", text(rule.getId()));
        body.put("tenantId", text(rule.getTenantId()));
        body.put("factoryCode", rule.getFactoryCode());
        body.put("ruleCode", rule.getRuleCode());
        body.put("deviceCode", rule.getDeviceCode());
        body.put("eventIdentifier", rule.getEventIdentifier());
        body.put("quantityMode", rule.getQuantityMode());
        body.put("quantityMultiplier", decimal(rule.getQuantityMultiplier()));
        body.put("pulseQuantity", decimal(rule.getPulseQuantity()));
        body.put("maxSingleQuantity", decimal(rule.getMaxSingleQuantity()));
        body.put("maxEventDelayMinutes", rule.getMaxEventDelayMinutes());
        body.put("routeCode", rule.getRouteCode());
        body.put("worksiteCode", rule.getWorksiteCode());
        return JSONUtil.toJsonStr(body, JSONConfig.create().setIgnoreNullValue(false));
    }

    /** 新动态事件只接受能从本事件独立证明的一件产出，不推算累计计数的产品成员。 */
    public static BigDecimal singleQuantity(MesAutoReportEventDTO input, MesAutoReportRuleEntity rule) {
        BigDecimal multiplier = Objects.requireNonNullElse(rule.getQuantityMultiplier(), BigDecimal.ONE);
        BigDecimal quantity;
        if ("PULSE".equals(rule.getQuantityMode())) {
            quantity = Objects.requireNonNullElse(input.getValue(), BigDecimal.ONE)
                    .multiply(Objects.requireNonNullElse(rule.getPulseQuantity(), BigDecimal.ONE)).multiply(multiplier);
        } else {
            require("DELTA".equals(rule.getQuantityMode()) && input.getValue() != null,
                    "动态单件事件只支持独立增量/脉冲；累计计数缺成员事实，不能猜是哪件产品");
            quantity = input.getValue().multiply(multiplier);
        }
        require(multiplier.signum() > 0 && (input.getValue() == null || input.getValue().signum() > 0)
                && (rule.getPulseQuantity() == null || rule.getPulseQuantity().signum() > 0)
                && BigDecimal.ONE.compareTo(quantity) == 0
                && (rule.getMaxSingleQuantity() == null || quantity.compareTo(rule.getMaxSingleQuantity()) <= 0),
                "动态事件原换算必须恰好一件，不能批量计数套用一个SN");
        return quantity;
    }

    /** 合成可逐字段复核的全文；字符串嵌套保留原JSON字节，不仅存摘要。 */
    public static String captureSnapshot(MesAutoReportEventEntity event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", VERSION);
        body.put("tenantId", text(event.getTenantId()));
        body.put("factoryCode", event.getFactoryCode());
        body.put("actorId", text(event.getCaptureActorId()));
        body.put("actorCode", event.getCaptureActorCode());
        body.put("inputSnapshot", inputSnapshot(originalInput(event)));
        body.put("canonicalIdentifier", event.getCaptureCanonicalIdentifier());
        body.put("ruleId", text(event.getCaptureRuleId()));
        body.put("ruleSnapshot", event.getCaptureRuleSnapshot());
        body.put("policySnapshot", event.getCapturePolicySnapshot());
        String snapshot = JSONUtil.toJsonStr(body, JSONConfig.create().setIgnoreNullValue(false));
        require(snapshot.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
                "原采集完整证据超过128KiB，须核查配置，禁止截断原事实");
        return snapshot;
    }

    /** 原字段、全文和摘要必须同时一致；摘要不是授权凭证。 */
    public static void verifyStored(MesAutoReportEventEntity event) {
        require(event != null && VERSION.equals(event.getCaptureVersion()) && event.getTenantId() != null
                && event.getCaptureActorId() != null && trimmed(event.getCaptureActorCode()) != null
                && event.getCaptureRuleId() != null && trimmed(event.getCaptureCanonicalIdentifier()) != null
                && event.getCapturePolicySnapshot() != null && event.getCaptureRuleSnapshot() != null,
                "设备事件缺少完整原采集事实，禁止按当前外码补猜实例");
        require(Objects.equals(event.getCaptureSnapshot(), captureSnapshot(event))
                && Objects.equals(event.getCaptureFingerprint(), fingerprint(event.getCaptureSnapshot())),
                "设备原采集全文或摘要已改变，请人工核对原事件");
    }

    public static String fingerprint(String snapshot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((VERSION + ":" + snapshot).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }

    public static Long instanceId(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "原实例ID必须为完整正整数文本");
        try { return Long.valueOf(value); }
        catch (NumberFormatException exception) { throw new CheckedException("原实例ID超出Long容量"); }
    }

    private static String trimmed(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String text(Long value) { return value == null ? null : value.toString(); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }
}
