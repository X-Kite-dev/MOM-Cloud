package com.kitex.mom.wms.dto;

import java.util.List;

/**
 * 操作台只传递可核实的分配和单据事实；ID和数量用字符串避免浏览器精度丢失。
 */
public record WmsProductionNoticeProgressDTO(int contractVersion, String tenantId, String factoryCode,
                                             String operatorCode, String warehouseCode, String warehouseName,
                                             String noticeId, String noticeState,
                                             String sourceDocumentId, Integer sourceRevision, String acceptanceStatus,
                                             Receipt receipt, SourceLinks sourceLinks,
                                             List<Line> lines, boolean canViewBills, List<Bill> bills) {

    public record Receipt(String requestId, String acceptedTime, String acceptedBy) {
    }

    /**
     * 原凭据中的关联记录完整性，不代表已回查MES来源有效性或完成实物执行。
     */
    public record SourceLinks(String status, int linkedLines, int totalLines) {
    }

    public record Line(String id, String sourceLineId, String materialCode, String materialName, String unit,
                       String planned, String allocated, String remaining) {
    }

    public record Bill(String id, String billNo, String state, String createdTime) {
    }
}
