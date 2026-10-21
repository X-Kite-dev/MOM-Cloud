package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWipTransferEntity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 版本化来源的明确字段集合，避免实体新增字段悄悄改变旧观察算法。
 * 四类V1事实固定当前原集合；工单/工序属于新V2，非持久processList不进入原工单事实。
 * 后续增加实物对象/位置/转换边时须升级新来源版本，不能扩大这里的旧版本集合。
 */
final class MesPhysicalTransformFactSchema {
    private static final String BASE = "id tenantId createBy createTime updateBy updateTime delFlag ";
    private static final Map<Class<?>, Set<String>> FIELDS = Map.of(
            MesWarehouseLiveStockEntity.class, fields("""
                qualityCaseId qualityDispositionReceiptId qualityOriginCaseId qualityReleaseReceiptId materialReceiptId
                sourceReportId physicalLocationCode physicalLocationName physicalConfirmedTime billNo produceTaskCode
                routeCode routeName matCode reservationNo requirementMatCode requirementMatName substituteFlag substituteRatio
                matName matSpec matBatch matUnit matProduceDate matValidPeriod matValidEndDate matBarcode matBarcodeImage
                supplierCode supplierName warehouseCode warehouseName containerCode containerName siteCode siteName inTime
                num status inspectionStatus judgeStatus matDetailType factoryCode
                """),
            MesTaskReportEntity.class, fields("""
                requestId status reverseRequestId reverseReason reverseTime reverseWorkerCode reverseWorkerName arrangeCode
                produceTaskCode taskCode workerCode workerName matCode matName matSpec reportNum effectiveReportNum
                correctedReportNum lastCorrectionTime correctionPermitId correctionPermitNo reportUnit reportMode reportTime
                transferCode submittedIdentifier identifierMappingId identifierType identifierResolutionSnapshot identifierBindingId
                identifierBindingVersion identifierNodeAction identifierBindingSnapshot extensionEvidenceSnapshot executionKind
                routeReturnId identifierRouteVersion carrierOperationId carrierCode type sourceEventId sourceEventTime manualOverrideReason
                qualityGateMode qualityGateStatus qualityGateMessage qualityTaskIds qualityEvidenceFingerprint qualityEvidenceSnapshot
                qualityQuantityStatus qualityReleasedNum qualityPendingNum qualityRejectedNum qualitySyncTime deviceGateStage
                deviceGateAllowed deviceGateState deviceGateStateName deviceGateMessage deviceGateCheckTime deviceCode deviceName factoryCode
                """),
            MesTaskArrangeEntity.class, fields("""
                code produceTaskCode taskCode matCode matName matSpec routeCode routeName routeType reportMode executionTemplateId
                executionTemplateCode executionTemplateVersion executionPolicySnapshot routeWorkCenter routeOrder predecessorCodes
                successorCodes overlapEnabled transferBatchQuantity transferMinutes lastRoute workerCode workerName dispatcherCode
                dispatcherName shiftCode shiftName dispatcherTime startTime finishTime finishHour status worksiteCodes worksiteNames
                worksiteTypes actualWorksiteCode plannedWorksiteCode plannedWorksiteName plannedWorkerCode plannedWorkerName
                plannedStartTime plannedEndTime plannedDeviceCode plannedDeviceName plannedResourceSlotNo plannedResourceGroupCode
                plannedResourceGroupName plannedResourceGroupCapacity plannedResourceGroupSlotNo plannedToolingCodes plannedToolingNames
                toolingAllocationSnapshot toolingLifeUsageSnapshot apsOperationSnapshot noticeNum plannedNum completeNum qualityReleasedNum
                materialGateRecordId unPassNum workshopCode workshopName batchNo wrapRuleCode wrapCode wrapCapacity factoryCode
                """),
            MesWipTransferEntity.class, fields("""
                factoryCode sourceStockId sourceReportId arrangeCode produceTaskCode matCode matName matBatch matBarcode matUnit
                reportMode indivisible fromLocationCode fromLocationName toLocationCode toLocationName quantity receivedQuantity
                returnedQuantity quarantinedQuantity lostQuantity remainingQuantity status revision resumeStatus dispatchUserId
                returnUserId holdUserId dispatchTime dueTime closedTime reason sourceSnapshot locationSnapshot
                """),
            MesTaskEntity.class, fields("""
                reworkMaterialPlanId code type planNo sourceRequestId sourceRequestFingerprint productionOrder matCode matName matSpec
                planStartDate planEndDate noticeNum dayNeedNum plannedNum completeNum inStockNum unInStockNum unPassNum alreadyNoticeNum
                startTime finishTime completionVersion status bomId bomSnapshot bomVersion processRouteId processRouteSnapshot
                processRouteCode engineeringReleaseId engineeringContentHash engineeringVersionNo engineeringBundleSnapshot
                processParameterStandardNo processParameterStandardVersion processParameterStandardSnapshot
                processParameterSnapshotFingerprint resourceType resourceId reworkRequestId sourceTaskCode sourceInspectionType
                sourceProduceTaskCode sourceInspectionTaskId sourceHandleRecordId reworkProcessCode reworkRouteNodeCode reworkTimingPolicySnapshot
                reworkReason reworkCallbackStatus reworkCallbackMessage
                reworkCallbackCount reinspectionTaskId reinspectionStatus reinspectionMessage reworkQualifiedNum reworkRejectedNum
                materialReservationSnapshot apsOperationSnapshot apsTaskId apsBatchId apsBatchNo apsBatchType apsBatchFingerprint
                workshopCode workshopName wrapCode wrapCapacity batchRuleCode transferCode factoryCode
                """),
            MesProduceTaskEntity.class, fields("""
                code taskCode processRouteId processRouteSnapshot processRouteCode processParameterStandardNo
                processParameterStandardVersion processParameterStandardSnapshot processParameterSnapshotFingerprint matCode matName
                matSpec routeCode routeName routeType routeWorkCenter routeOrder predecessorCodes successorCodes overlapEnabled
                transferBatchQuantity transferMinutes transferReleasedTime lastRoute worksiteCodes worksiteNames worksiteTypes
                plannedWorksiteCode plannedWorksiteName plannedResourceSlotNo plannedResourceGroupCode plannedResourceGroupName
                plannedResourceGroupCapacity plannedResourceGroupSlotNo plannedDeviceCode plannedDeviceName plannedWorkerCode
                plannedWorkerName plannedToolingCodes plannedToolingNames toolingAllocationSnapshot toolingLifeUsageSnapshot
                plannedStartTime plannedEndTime apsOperationSnapshot noticeNum plannedNum completeNum qualityReleasedNum unPassNum
                noticeDate status startTime finishTime workshopCode workshopName wrapCode reportType isInspection factoryCode
                """));

    private MesPhysicalTransformFactSchema() {
    }

    static Set<String> fields(Class<?> entityType) {
        Set<String> result = FIELDS.get(entityType);
        MesPhysicalTransformCodec.require(result != null, "没有该实体的明确来源字段版本，禁止反射追加事实");
        return result;
    }

    private static Set<String> fields(String declared) {
        var result = new HashSet<>(Set.of(BASE.trim().split("\\s+")));
        for (String field : declared.trim().split("\\s+"))
            MesPhysicalTransformCodec.require(result.add(field), "来源字段版本声明重复：" + field);
        return Set.copyOf(result);
    }
}
