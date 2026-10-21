package com.kitex.mom.mes.dto;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;

/**
 * 质量案件真实来源的独立类型合同；类型由MES权威事实确定，不相信客户端标签。
 */
public final class MesInventoryQualityOrigin {
    public static final String TRANSFER_RESOURCE = "MES_WIP_TRANSFER";
    public static final String PRODUCTION_RESOURCE = "MES_PRODUCTION_NG";

    private MesInventoryQualityOrigin() {
    }

    public static boolean isMesResource(String resourceType) {
        return TRANSFER_RESOURCE.equals(resourceType) || PRODUCTION_RESOURCE.equals(resourceType);
    }

    /**
     * 旧originType=NULL仅解析为TRANSFER，不补写旧raw/SHA或类型列。
     */
    public static String resourceType(MesInventoryQualityCaseEntity source) {
        if (source == null) throw new CheckedException("MES权威质量案件为空");
        boolean transfer = source.getOriginType() == null || "TRANSFER".equals(source.getOriginType());
        if (transfer && source.getTransferId() != null && source.getReceiptId() != null
                && source.getSourceReportId() != null && source.getSourceStockId() != null
                && source.getSourceUnqualifiedReportId() == null && source.getProductionNgCaptureId() == null
                && source.getInitialInflowId() == null) return TRANSFER_RESOURCE;
        if ("PRODUCTION_NG".equals(source.getOriginType()) && source.getTransferId() == null
                && source.getReceiptId() == null && source.getSourceReportId() == null && source.getSourceStockId() == null
                && source.getSourceUnqualifiedReportId() != null && source.getProductionNgCaptureId() != null
                && source.getInitialInflowId() != null) return PRODUCTION_RESOURCE;
        throw new CheckedException("MES案件来源不完整或两种来源混用，禁止降级为转运或普通WMS案件");
    }

    public static String requestId(MesInventoryQualityCaseEntity source) {
        return (PRODUCTION_RESOURCE.equals(resourceType(source)) ? "MES-NG-CASE:" : "MES-WIP-CASE:") + source.getId();
    }

    /**
     * 两种业务中该字段均是已提交初始隔离事实，不把NG流水称为转运实收回执。
     */
    public static Long initialProofId(MesInventoryQualityCaseEntity source) {
        return PRODUCTION_RESOURCE.equals(resourceType(source)) ? source.getInitialInflowId() : source.getReceiptId();
    }
}

