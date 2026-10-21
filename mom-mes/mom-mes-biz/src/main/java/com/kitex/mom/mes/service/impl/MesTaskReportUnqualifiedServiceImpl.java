package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.dto.MesUnqualifiedTimingRecordDTO;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesProductionNgSourceDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesTaskReportUnqualifiedMapper;
import com.kitex.mom.mes.mapper.MesProductionNgCaptureMapper;
import com.kitex.mom.mes.entity.MesProductionNgCaptureEntity;
import com.kitex.mom.mes.service.MesTaskReportUnqualifiedService;
import com.kitex.mom.mes.service.MesProductionMaterialOperationService;
import com.kitex.mom.mes.service.MesProductionMaterialReversalService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 生产报工-不合格品上报
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskReportUnqualifiedServiceImpl
        extends ServiceImpl<MesTaskReportUnqualifiedMapper, MesTaskReportUnqualifiedEntity>
        implements MesTaskReportUnqualifiedService {

    private final RemoteMaterialService materials;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesProductionNgSourceCodec sourceCodec;
    private final MesProductionMaterialOperationService materialOperations;
    private final MesProductionMaterialReversalService materialReversals;
    // 仅原不可变采集链接查询，不能依赖采集业务服务，否则生产调用链形成环。
    private final MesProductionNgCaptureMapper inventoryCaptures;
    private final MesProcessTimingGuard processTimingGuard;
    private final MesReportNodeGate reportNodeGate;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void saveFrozenSource(MesTaskReportUnqualifiedEntity report, MesTaskArrangeEntity arrange, ReportModeEnum mode,
                                 String originalInputJson) {
        String factory = factoryAccess.require("write");
        require(Boolean.TRUE.equals(baseMapper.sourceReady()), "不良来源与标识保护未就绪，请先完成累计V2第286节");
        var actor = SecurityUtils.getUser();
        require(report != null && arrange != null && report.getId() == null
                && Objects.equals(factory, arrange.getFactoryCode())
                && Objects.equals(TenantContextHolder.getTenantId(), arrange.getTenantId()), "只能采集当前工厂真实新不良来源");
        // 不良也是实际生产事实：不能借NG入口跳过原节点或逐标识前序；原号恢复在调用前处理。
        reportNodeGate.validate(arrange, mode, report.getTransferCode(), false);
        // 仅新事实读取一次物料；历史回放和查询绝不从当前主数据补单位。
        var response = materials.productionMeasure(arrange.getMatCode());
        require(response != null && response.getData() != null, "无法获取真实物料基本单位，未登记不良原来源");
        report.setId(IdWorker.getId());
        report.setTenantId(TenantContextHolder.getTenantId());
        report.setCapturedActorId(actor.getId());
        report.setCreateBy(actor.getUsername());
        report.setDelFlag("0");
        // 所有新不良来源走同一时间门；不接受调用者传入的接受时刻或时间证据。
        var timing = processTimingGuard.validate(arrange, mode, report.getTransferCode(), "NORMAL", 1, null,
                MesTimingExceptionFacts.ng(report.getRequestId(), actor.getId(), actor.getUsername(),
                        report.getUnqualifiedNum(), originalInputJson));
        report.setTimingAcceptedAt(timing.acceptedAt());
        report.setTimingEvidenceJson(timing.evidenceJson());
        // PG timestamp保存微秒，写前明确精度，禁止提交后静默改变原证据时间。
        report.setReportTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        sourceCodec.freeze(report, arrange, response.getData(), mode);
        require(save(report), "不良原来源未保存，整次上报回滚");
        var persisted = getOne(scoped(factory).eq(MesTaskReportUnqualifiedEntity::getId, report.getId()));
        require(persisted != null && Objects.equals(persisted.getSourceSnapshot(), report.getSourceSnapshot())
                && Objects.equals(persisted.getTimingAcceptedAt(), report.getTimingAcceptedAt())
                && Objects.equals(persisted.getTimingEvidenceJson(), report.getTimingEvidenceJson())
                && Objects.equals(persisted.getIdentificationSnapshot(), report.getIdentificationSnapshot())
                && Objects.equals(persisted.getIdentificationFingerprint(), report.getIdentificationFingerprint())
                && Objects.equals(persisted.getSourceFingerprint(), report.getSourceFingerprint()), "不良原来源必要写回读不一致");
        sourceCodec.verify(persisted);
    }

    @Override
    public void verifySource(MesTaskReportUnqualifiedEntity report) {
        sourceCodec.verify(report);
    }

    @Override
    @Transactional(readOnly = true)
    public MesProductionNgSourceDTO source(String requestId) {
        String factory = factoryAccess.require("read");
        require(requestId != null && requestId.length() >= 8 && requestId.length() <= 64, "原不良请求号长度须为8至64");
        var row = getOne(scoped(factory).eq(MesTaskReportUnqualifiedEntity::getRequestId, requestId));
        require(row != null, "当前租户工厂未找到原不良请求结果，不代表可以另号重投");
        boolean frozen = sourceCodec.verify(row);
        var result = new MesProductionNgSourceDTO();
        result.setSourceUnqualifiedReportId(row.getId().toString());
        result.setIdentificationVersion(row.getIdentificationVersion());
        result.setIdentificationSnapshot(row.getIdentificationSnapshot());
        result.setIdentificationFingerprint(row.getIdentificationFingerprint());
        result.setRequestId(row.getRequestId());
        result.setTimingAcceptedAt(textTime(row.getTimingAcceptedAt()));
        result.setTimingEvidenceJson(row.getTimingEvidenceJson());
        result.setTenantId(row.getTenantId().toString());
        result.setFactoryCode(row.getFactoryCode());
        result.setArrangeCode(row.getArrangeCode());
        result.setMaterialCode(row.getMatCode());
        result.setQuantity(row.getUnqualifiedNum() == null ? null : row.getUnqualifiedNum().stripTrailingZeros().toPlainString());
        result.setUnit(row.getReportUnit());
        result.setReportMode(row.getReportMode());
        result.setSourceVersion(row.getSourceVersion());
        result.setSourceFingerprint(row.getSourceFingerprint());
        result.setSourceSnapshot(row.getSourceSnapshot());
        result.setSourceState(frozen ? "FROZEN_UNSTOCKED" : "LEGACY_UNPROVEN");
        result.setInventoryRecorded(false);
        if (frozen) {
            var capture = inventoryCaptures.selectOne(new LambdaQueryWrapper<MesProductionNgCaptureEntity>()
                    .eq(MesProductionNgCaptureEntity::getTenantId, row.getTenantId())
                    .eq(MesProductionNgCaptureEntity::getFactoryCode, factory)
                    .eq(MesProductionNgCaptureEntity::getSourceUnqualifiedReportId, row.getId()));
            if (capture != null) {
                require(Objects.equals(capture.getRequestId(), row.getRequestId())
                        && Objects.equals(capture.getActorId(), row.getCapturedActorId())
                        && "ISOLATED".equals(capture.getStatus())
                        && "MES_PRODUCTION_NG_INVENTORY_V1".equals(capture.getEvidenceVersion())
                        && capture.getStockId() != null && capture.getInflowId() != null && capture.getCaseId() != null,
                        "实际NG隔离原链接不完整，不能回落为未库存化");
                result.setInventoryCapture(new MesProductionNgSourceDTO.InventoryLink(capture.getId().toString(),
                        capture.getStockId().toString(), capture.getInflowId().toString(), capture.getCaseId().toString(),
                        capture.getEvidenceVersion(), capture.getEvidenceFingerprint()));
                result.setInventoryRecorded(true);
                result.setSourceState("FROZEN_ISOLATED");
            }
        }
        var operation = materialOperations.original(row);
        result.setMaterialAccountingMode(operation == null ? "NONE" : operation.getAccountingMode());
        if (operation != null) {
            result.setMaterialOperationId(operation.getId().toString());
            result.setMaterialAccountingVersion(operation.getCommandVersion());
            result.setMaterialAccountingFingerprint(operation.getCommandFingerprint());
            result.setMaterialAccountingSnapshot(operation.getCommandSnapshot());
            result.setMaterialReversal(materialReversals.attached(operation));
        }
        return result;
    }

    private LambdaQueryWrapper<MesTaskReportUnqualifiedEntity> scoped(String factory) {
        return new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
                .eq(MesTaskReportUnqualifiedEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportUnqualifiedEntity::getFactoryCode, factory);
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<MesUnqualifiedTimingRecordDTO> timingPage(long current, long size, String taskCode,
                                                         String arrangeCode, String identifier) {
        require(current >= 1 && current <= 1_000_000 && size >= 1 && size <= 100, "不良证据分页范围无效，每页最多100条");
        String factory = factoryAccess.require("read");
        String task = searchValue(taskCode), arrange = searchValue(arrangeCode), code = searchValue(identifier);
        var query = scoped(factory)
                .eq(task != null, MesTaskReportUnqualifiedEntity::getTaskCode, task)
                .eq(arrange != null, MesTaskReportUnqualifiedEntity::getArrangeCode, arrange)
                .and(code != null, by -> by.eq(MesTaskReportUnqualifiedEntity::getTransferCode, code)
                        .or().apply("btrim(submitted_identifier) = {0}", code))
                .orderByDesc(MesTaskReportUnqualifiedEntity::getReportTime, MesTaskReportUnqualifiedEntity::getId);
        return page(new Page<MesTaskReportUnqualifiedEntity>(current, size), query).convert(row -> {
            // 分页不把完整SOURCE原文交给页面，但必须先核原行与来源/双标识SHA，不能仅验显示投影。
            sourceCodec.verify(row);
            return new MesUnqualifiedTimingRecordDTO(
                row.getId().toString(), row.getRequestId(), row.getTenantId().toString(), row.getFactoryCode(),
                row.getTaskCode(), row.getArrangeCode(), row.getProduceTaskCode(), row.getMatCode(), row.getMatName(),
                row.getTransferCode(), row.getUnqualifiedNum() == null ? null : row.getUnqualifiedNum().toPlainString(),
                row.getReportUnit(), row.getType(), row.getWorkerName(), textTime(row.getReportTime()),
                textTime(row.getTimingAcceptedAt()), row.getTimingEvidenceJson(), row.getSubmittedIdentifier(),
                row.getIdentificationVersion(), row.getIdentificationSnapshot(), row.getIdentificationFingerprint());
        });
    }

    private static String searchValue(String value) {
        if (value == null || value.isBlank()) return null;
        require(value.length() <= 200, "查询条件最多200字符");
        return value.trim();
    }

    private static String textTime(LocalDateTime value) { return value == null ? null : value.toString(); }
}
