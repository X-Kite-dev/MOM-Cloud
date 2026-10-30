package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 投料独立20文本协议；先核可封存的原结构，首次执行再核业务值。
 */
@Component
@RequiredArgsConstructor
public class MesWarehouseFeedCommandCodec {
    public static final String COMMAND_VERSION = "MES_FEED_COMMAND_V1";
    public static final String EVIDENCE_VERSION = "MES_FEED_COMMAND_RESULT_V1";
    private static final Set<String> KEYS = Set.of("version", "requestId", "expectedTenantId",
            "expectedFactoryCode", "expectedActorUserId", "taskCode", "arrangeCode", "produceTaskCode",
            "materialCode", "requirementMaterialCode", "quantity", "batchNo", "barcode", "supplierCode",
            "sourceWarehouseCode", "sourceSiteCode", "receivingWorksiteCode", "reservationNo", "expectedUnit", "reason");
    private final MesPhysicalTransformCodec codec;

    /**
     * 不验证首次业务值，因此填错库位等完整正文仍可永久作废。
     */
    public JsonNode original(String raw, String factory) {
        JsonNode value = codec.objectDocument(raw, 64 * 1024);
        require(value.size() == KEYS.size(), "投料原文缺字段或有额外字段");
        KEYS.forEach(key -> require(value.has(key) && value.get(key).isTextual(), key + "必须是文本"));
        require(COMMAND_VERSION.equals(text(value, "version")), "投料命令版本未知");
        requestId(text(value, "requestId"));
        require(Objects.equals(codec.id(text(value, "expectedTenantId")), TenantContextHolder.getTenantId())
                        && Objects.equals(text(value, "expectedFactoryCode"), factory)
                        && Objects.equals(codec.id(text(value, "expectedActorUserId")), SecurityUtils.getUser().getId()),
                "只能在当前认证租户工厂以本人原文投料或作废");
        return value;
    }

    /**
     * 源筛选与接收工位是不同字段；名称/替代比例/状态/来源包不能由客户端指定。
     */
    public MesWarehouseFeedEntity feed(JsonNode value) {
        for (String key : Set.of("taskCode", "arrangeCode", "produceTaskCode", "materialCode", "batchNo", "expectedUnit"))
            codec.code(text(value, key), key, 100);
        for (String key : Set.of("requirementMaterialCode", "barcode", "supplierCode", "sourceWarehouseCode",
                "sourceSiteCode", "receivingWorksiteCode", "reservationNo"))
            if (!text(value, key).isEmpty()) codec.code(text(value, key), key, "barcode".equals(key) ? 200 : 100);
        codec.quantity(text(value, "quantity"), false, "真实投料原数量");
        codec.reason(text(value, "reason"));
        require(text(value, "reason").length() <= 500, "投料说明不能超过500字符");
        var feed = new MesWarehouseFeedEntity();
        feed.setTaskCode(text(value, "taskCode"));
        feed.setArrangeCode(text(value, "arrangeCode"));
        feed.setProduceTaskCode(text(value, "produceTaskCode"));
        feed.setMatCode(text(value, "materialCode"));
        feed.setRequirementMatCode(optional(value, "requirementMaterialCode"));
        feed.setMatBatch(text(value, "batchNo"));
        feed.setMatBarcode(optional(value, "barcode"));
        feed.setSupplierCode(optional(value, "supplierCode"));
        feed.setWarehouseCode(optional(value, "sourceWarehouseCode"));
        feed.setSiteCode(optional(value, "sourceSiteCode"));
        feed.setReservationNo(optional(value, "reservationNo"));
        feed.setFeedNum(new BigDecimal(text(value, "quantity")));
        return feed;
    }

    /**
     * 原号仅作永久请求身份，禁止空号、超长号及路径字符。
     */
    public void requestId(String value) {
        require(value != null && value.matches("[A-Za-z0-9_-]{8,64}"), "投料原请求号须为8至64位字母数字下划线或横线");
    }

    /**
     * 原文必须保留文本，不能把数值、空值或缺失字段静默转成字符串。
     */
    public String text(JsonNode value, String key) {
        require(value.path(key).isTextual(), "投料原文须保留文本字段：" + key);
        return value.get(key).textValue();
    }

    private String optional(JsonNode value, String key) {
        String text = text(value, key);
        return text.isEmpty() ? null : text;
    }
}
