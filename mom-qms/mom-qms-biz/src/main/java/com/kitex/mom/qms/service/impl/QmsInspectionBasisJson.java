package com.kitex.mom.qms.service.impl;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.dto.inspection.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 明确字段的本域V1原文；不修改MES现有来源摘要算法。null与空字符串保留区别。 */
final class QmsInspectionBasisJson {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private QmsInspectionBasisJson() {}

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new CheckedException("检验依据原文无法序列化", e);
        }
    }

    static <T> T read(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (Exception e) {
            throw new CheckedException("检验依据原文结构损坏", e);
        }
    }

    static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> scope(String type) {
        var m = new TreeMap<String, Object>();
        m.put("schemaVersion", 1);
        m.put("inspectionType", type);
        m.put("tenantId", TenantContextHolder.getTenantId());
        m.put("factoryCode", FactoryContextHolder.getFactoryCode());
        return m;
    }

    static String creation(InspectionProductCreateParam p) {
        var m = scope("PRODUCT");
        m.put("sourceRequestId", cn.hutool.core.util.StrUtil.trimToNull(p.getSourceRequestId()));
        m.put("sourceTaskCode", p.getSourceTaskCode());
        m.put("name", p.getName());
        m.put("matCode", p.getMatCode());
        m.put("matName", p.getMatName());
        m.put("matSpec", p.getMatSpec());
        m.put("standardId", p.getStandardId());
        m.put("batchNo", p.getBatchNo());
        m.put("processCode", p.getProcessCode());
        m.put("processName", p.getProcessName());
        m.put("num", p.getNum());
        m.put("inspectionNum", p.getInspectionNum());
        m.put("specification", p.getSpecification());
        m.put(
                "matProduceDate",
                p.getMatProduceDate() == null ? null : p.getMatProduceDate().getTime());
        m.put("deptCode", p.getDeptCode());
        m.put("orderId", p.getOrderId());
        m.put("orderNo", p.getOrderNo());
        m.put("matBarcode", p.getMatBarcode());
        return write(m);
    }

    static String creation(InspectionProcedureCreateParam p) {
        var m = scope("PROCEDURE");
        m.put("sourceRequestId", cn.hutool.core.util.StrUtil.trimToNull(p.getSourceRequestId()));
        m.put("taskCode", p.getTaskCode());
        m.put("sourceTaskCode", p.getSourceTaskCode());
        m.put("sourceRequestFingerprint", p.getSourceRequestFingerprint());
        m.put("labelType", p.getLabelType());
        m.put("name", p.getName());
        m.put("matCode", p.getMatCode());
        m.put("matName", p.getMatName());
        m.put("matSpec", p.getMatSpec());
        m.put("processCode", p.getProcessCode());
        m.put("processName", p.getProcessName());
        m.put("standardId", p.getStandardId());
        m.put("batchNo", p.getBatchNo());
        m.put("num", p.getNum());
        m.put("inspectionNum", p.getInspectionNum());
        m.put("deptCode", p.getDeptCode());
        m.put("orderNo", p.getOrderNo());
        m.put("orderId", p.getOrderId());
        m.put("matBarcode", p.getMatBarcode());
        return write(m);
    }
}
