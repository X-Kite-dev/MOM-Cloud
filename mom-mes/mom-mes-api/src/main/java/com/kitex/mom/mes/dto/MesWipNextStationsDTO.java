package com.kitex.mom.mes.dto;

import java.util.List;

/** 冻结路线直接下一站的只读参考，不替代生产门禁或物理库位。 */
public final class MesWipNextStationsDTO {
    private MesWipNextStationsDTO() {}

    public static final String SCHEMA = "MES_WIP_NEXT_STATIONS@1";

    public record Operation(
            String operationId,
            String operationCode,
            String nodeCode,
            String processCode,
            String processName) {}

    public record Station(
            String arrangeId,
            String arrangeCode,
            String operationCode,
            String nodeCode,
            String processName,
            String status,
            String workshopCode,
            String workshopName,
            String actualWorksiteCode,
            String plannedWorksiteCode,
            String plannedWorksiteName,
            String worksiteCodes,
            String plannedStartTime,
            String plannedEndTime) {}

    public record View(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String stockId,
            String afterId,
            String observedAt,
            String availability,
            String orderCode,
            Operation source,
            List<Operation> nextOperations,
            List<Station> stations,
            String nextAfterId) {
        public View {
            nextOperations = List.copyOf(nextOperations);
            stations = List.copyOf(stations);
        }
    }

    public static final String TRANSFER_SCHEMA = "MES_WIP_TRANSFER_NEXT_STATIONS@1";

    /** routing.stockId仅为冻结原库存引用，不表示当前库存可用。 */
    public record TransferView(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String transferId,
            String transferRevision,
            String status,
            String quantity,
            String remainingQuantity,
            String unit,
            String fromLocationCode,
            String toLocationCode,
            String sourceStockId,
            String sourceReportId,
            String dispatchOperationId,
            String dispatchRequestId,
            String dispatchFingerprint,
            MesWipDispatchTargetDTO.Selection boundTarget,
            View routing) {}
}
