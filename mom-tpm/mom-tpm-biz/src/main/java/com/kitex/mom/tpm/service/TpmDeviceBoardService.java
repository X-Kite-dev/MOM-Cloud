package com.kitex.mom.tpm.service;


import com.kitex.mom.tpm.dto.*;

import java.util.List;

public interface TpmDeviceBoardService {

    RepairSummaryDto repairSummary();

    EquipmentUsageDto equipmentUsage();

    EquipmentOverviewDto equipmentOverview();

    List<EquipmentBoardSortDto> failureRanking();

    List<EquipmentBoardSortDto> downtimeRanking();

    SparePartsReplacementDto sparePartsReplacement();

    MaintenanceStatusDto maintenanceStatus();

    InspectionStatusDto inspectionStatus();
}