package com.kitex.mom.mes.dto;

import java.util.List;

/** 退回后重新发出的只读办理依据；不代表预留、质量放行或实收确认。 */
public final class MesWipDestinationChangeDTO {
    private MesWipDestinationChangeDTO() {}

    public record Issue(String transferId, String toLocationCode, String quantity, String status) {}

    public record Receipt(
            String receiptId,
            String stockId,
            String quantity,
            String currentQuantity,
            String locationCode,
            String currentLocationCode,
            boolean canDispatch,
            List<Issue> issues,
            boolean hasMoreIssues) {}

    public record View(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String transferId,
            String revision,
            String status,
            boolean indivisible,
            String resumeStatus,
            String fromLocationCode,
            String toLocationCode,
            String unit,
            String quantity,
            String remainingQuantity,
            String receivedQuantity,
            String returnedQuantity,
            String quarantinedQuantity,
            String lostQuantity,
            List<Receipt> receipts,
            boolean hasMore,
            String nextAfterId) {}
}
