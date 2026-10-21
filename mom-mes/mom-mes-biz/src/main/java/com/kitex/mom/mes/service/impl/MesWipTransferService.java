package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mes.dto.MesWipTransferCommand;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 实物发出即从线边库存转入独立在途账，接收才重新形成库存；不改变原工序与报工来源。
 */
@Service
@RequiredArgsConstructor
public class MesWipTransferService {
    private final MesActualFactoryAccessService access;
    private final MesWipTransferMapper transfers;
    private final MesWipTransferOperationMapper operations;
    private final MesInventoryQualityCaseMapper qualityCases;
    private final MesWipLossCaseMapper lossCases;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesTaskReportMapper reports;
    private final MesTaskArrangeMapper arranges;
    private final RemoteWarehouseLocationService locations;
    private final RemoteWarehouseService warehouses;

    public Map<String, Object> source(Long id) {
        String factory = access.require("read");
        require(id != null, "须提供精确库存ID");
        var s = stocks.selectOne(stockScope(factory).eq(MesWarehouseLiveStockEntity::getId, id).eq(MesWarehouseLiveStockEntity::getMatDetailType, "WIP"));
        require(s != null && s.getNum() != null, "当前工厂在制库存不存在或数量未知");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(s.getId()));
        result.put("quantity", s.getNum().toPlainString());
        result.put("matCode", s.getMatCode());
        result.put("matName", s.getMatName());
        result.put("matBatch", s.getMatBatch());
        result.put("matUnit", s.getMatUnit());
        result.put("barcode", s.getMatBarcode());
        result.put("physicalLocationCode", s.getPhysicalLocationCode());
        result.put("inspectionStatus", s.getInspectionStatus());
        result.put("stockStatus", s.getStatus());
        result.put("factoryCode", factory);
        return result;
    }

    public Object page(long current, String status, Long sourceReportId, String matCode) {
        String factory = access.require("read");
        require(current >= 1 && current <= 100000, "页码无效");
        var q = scope(factory).eq(sourceReportId != null, MesWipTransferEntity::getSourceReportId, sourceReportId)
                .eq(StrUtil.isNotBlank(matCode), MesWipTransferEntity::getMatCode, matCode);
        if ("LOSS_OPEN".equals(status))
            q.exists("SELECT 1 FROM mes_wip_loss_case c WHERE c.transfer_id=mes_wip_transfer.id AND c.status IN ('PENDING','APPROVED')");
        else if ("LOSS_EXECUTED".equals(status)) q.gt(MesWipTransferEntity::getLostQuantity, BigDecimal.ZERO);
        else if ("OPEN".equals(status) || "OVERDUE".equals(status)) q.ne(MesWipTransferEntity::getStatus, "CLOSED");
        else if (StrUtil.isNotBlank(status) && !"ALL".equals(status)) {
            require(Set.of("TRANSIT", "RETURNING", "HOLD", "CLOSED").contains(status), "不支持的转运状态");
            q.eq(MesWipTransferEntity::getStatus, status);
        }
        if ("OVERDUE".equals(status)) q.lt(MesWipTransferEntity::getDueTime, LocalDateTime.now());
        return transfers.selectPage(new Page<>(current, 30), q.orderByAsc(MesWipTransferEntity::getDueTime).orderByAsc(MesWipTransferEntity::getId));
    }

    public Map<String, Object> detail(Long id, long current) {
        String factory = access.require("read");
        require(id != null && current >= 1 && current <= 100000, "单据或页码无效");
        var doc = transfers.selectOne(scope(factory).eq(MesWipTransferEntity::getId, id));
        require(doc != null, "当前工厂转运单不存在");
        var history = operations.selectPage(new Page<>(current, 30), new LambdaQueryWrapper<MesWipTransferOperationEntity>()
                .eq(MesWipTransferOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWipTransferOperationEntity::getFactoryCode, factory).eq(MesWipTransferOperationEntity::getTransferId, id)
                .orderByDesc(MesWipTransferOperationEntity::getId));
        return Map.of("transfer", doc, "operations", history);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesWipTransferCommand c) {
        String factory = access.require("write");
        normalize(c);
        var actor = SecurityUtils.getUser();
        String fingerprint = fingerprint(JSONUtil.toJsonStr(List.of(c, String.valueOf(actor.getId()))));
        transfers.lockRequest("MES-WIP-TRANSFER:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + c.getRequestId());
        var old = operations.selectOne(new LambdaQueryWrapper<MesWipTransferOperationEntity>()
                .eq(MesWipTransferOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWipTransferOperationEntity::getFactoryCode, factory)
                .eq(MesWipTransferOperationEntity::getRequestId, c.getRequestId()));
        if (old != null) {
            require(fingerprint.equals(old.getFingerprint()), "请求号已用于其他内容或操作人，请核对原回执");
            var existing = transfers.selectOne(scope(factory).eq(MesWipTransferEntity::getId, old.getTransferId()));
            require(existing != null, "原回执缺少转运单，请核对数据");
            return Map.of("transfer", existing, "operation", old, "replayed", true);
        }
        MesWipTransferEntity doc;
        MesWipTransferOperationEntity op = new MesWipTransferOperationEntity();
        op.setTenantId(TenantContextHolder.getTenantId());
        op.setFactoryCode(factory);
        op.setRequestId(c.getRequestId());
        op.setFingerprint(fingerprint);
        op.setOperationType(c.getOperationType());
        op.setOperatorId(actor.getId());
        op.setOperatorCode(actor.getUsername());
        op.setReason(c.getReason());
        op.setQuantity(BigDecimal.ZERO);
        if ("DISPATCH".equals(c.getOperationType())) {
            require(c.getDueTime().isAfter(LocalDateTime.now()) && !c.getDueTime().isAfter(LocalDateTime.now().plusDays(30)), "承诺到达须在未来30天内");
            require(c.getTransferId() == null && c.getSourceStockId() != null, "发出须指定精确来源库存，不能指定旧转运单");
            var observed = stocks.selectOne(stockScope(factory).eq(MesWarehouseLiveStockEntity::getId, c.getSourceStockId()));
            require(observed != null && observed.getSourceReportId() != null, "转运须有本厂有效原报工库存，不按批次猜测");
            lockReport(observed.getSourceReportId(), factory);
            var source = stocks.selectOne(stockScope(factory).eq(MesWarehouseLiveStockEntity::getId, c.getSourceStockId()).last("FOR UPDATE"));
            require(source != null && Objects.equals(source.getSourceReportId(), observed.getSourceReportId()), "原库存已变化");
            require(source.getQualityCaseId() == null && "WIP".equals(source.getMatDetailType()) && "0".equals(source.getStatus())
                    && Set.of("RELEASED", "NOT_REQUIRED").contains(Objects.toString(source.getInspectionStatus(), ""))
                    && StrUtil.isBlank(source.getJudgeStatus()), "仅未锁定、已质量放行且不在退料审批的在制库存可转运");
            require(source.getMatValidEndDate() == null || !source.getMatValidEndDate().isBefore(java.time.LocalDate.now()), "已过期库存不能正常转运接收");
            var report = reports.selectById(source.getSourceReportId());
            require(Objects.equals(report.getMatCode(), source.getMatCode()) && Objects.equals(report.getArrangeCode(), source.getBillNo())
                    && Objects.equals(report.getProduceTaskCode(), source.getProduceTaskCode()), "库存与原报工的物料/派工/工序来源不一致");
            require(source.getNum() != null && source.getNum().signum() > 0 && c.getQuantity().compareTo(source.getNum()) <= 0, "发出数量超过实际库存");
            require(c.getExpectedSourceQuantity() != null && source.getNum().compareTo(c.getExpectedSourceQuantity()) == 0, "来源库存数量已变化，请重新核对后发出，不得重复提交旧观察量");
            var arrange = arranges.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                    .eq(MesTaskArrangeEntity::getCode, source.getBillNo()));
            require(arrange != null && Objects.equals(arrange.getProduceTaskCode(), source.getProduceTaskCode()), "缺少唯一同厂原派工，无法核验实物标识粒度");
            ReportModeEnum mode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
            if (mode.isIdentifierRequired()) {
                require(StrUtil.isNotBlank(source.getMatBarcode()) && c.getQuantity().compareTo(source.getNum()) == 0, "一物一码、流转卡和包装须整件/整卡/整包发出，拆分须先走对应标识业务");
                if (mode == ReportModeEnum.ONE_CODE)
                    require(source.getNum().compareTo(BigDecimal.ONE) == 0, "一物一码库存数量应为1，请治理来源");
                require(stocks.selectCount(stockScope(factory).eq(MesWarehouseLiveStockEntity::getMatBarcode, source.getMatBarcode())
                                .gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO).ne(MesWarehouseLiveStockEntity::getId, source.getId())) == 0,
                        "同一实物标识存在多个正量库存行，须先核对标识拆分谱系");
                require(transfers.selectCount(scope(factory).eq(MesWipTransferEntity::getMatBarcode, source.getMatBarcode())
                        .ne(MesWipTransferEntity::getStatus, "CLOSED")) == 0, "同一实物标识已有未结清转运");
            }
            checkBarcode(source.getMatBarcode(), c.getObservedBarcode());
            require(Boolean.TRUE.equals(c.getConditionConfirmed()), "请确认实物身份、数量和质量条件");
            var from = location(c.getFromLocationCode(), factory);
            var to = location(c.getToLocationCode(), factory);
            require(!from.getLocationCode().equals(to.getLocationCode()), "来源与目标现场库位不能相同");
            require(StrUtil.isNotBlank(from.getWarehouseCode()) && from.getWarehouseCode().equals(to.getWarehouseCode()), "此为同仓现场库位转运；跨仓/跨厂须走WMS调拨，不得改变账务归属");
            require(StrUtil.isBlank(source.getWarehouseCode()) || source.getWarehouseCode().equals(from.getWarehouseCode()), "库存已声明仓库与实际来源仓不一致，须走受控跨仓业务");
            require(StrUtil.isBlank(source.getPhysicalLocationCode()) || source.getPhysicalLocationCode().equals(from.getLocationCode()), "扫描来源与最近确认的现场库位不一致，请先核对实物");
            doc = new MesWipTransferEntity();
            doc.setTenantId(TenantContextHolder.getTenantId());
            doc.setFactoryCode(factory);
            doc.setSourceStockId(source.getId());
            doc.setSourceReportId(source.getSourceReportId());
            doc.setArrangeCode(source.getBillNo());
            doc.setProduceTaskCode(source.getProduceTaskCode());
            doc.setMatCode(source.getMatCode());
            doc.setMatName(source.getMatName());
            doc.setMatBatch(source.getMatBatch());
            doc.setMatBarcode(source.getMatBarcode());
            doc.setMatUnit(source.getMatUnit());
            doc.setReportMode(mode.getCode());
            doc.setIndivisible(mode.isIdentifierRequired());
            doc.setFromLocationCode(from.getLocationCode());
            doc.setFromLocationName(from.getLocationName());
            doc.setToLocationCode(to.getLocationCode());
            doc.setToLocationName(to.getLocationName());
            doc.setQuantity(c.getQuantity());
            doc.setRemainingQuantity(c.getQuantity());
            doc.setReceivedQuantity(BigDecimal.ZERO);
            doc.setReturnedQuantity(BigDecimal.ZERO);
            doc.setQuarantinedQuantity(BigDecimal.ZERO);
            doc.setLostQuantity(BigDecimal.ZERO);
            doc.setStatus("TRANSIT");
            doc.setRevision(1L);
            doc.setDispatchUserId(actor.getId());
            doc.setDispatchTime(LocalDateTime.now());
            doc.setDueTime(c.getDueTime());
            doc.setReason(c.getReason());
            doc.setSourceSnapshot(JSONUtil.toJsonStr(source));
            doc.setLocationSnapshot(JSONUtil.toJsonStr(List.of(from, to)));
            BigDecimal after = source.getNum().subtract(c.getQuantity());
            var u = new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>().eq(MesWarehouseLiveStockEntity::getId, source.getId())
                    .eq(MesWarehouseLiveStockEntity::getNum, source.getNum()).set(MesWarehouseLiveStockEntity::getNum, after)
                    .set(MesWarehouseLiveStockEntity::getPhysicalLocationCode, from.getLocationCode())
                    .set(MesWarehouseLiveStockEntity::getPhysicalLocationName, from.getLocationName())
                    .set(MesWarehouseLiveStockEntity::getPhysicalConfirmedTime, LocalDateTime.now());
            if (after.signum() == 0) u.set(MesWarehouseLiveStockEntity::getDelFlag, "1");
            require(stocks.update(null, u) == 1, "发出扣减失败，已回滚");
            if (after.signum() > 0)
                require(stocks.selectById(source.getId()).getNum().compareTo(after) == 0, "发出余量发生精度舍入，已回滚");
            require(transfers.insert(doc) == 1, "在途单据保存失败，已回滚");
            op.setBeforeRemaining(BigDecimal.ZERO);
            op.setBeforeStatus("NONE");
            op.setQuantity(c.getQuantity());
            op.setSourceBeforeNum(source.getNum());
            op.setSourceAfterNum(after);
            op.setLocationCode(from.getLocationCode());
            op.setEvidence(JSONUtil.toJsonStr(Map.of("source", source, "locations", List.of(from, to), "command", c)));
        } else {
            require(c.getTransferId() != null && c.getSourceStockId() == null, "交接命令须指定转运单，不能另选库存");
            doc = transfers.selectOne(scope(factory).eq(MesWipTransferEntity::getId, c.getTransferId()));
            require(doc != null, "当前工厂转运单不存在");
            lockReport(doc.getSourceReportId(), factory);
            doc = transfers.selectOne(scope(factory).eq(MesWipTransferEntity::getId, c.getTransferId()).last("FOR UPDATE"));
            require(doc != null && !"CLOSED".equals(doc.getStatus()), "转运已结清，不得重复交接");
            require(Objects.equals(doc.getRevision(), c.getExpectedRevision()), "转运已被其他命令推进，请刷新核对原回执后操作");
            require(lossCases.selectCount(new LambdaQueryWrapper<MesWipLossCaseEntity>()
                            .eq(MesWipLossCaseEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesWipLossCaseEntity::getFactoryCode, factory)
                            .eq(MesWipLossCaseEntity::getTransferId, doc.getId()).in(MesWipLossCaseEntity::getStatus, "PENDING", "APPROVED")) == 0,
                    "存在待审批/待执行丢失核销，须先完成、驳回或撤回，不能并行改变在途事实");
            op.setBeforeRemaining(doc.getRemainingQuantity());
            op.setBeforeStatus(doc.getStatus());
            op.setEvidence(JSONUtil.toJsonStr(Map.of("before", doc, "command", c)));
            switch (c.getOperationType()) {
                case "RECEIVE", "RECEIVE_RETURN", "RECEIVE_QUARANTINE" -> {
                    boolean quarantine = "RECEIVE_QUARANTINE".equals(c.getOperationType());
                    boolean returning = "RECEIVE_RETURN".equals(c.getOperationType()) || quarantine && "RETURNING".equals(doc.getResumeStatus());
                    require((quarantine ? "HOLD" : returning ? "RETURNING" : "TRANSIT").equals(doc.getStatus()), "当前状态不允许该接收；异常实收须先暂停在途");
                    if (quarantine)
                        require(Set.of("TRANSIT", "RETURNING").contains(Objects.toString(doc.getResumeStatus(), "")), "异常在途方向不可核验");
                    require(!Objects.equals(actor.getId(), returning ? doc.getReturnUserId() : doc.getDispatchUserId()), "接收人与本次发出/退回申请人必须不同");
                    require(c.getQuantity().compareTo(doc.getRemainingQuantity()) <= 0, "接收量超过剩余在途数量；溢收不得自动增库存");
                    require(!Boolean.TRUE.equals(doc.getIndivisible()) || c.getQuantity().compareTo(doc.getRemainingQuantity()) == 0, "整件/整卡/整包转运须完整接收，不能在交接时伪造子标识");
                    require(quarantine ? Boolean.TRUE.equals(c.getSegregationConfirmed()) : Boolean.TRUE.equals(c.getConditionConfirmed()), "须确认实际身份、数量及相应现场条件；异常件须已实收并隔离标识");
                    checkBarcode(doc.getMatBarcode(), c.getObservedBarcode());
                    String target = returning ? doc.getFromLocationCode() : doc.getToLocationCode();
                    require(target.equals(c.getScannedLocationCode()), "扫描库位与本次接收目标不一致");
                    var loc = location(target, factory);
                    require(Objects.equals(loc.getWarehouseCode(), JSONUtil.parseArray(doc.getLocationSnapshot()).getJSONObject(0).getStr("warehouseCode")), "现场库位所属仓库已变化，不能借转运跨仓恢复库存");
                    var frozen = JSONUtil.toBean(doc.getSourceSnapshot(), MesWarehouseLiveStockEntity.class);
                    require(quarantine || frozen.getMatValidEndDate() == null || !frozen.getMatValidEndDate().isBefore(java.time.LocalDate.now()), "在途已过期，须进入质量异常处置，不能直接恢复可用库存");
                    var result = new MesWarehouseLiveStockEntity();
                    BeanUtils.copyProperties(frozen, result, "id", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
                    result.setNum(c.getQuantity());
                    result.setTenantId(TenantContextHolder.getTenantId());
                    result.setFactoryCode(factory);
                    result.setPhysicalLocationCode(loc.getLocationCode());
                    result.setPhysicalLocationName(loc.getLocationName());
                    result.setPhysicalConfirmedTime(LocalDateTime.now());
                    if (quarantine) {
                        result.setQualityCaseId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
                        result.setStatus("1");
                        result.setInspectionStatus("PENDING");
                        result.setJudgeStatus(null);
                        result.setQualityDispositionReceiptId(null);
                        result.setQualityOriginCaseId(null);
                        result.setQualityReleaseReceiptId(null);
                    }
                    require(stocks.insert(result) == 1, "实收库存保存失败，已回滚");
                    var saved = stocks.selectById(result.getId());
                    require(saved != null && saved.getNum().compareTo(c.getQuantity()) == 0, "接收数量精度不一致，已回滚");
                    doc.setRemainingQuantity(doc.getRemainingQuantity().subtract(c.getQuantity()));
                    if (quarantine) doc.setQuarantinedQuantity(doc.getQuarantinedQuantity().add(c.getQuantity()));
                    else if (returning) doc.setReturnedQuantity(doc.getReturnedQuantity().add(c.getQuantity()));
                    else doc.setReceivedQuantity(doc.getReceivedQuantity().add(c.getQuantity()));
                    if (doc.getRemainingQuantity().signum() == 0) {
                        doc.setStatus("CLOSED");
                        doc.setClosedTime(LocalDateTime.now());
                    }
                    op.setResultStockId(result.getId());
                    op.setQuantity(c.getQuantity());
                    op.setLocationCode(target);
                    op.setEvidence(JSONUtil.toJsonStr(Map.of("before", op.getEvidence(), "location", loc, "result", saved)));
                    if (quarantine) {
                        op.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
                        op.setQualityCaseId(result.getQualityCaseId());
                        var incident = new MesInventoryQualityCaseEntity();
                        incident.setId(result.getQualityCaseId());
                        incident.setTenantId(TenantContextHolder.getTenantId());
                        incident.setFactoryCode(factory);
                        incident.setTransferId(doc.getId());
                        incident.setReceiptId(op.getId());
                        incident.setSourceReportId(doc.getSourceReportId());
                        incident.setSourceStockId(doc.getSourceStockId());
                        incident.setStockId(result.getId());
                        incident.setQuantity(c.getQuantity());
                        incident.setMatCode(doc.getMatCode());
                        incident.setMatName(doc.getMatName());
                        incident.setMatBatch(doc.getMatBatch());
                        incident.setMatBarcode(doc.getMatBarcode());
                        incident.setMatUnit(doc.getMatUnit());
                        incident.setLocationCode(target);
                        incident.setLocationName(loc.getLocationName());
                        incident.setExceptionType(c.getExceptionType());
                        incident.setReason(c.getReason());
                        incident.setSegregationEvidence(c.getSegregationEvidence());
                        incident.setReceiverId(actor.getId());
                        incident.setReceiverCode(actor.getUsername());
                        incident.setSourceSnapshot(doc.getSourceSnapshot());
                        require(qualityCases.insert(incident) == 1, "独立质量案件保存失败，已回滚");
                    }
                }
                case "REQUEST_RETURN" -> {
                    require("TRANSIT".equals(doc.getStatus()) || "HOLD".equals(doc.getStatus()) && "TRANSIT".equals(doc.getResumeStatus()), "当前不能再次申请退回");
                    doc.setStatus("RETURNING");
                    doc.setReturnUserId(actor.getId());
                }
                case "HOLD" -> {
                    require(Set.of("TRANSIT", "RETURNING").contains(doc.getStatus()), "当前不能重复登记在途阻断");
                    doc.setResumeStatus(doc.getStatus());
                    doc.setStatus("HOLD");
                    doc.setHoldUserId(actor.getId());
                }
                case "RESUME" -> {
                    require("HOLD".equals(doc.getStatus()) && Set.of("TRANSIT", "RETURNING").contains(Objects.toString(doc.getResumeStatus(), "")), "当前无可解除的在途阻断");
                    require(!Objects.equals(doc.getHoldUserId(), actor.getId()), "异常解除须由另一名授权人员复核");
                    require(Boolean.TRUE.equals(c.getConditionConfirmed()), "异常未消除或质量条件已改变，不得恢复正常交接");
                    doc.setStatus(doc.getResumeStatus());
                }
                default -> throw new CheckedException("不支持的在制转运操作");
            }
            doc.setRevision(doc.getRevision() + 1);
            require(transfers.updateById(doc) == 1, "转运状态保存失败，已回滚");
        }
        op.setTransferId(doc.getId());
        op.setSequenceNo(doc.getRevision());
        op.setAfterRemaining(doc.getRemainingQuantity());
        op.setAfterStatus(doc.getStatus());
        require(operations.insert(op) == 1, "转运永久回执保存失败，已回滚");
        return Map.of("transfer", doc, "operation", op, "replayed", false);
    }

    private void normalize(MesWipTransferCommand c) {
        require(c != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}"), "请求号须为16至64位安全字符");
        require(Set.of("DISPATCH", "RECEIVE", "REQUEST_RETURN", "RECEIVE_RETURN", "RECEIVE_QUARANTINE", "HOLD", "RESUME").contains(Objects.toString(c.getOperationType(), "")), "不支持的转运命令");
        require(StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 500, "须填写500字以内的实际原因/交接说明");
        c.setReason(c.getReason().trim());
        if ("RECEIVE_QUARANTINE".equals(c.getOperationType())) {
            require(Set.of("DAMAGED", "EXPIRED", "QUALITY_SUSPECT").contains(Objects.toString(c.getExceptionType(), "")), "请选择破损、过期或质量疑似；丢失物料不能作为实收");
            require(StrUtil.isNotBlank(c.getSegregationEvidence()) && c.getSegregationEvidence().length() <= 1000, "须填写1000字以内现场隔离位置、标签及佐证编号");
            c.setSegregationEvidence(c.getSegregationEvidence().trim());
            require(Boolean.TRUE.equals(c.getSegregationConfirmed()), "须确认物料已实际收到并完成现场隔离标识");
        } else
            require(c.getExceptionType() == null && c.getSegregationEvidence() == null && c.getSegregationConfirmed() == null, "非隔离接收不能附带质量案件字段");
        if (Set.of("DISPATCH", "RECEIVE", "RECEIVE_RETURN", "RECEIVE_QUARANTINE").contains(c.getOperationType())) {
            require(c.getQuantity() != null && c.getQuantity().signum() > 0 && c.getQuantity().precision() <= 24 && Math.max(c.getQuantity().scale(), 0) <= 8, "数量须为正数，最多24位且8位小数");
            c.setQuantity(c.getQuantity().stripTrailingZeros());
        } else require(c.getQuantity() == null, "状态命令不能修改数量");
        for (String value : Arrays.asList(c.getFromLocationCode(), c.getToLocationCode(), c.getScannedLocationCode(), c.getObservedBarcode()))
            require(value == null || value.length() <= 200, "库位或条码过长");
        if ("DISPATCH".equals(c.getOperationType())) {
            require(c.getDueTime() != null, "发出须填写承诺到达时间");
            require(c.getExpectedSourceQuantity() != null && c.getExpectedSourceQuantity().signum() > 0, "发出须先读取并核对当前库存数量");
            c.setExpectedSourceQuantity(c.getExpectedSourceQuantity().stripTrailingZeros());
        } else require(c.getExpectedRevision() != null && c.getExpectedRevision() > 0, "交接须提供观察到的单据版本");
    }

    private MdmWarehouseLocationEntity location(String code, String factory) {
        require(StrUtil.isNotBlank(code), "须扫描或填写实际现场库位编码");
        var response = locations.getByCode(code);
        var value = response == null ? null : response.getData();
        require(response != null && response.isOk() && value != null && code.equals(value.getLocationCode())
                && factory.equals(value.getFactoryCode()) && Objects.equals(value.getTenantId(), TenantContextHolder.getTenantId())
                && Boolean.TRUE.equals(value.getStatus()) && Integer.valueOf(3).equals(value.getLocationLevel()), "MDM无法证明该库位为本厂已启用的实际库位");
        var warehouse = warehouses.getByCode(value.getWarehouseCode());
        require(warehouse != null && warehouse.isOk() && warehouse.getData() != null
                && Objects.equals(value.getWarehouseCode(), warehouse.getData().getWarehouseCode())
                && factory.equals(warehouse.getData().getFactoryCode())
                && Objects.equals(TenantContextHolder.getTenantId(), warehouse.getData().getTenantId())
                && Boolean.TRUE.equals(warehouse.getData().getStatus()), "现场库位所属仓库未启用或归属不可核验");
        return value;
    }

    private void lockReport(Long id, String factory) {
        var r = reports.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getId, id)
                .eq(MesTaskReportEntity::getFactoryCode, factory).eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId()).last("FOR UPDATE"));
        require(r != null && "EFFECTIVE".equals(r.getStatus()), "转运原报工不存在或已失效，禁止猜测恢复库存");
    }

    private void checkBarcode(String expected, String observed) {
        require(Objects.equals(StrUtil.blankToDefault(expected, ""), StrUtil.blankToDefault(observed, "")), "实物条码与原库存不一致");
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LambdaQueryWrapper<MesWipTransferEntity> scope(String factory) {
        return new LambdaQueryWrapper<MesWipTransferEntity>().eq(MesWipTransferEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesWipTransferEntity::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<MesWarehouseLiveStockEntity> stockScope(String factory) {
        return new LambdaQueryWrapper<MesWarehouseLiveStockEntity>().eq(MesWarehouseLiveStockEntity::getTenantId, TenantContextHolder.getTenantId()).eq(MesWarehouseLiveStockEntity::getFactoryCode, factory);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
