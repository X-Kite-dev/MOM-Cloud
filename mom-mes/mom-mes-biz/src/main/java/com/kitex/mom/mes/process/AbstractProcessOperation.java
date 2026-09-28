package com.kitex.mom.mes.process;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.dto.MesWarehouseUseDTO;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.enums.MesTaskTypeEnum;
import com.kitex.mom.mes.enums.MesWarehouseMatDetailTypeEnum;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractProcessOperation implements ProcessOperation {


    protected final MesWarehouseLiveStockService liveStockService;

    protected final RemoteParamService remoteParamService;

    protected final RemoteMaterialService matService;

    private final MesWarehouseFeedService feedService;

    /**
     * 各报工模式共用的报工记录构造，避免模式之间形成不同的数据口径。
     */
    protected MesTaskReportEntity createReport(MesTaskReportVO vo, MomUser currentUser) {
        boolean integration = "API".equals(vo.getType());
        String workerCode = integration || currentUser == null
                ? vo.getOperatorCode() : currentUser.getUsername();
        String workerName = integration || currentUser == null
                ? vo.getOperatorName() : currentUser.getName();
        if (StrUtil.isBlank(workerCode) || StrUtil.isBlank(workerName)) {
            throw new CheckedException("报工操作主体不可识别");
        }
        MesTaskReportEntity report = new MesTaskReportEntity()
                .setRequestId(vo.getRequestId())
                .setStatus(ReportStatusEnum.EFFECTIVE.name())
                .setArrangeCode(vo.getArrangeCode())
                .setTaskCode(vo.getTaskCode())
                .setProduceTaskCode(vo.getProduceTaskCode())
                .setMatCode(vo.getMatCode())
                .setMatName(vo.getMatName())
                .setMatSpec(vo.getMatSpec())
                .setTransferCode(vo.getTransferCode())
                .setSubmittedIdentifier(vo.getSubmittedIdentifier())
                .setIdentifierMappingId(vo.getIdentifierMappingId())
                .setIdentifierType(vo.getIdentifierType())
                .setIdentifierResolutionSnapshot(vo.getIdentifierResolutionSnapshot())
                .setExtensionEvidenceSnapshot(vo.getExtensionEvidenceSnapshot())
                .setReportNum(vo.getPassNum())
                .setType(vo.getType())
                .setSourceEventId(vo.getSourceEventId())
                .setSourceEventTime(vo.getSourceEventTime())
                .setManualOverrideReason(vo.getManualOverrideReason())
                .setQualityGateMode(vo.getQualityGateMode())
                .setQualityGateStatus(vo.getQualityGateStatus())
                .setQualityGateMessage(vo.getQualityGateMessage())
                .setQualityTaskIds(vo.getQualityTaskIds())
                .setQualityEvidenceFingerprint(vo.getQualityEvidenceFingerprint())
                .setQualityEvidenceSnapshot(vo.getQualityEvidenceSnapshot())
                .setQualityQuantityStatus(vo.getQualityQuantityStatus())
                .setQualityReleasedNum(vo.getQualityReleasedNum())
                .setQualityPendingNum(vo.getQualityPendingNum())
                .setQualityRejectedNum(vo.getQualityRejectedNum())
                .setQualitySyncTime(LocalDateTime.now())
                .setDeviceGateStage(vo.getDeviceGateStage())
                .setDeviceGateAllowed(vo.getDeviceGateAllowed())
                .setDeviceGateState(vo.getDeviceGateState())
                .setDeviceGateStateName(vo.getDeviceGateStateName())
                .setDeviceGateMessage(vo.getDeviceGateMessage())
                .setDeviceGateCheckTime(vo.getDeviceGateCheckTime())
                .setReportTime(vo.getSourceEventTime() == null ? LocalDateTime.now() : vo.getSourceEventTime())
                .setWorkerCode(workerCode)
                .setWorkerName(workerName)
                .setDeviceCode(vo.getDeviceCode())
                .setDeviceName(vo.getDeviceName())
                .setFactoryCode(vo.getFactoryCode());
        report.setId(IdWorker.getId());
        return report;
    }

    protected String operatorName(MesTaskReportVO vo, MomUser currentUser) {
        if ("API".equals(vo.getType())) {
            return vo.getOperatorName();
        }
        return currentUser == null
                ? StrUtil.blankToDefault(vo.getOperatorName(), "设备自动报工") : currentUser.getName();
    }

    /**
     * 通用物料消耗
     *
     * @param params
     */
    @Override
    public void use(MesTaskReportVO params) {
        log.info("通用物料消耗");

        String backFlushFlag = remoteParamService.getByKey("BACK_FLUSH").getData();
        MesTaskArrangeEntity arrange = params.getTaskArrange();
        MesTaskEntity task = params.getTask();
        // 倒冲则直接在线边库中扣除
        if ("1".equals(backFlushFlag)) {
            ProcessProcedureDTO currentProcess = MesTaskUtils.getCurrentProcess(params.getTask(), params.getTaskArrange().getRouteCode());
            // 工序的物料信息
            List<ProcessProcedureDTO.ProcessProcedureMatDTO> mdmProcessProcedureMatList = currentProcess.getMdmProcessProcedureMatList();
            if (mdmProcessProcedureMatList == null || mdmProcessProcedureMatList.isEmpty()) {
                return;
            }
            for (ProcessProcedureDTO.ProcessProcedureMatDTO item : mdmProcessProcedureMatList) {
                backflush(params, arrange, item);
            }
        } else {
            // 消耗投料
            feedService.use(new MesWarehouseUseDTO()
                    .setReportId(params.getReportId())
                    .setReportRequestId(params.getRequestId())
                    .setReportNum(params.getPassNum())
                    .setArrangeCode(arrange.getCode())
                    .setProcessRouteCode(task.getProcessRouteCode())
                    .setRouteCode(arrange.getRouteCode())
                    .setSnapshot(task.getProcessRouteSnapshot())
                    .setBomSnapshot(task.getBomSnapshot())
                    .setFactoryCode(arrange.getFactoryCode()));
        }


    }

    private void backflush(MesTaskReportVO params, MesTaskArrangeEntity arrange,
                           ProcessProcedureDTO.ProcessProcedureMatDTO requirement) {
        MesMaterialSubstituteResolver.Policy policy = MesMaterialSubstituteResolver.resolvePolicy(
                params.getTask().getBomSnapshot(), requirement.getMaterialCode());
        BigDecimal totalRequirement = params.getPassNum().multiply(requirement.getQuantity());
        BigDecimal remainingRequirement = totalRequirement;
        List<MesMaterialSubstituteResolver.Candidate> candidates = policy.candidates();
        if (!policy.allowMixed()) {
            candidates = policy.candidates().stream()
                    .filter(candidate -> availableBackflush(arrange, candidate.actualMatCode())
                            .compareTo(totalRequirement.multiply(candidate.ratio())) >= 0)
                    .findFirst().map(List::of)
                    .orElseThrow(() -> new CheckedException("需求物料[" + requirement.getMaterialCode()
                            + "]不允许混用，主料及任一替代料库存均无法单独满足倒冲"));
        }
        for (MesMaterialSubstituteResolver.Candidate candidate : candidates) {
            if (remainingRequirement.compareTo(BigDecimal.ZERO) == 0) {
                break;
            }
            BigDecimal availableActual = availableBackflush(arrange, candidate.actualMatCode());
            if (availableActual.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal actualNeeded = remainingRequirement.multiply(candidate.ratio());
            BigDecimal consumeActual = availableActual.min(actualNeeded);
            BigDecimal satisfiedRequirement = consumeActual.compareTo(actualNeeded) == 0
                    ? remainingRequirement
                    : consumeActual.divide(candidate.ratio(), 12, RoundingMode.DOWN);
            if (satisfiedRequirement.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            MdmMaterialEntity actualMaterial = matService.getDetails(candidate.actualMatCode()).getData();
            if (actualMaterial == null || !Boolean.TRUE.equals(actualMaterial.getStatus())) {
                throw new CheckedException("倒冲物料[" + candidate.actualMatCode() + "]不存在或未启用");
            }
            MesWarehouseLiveStockInoutDTO out = new MesWarehouseLiveStockInoutDTO();
            out.setBillId(params.getReportId());
            out.setBillNo(arrange.getCode());
            out.setResourceNo(params.getRequestId());
            out.setProduceTaskCode(arrange.getProduceTaskCode());
            out.setAllowUnassignedProduceTask(Boolean.TRUE);
            out.setRequirementMatCode(requirement.getMaterialCode());
            out.setRequirementMatName(requirement.getMaterialName());
            out.setSubstituteFlag(candidate.substitute());
            out.setSubstituteRatio(candidate.ratio());
            out.setMatCode(actualMaterial.getMaterialCode());
            out.setMatName(actualMaterial.getMaterialName());
            out.setMatSpec(actualMaterial.getSpecificationModel());
            out.setMatUnit(actualMaterial.getBasicUnit());
            out.setNum(consumeActual);
            out.setMatDetailType(MesWarehouseMatDetailTypeEnum.RAW.name());
            out.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
            out.setFactoryCode(arrange.getFactoryCode());
            liveStockService.outStock(out);
            remainingRequirement = remainingRequirement.subtract(satisfiedRequirement);
        }
        if (remainingRequirement.compareTo(BigDecimal.ZERO) > 0) {
            throw new CheckedException("需求物料[" + requirement.getMaterialCode() + "]倒冲库存不足，尚缺需求当量："
                    + remainingRequirement.setScale(6, RoundingMode.HALF_UP));
        }
    }

    private BigDecimal availableBackflush(MesTaskArrangeEntity arrange, String actualMatCode) {
        var request = new MesWarehouseLiveStockInoutDTO();
        request.setMatCode(actualMatCode);
        request.setFactoryCode(arrange.getFactoryCode());
        request.setProduceTaskCode(arrange.getProduceTaskCode());
        request.setAllowUnassignedProduceTask(true);
        request.setNum(BigDecimal.ONE);
        request.setMatDetailType(MesWarehouseMatDetailTypeEnum.RAW.name());
        request.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
        return liveStockService.previewOutCandidates(request)
                .stream().map(MesWarehouseLiveStockEntity::getNum).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    /**
     * 线边入库
     * 1. 根据当前产品和工序情况判断入库为在制品还是半成品完成，还是成品
     * 2. 入库类型
     * a. 在制品
     * b. 半成品完成
     * c. 成品
     *
     * @param params
     */
    @Override
    public void in(MesTaskReportVO params) {
        log.info("线边入库");
        // 生产任务
        MesTaskEntity task = params.getTask();
        // 派工工单
        MesTaskArrangeEntity arrange = params.getTaskArrange();
//        String nextRouteCode = routeWorking.getNextRouteCode();
        MesWarehouseLiveStockInoutDTO in = new MesWarehouseLiveStockInoutDTO();
        in.setBillId(params.getReportId());
        in.setSourceReportId(params.getReportId());
        in.setBillNo(arrange.getCode());
        in.setResourceNo(params.getRequestId());
        in.setProduceTaskCode(arrange.getProduceTaskCode());
        in.setRouteCode(arrange.getRouteCode());
        in.setRouteName(arrange.getRouteName());
        in.setMatCode(arrange.getMatCode());
        in.setMatName(arrange.getMatName());
        in.setMatSpec(arrange.getMatSpec());
        in.setMatBatch(arrange.getBatchNo());
        in.setMatBarcode(params.getTransferCode());
//        in.setMatUnit(arrange.getBasicUnit());
//            in.setMatBarcode();
        in.setNum(params.getPassNum());
        in.setInspectionStatus(params.getQualityQuantityStatus());
        in.setMatUnit(params.getMatUnit());
        in.setSiteCode(arrange.getRouteCode());
        in.setSiteName(arrange.getRouteName());

        if (Boolean.TRUE.equals(arrange.getLastRoute())) {
            in.setMatDetailType(MesTaskTypeEnum.PRODUCT.name().equals(task.getType())
                    ? MesWarehouseMatDetailTypeEnum.PRODUCT.name()
                    : MesWarehouseMatDetailTypeEnum.SEMI_FINISHED.name());
        } else {
            in.setMatDetailType(MesWarehouseMatDetailTypeEnum.WIP.name());
        }
        in.setBizType(WarehouseInBizTypeEnum.PROCESS_PRODUCT_IN.getCode());
        in.setFactoryCode(arrange.getFactoryCode());
        liveStockService.inStock(in);
    }
}
