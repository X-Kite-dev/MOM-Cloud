package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mes.dto.MesProductionNgCaptureDTO;
import com.kitex.mom.mes.dto.MesTaskReportUnqualifiedVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesProductionMaterialOperationService;
import com.kitex.mom.mes.service.MesProductionNgCaptureService;
import com.kitex.mom.mes.service.MesTaskReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 明确的新生产不良实物采集。首次原NG生产、原耗用和实际隔离在同一MES事务提交，
 * 失败全部回滚；QMS办理使用提交后的独立案件，不以Feign写入伪装跨服务原子事务。
 */
@Service
@RequiredArgsConstructor
public class MesProductionNgCaptureServiceImpl
        extends ServiceImpl<MesProductionNgCaptureMapper, MesProductionNgCaptureEntity>
        implements MesProductionNgCaptureService {
    public static final String COMMAND_VERSION = "MES_PRODUCTION_NG_CAPTURE_V1";
    public static final String EVIDENCE_VERSION = "MES_PRODUCTION_NG_INVENTORY_V1";
    public static final String CANCELLATION_VERSION = "MES_PRODUCTION_NG_CANCEL_V1";
    private static final Set<String> KEYS = Set.of("version", "requestId", "expectedTenantId",
            "expectedFactoryCode", "expectedActorUserId", "arrangeCode", "quantity", "identifier",
            "badCode", "materialAccountingMode", "locationCode", "reason", "segregationEvidence",
            "worksiteCode", "manualOverrideReason");
    private final MesPhysicalTransformCodec codec;
    private final MesProductionNgSourceCodec sourceCodec;
    private final MesActualFactoryAccessService access;
    private final MesPhysicalTransformSourceScopeLock locks;
    private final MesTerminalExecutionGuard terminalGuard;
    private final MesProductionNgAdmission admission;
    private final MesProductionNgCancellationMapper cancellations;
    private final MesTaskArrangeMapper arrangements;
    private final MesTaskReportUnqualifiedMapper reports;
    private final MesTaskReportService production;
    private final MesProductionMaterialOperationService materials;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesWarehouseInoutMapper flows;
    private final MesInventoryQualityCaseMapper cases;
    private final MesCaseDispositionReceiptMapper caseReceipts;
    private final RemoteWarehouseLocationService locations;
    private final RemoteWarehouseService warehouses;
    private final RemoteMaterialService materialCatalog;
    private final MesExecutionTemplateService templates;
    private final PlatformTransactionManager transactions;

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    @Transactional(propagation = Propagation.NEVER)
    public MesProductionNgCaptureDTO.Receipt capture(String raw) {
        return captureInChannel(raw, false);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    @Transactional(propagation = Propagation.NEVER)
    public MesProductionNgCaptureDTO.Receipt captureMobile(String raw) {
        return captureInChannel(raw, true);
    }

    private MesProductionNgCaptureDTO.Receipt captureInChannel(String raw, boolean mobile) {
        String factory = access.require("write");
        JsonNode command = originalCommand(raw, factory);
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> execute(raw, command, factory, mobile));
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    @Transactional(readOnly = true)
    public MesProductionNgCaptureDTO.Preparation prepare(String arrangeCode, boolean mobile) {
        String factory = access.require("read");
        codec.code(arrangeCode, "实际派工", 100);
        var matches = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, arrangeCode).last("LIMIT 2"));
        require(matches.size() == 1, "当前实际租户工厂内派工不存在或不唯一");
        var arrange = matches.get(0);
        var response = materialCatalog.productionMeasure(arrange.getMatCode());
        require(response != null && response.getCode() == 0 && response.getData() != null, "未取得物料实际计量，请检查MDM服务");
        var material = response.getData();
        require(Objects.equals(material.getTenantId(), arrange.getTenantId().toString())
                && Objects.equals(material.getFactoryCode(), factory)
                && Objects.equals(material.getMaterialCode(), arrange.getMatCode())
                && Boolean.TRUE.equals(material.getEnabled()), "物料计量未唯一归属当前租户工厂或未启用");
        codec.id(material.getMaterialId());
        codec.code(material.getBasicUnit(), "真实基本单位", 100);
        var contract = templates.contract(arrange);
        boolean allowed = Boolean.TRUE.equals(mobile ? contract.getMobileAllowed() : contract.getStationAllowed())
                && contract.getAllowedTriggers() != null && contract.getAllowedTriggers().contains("MANUAL");
        boolean assigned = arrange.getWorkerCode() != null && java.util.Arrays.stream(arrange.getWorkerCode().split(","))
                .map(String::trim).anyMatch(SecurityUtils.getUser().getUsername()::equals);
        String restriction = !allowed ? "原派工未开放该通道的人工采集"
                : !assigned ? "当前用户不是原派工指定执行人"
                : !"NONE".equals(contract.getIdentifierNodePolicy().getAction()) ? "该换码节点尚不支持新NG前驱实物采集"
                : arrange.getActualWorksiteCode() == null || arrange.getActualWorksiteCode().isBlank() ? "请先接收派工并确认实际工站" : "";
        return new MesProductionNgCaptureDTO.Preparation(arrange.getTenantId().toString(), factory,
                SecurityUtils.getUser().getId().toString(), arrange.getCode(), arrange.getTaskCode(),
                arrange.getProduceTaskCode(), arrange.getMatCode(), arrange.getMatName(), material.getBasicUnit(),
                ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType()).name(),
                arrange.getActualWorksiteCode(), mobile ? "MOBILE" : "STATION", allowed, assigned, restriction);
    }

    /**
     * 保留完整HTTP原字符串；所有身份/数量是明确文本，不接受客户端生成来源包。
     */
    private JsonNode originalCommand(String raw, String factory) {
        var value = codec.objectDocument(raw, 64 * 1024);
        require(value.size() == KEYS.size(), "生产不良采集原文缺字段或有额外字段");
        KEYS.forEach(key -> require(value.has(key) && value.get(key).isTextual(), key + "必须是文本"));
        require(COMMAND_VERSION.equals(text(value, "version")), "生产不良采集协议未知");
        require(text(value, "requestId").matches("[A-Za-z0-9_-]{8,64}"), "原请求号须为8至64位");
        require(Objects.equals(codec.id(text(value, "expectedTenantId")), TenantContextHolder.getTenantId())
                        && Objects.equals(text(value, "expectedFactoryCode"), factory)
                        && Objects.equals(codec.id(text(value, "expectedActorUserId")), SecurityUtils.getUser().getId()),
                "只能在当前实际实名租户工厂采集");
        return value;
    }

    /**
     * 仅首次执行要求业务字段有效；作废必须能封存库位/标识等填错的完整原文。
     */
    private JsonNode command(String raw, String factory) {
        var value = originalCommand(raw, factory);
        codec.code(text(value, "arrangeCode"), "实际派工", 100);
        codec.code(text(value, "badCode"), "实际不良项目", 100);
        codec.code(text(value, "locationCode"), "实际隔离库位", 100);
        codec.code(text(value, "worksiteCode"), "实际接收工站", 100);
        if (!text(value, "manualOverrideReason").isEmpty()) {
            codec.reason(text(value, "manualOverrideReason"));
            require(text(value, "manualOverrideReason").length() <= 500, "人工接管原因不能超过500字符");
        }
        codec.quantity(text(value, "quantity"), false, "真实不良实物数量");
        codec.reason(text(value, "reason"));
        require(text(value, "reason").length() <= 500, "案件原因不能超过500字符");
        codec.reason(text(value, "segregationEvidence"));
        require(text(value, "identifier").isEmpty()
                || text(value, "identifier").equals(text(value, "identifier").trim())
                && text(value, "identifier").length() <= 200, "标识过长或有首尾空白");
        require(Set.of("NONE", "FEED").contains(text(value, "materialAccountingMode")), "原耗料选择只能NONE或FEED");
        return value;
    }

    private MesProductionNgCaptureDTO.Receipt execute(String raw, JsonNode command, String factory, boolean mobile) {
        ready();
        String requestId = text(command, "requestId");
        require(Boolean.TRUE.equals(baseMapper.tryRequest(codec.snapshot(List.of(COMMAND_VERSION,
                TenantContextHolder.getTenantId().toString(), requestId)))), "原请求仍在执行，请保留原号查询");
        var replay = getOne(scoped(factory).eq(MesProductionNgCaptureEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getActorId(), SecurityUtils.getUser().getId())
                    && Objects.equals(replay.getCommandSnapshot(), raw), "原号不能改变采集原文或人员");
            return verified(replay);
        }
        var cancelled = cancellation(requestId, factory);
        if (cancelled != null) {
            require(Objects.equals(cancelled.getCommandSnapshot(), raw), "已永久作废的原号不能改原文重新执行");
            return cancellationReceipt(cancelled);
        }
        command(raw, factory);
        require(Boolean.TRUE.equals(baseMapper.channelReady()), "生产NG通道保护未就绪，请先执行V2第269节");
        // 首次新实物必须在全实际来源写域内证明不存在；单个派工锁不能挡住另一派工/库存/外码写者。
        locks.acquire(TenantContextHolder.getTenantId(), factory);
        var initial = arrangements.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, text(command, "arrangeCode")));
        require(initial != null, "当前实际工厂派工不存在");
        locks.task(initial.getTenantId(), factory, initial.getTaskCode());
        var arrange = arrangements.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, initial.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getId, initial.getId()).last("FOR UPDATE NOWAIT"));
        require(arrange != null && Objects.equals(arrange.getTaskCode(), initial.getTaskCode()), "实际派工归属变化");
        terminalGuard.assertUnoccupied(arrange);
        admission.manual(arrange, text(command, "worksiteCode"), text(command, "manualOverrideReason"), mobile);
        require(source(requestId, factory) == null, "原号已有不良声明，不能事后补库存；须核原事实而非改请求号");
        var mode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
        require(!mode.isIdentifierRequired() || !text(command, "identifier").isEmpty(), "该原对象模式须扫描实际标识");
        var location = location(text(command, "locationCode"), factory);
        var vo = new MesTaskReportUnqualifiedVO();
        vo.setRequestId(requestId);
        vo.setArrangeCode(arrange.getCode());
        vo.setUnPassNum(new BigDecimal(text(command, "quantity")));
        vo.setTransferCode(text(command, "identifier").isEmpty() ? null : text(command, "identifier"));
        vo.setUnqualifiedType(text(command, "badCode"));
        vo.setMaterialAccountingMode(text(command, "materialAccountingMode"));
        // 原服务加入本事务：报工记录、NG计数、可选耗料、APS来源只生成一次。
        production.unqualified(vo);
        var source = source(requestId, factory);
        require(source != null && sourceCodec.verify(source)
                && Objects.equals(source.getCapturedActorId(), SecurityUtils.getUser().getId()), "首次不良生产原事实未保存");
        // 解析事实来自本事务真实NG，不能用页面预览或今日另一次解析替代。
        // 已有静态映射只是字典，不等于已有实物；已有生产/库存/动态绑定仍拒绝，整笔NG与计次回滚。
        require(Boolean.TRUE.equals(baseMapper.newIdentityAvailable(arrange.getTenantId(), factory, source.getId())),
                "原始码或规范码已有生产、库存或动态外码事实，不能作为新实物重复采集；请办理原实物质量流程");
        var material = materials.original(source);
        require((material == null ? "NONE" : "FEED").equals(text(command, "materialAccountingMode")), "原NG耗料事实与选择不一致");
        var header = new MesProductionNgCaptureEntity();
        header.setId(IdWorker.getId());
        header.setTenantId(source.getTenantId());
        header.setFactoryCode(factory);
        header.setRequestId(requestId);
        header.setActorId(source.getCapturedActorId());
        header.setSourceUnqualifiedReportId(source.getId());
        header.setStockId(IdWorker.getId());
        header.setInflowId(IdWorker.getId());
        header.setCaseId(IdWorker.getId());
        header.setReportMode(source.getReportMode());
        header.setExecutionChannel(mobile ? "MOBILE" : "STATION");
        header.setIndivisible(mode != ReportModeEnum.QUANTITY);
        header.setQuantity(source.getUnqualifiedNum());
        header.setUnit(source.getReportUnit());
        header.setMaterialAccountingMode(text(command, "materialAccountingMode"));
        header.setCommandVersion(COMMAND_VERSION);
        header.setCommandSnapshot(raw);
        header.setCommandFingerprint(codec.fingerprint(COMMAND_VERSION, raw));
        header.setEvidenceVersion(EVIDENCE_VERSION);
        header.setStatus("ISOLATED");
        header.setCreateBy(SecurityUtils.getUser().getUsername());
        header.setDelFlag("0");
        var stock = stock(header, source, arrange, location);
        var evidence = new TreeMap<String, String>();
        evidence.put("version", EVIDENCE_VERSION);
        evidence.put("captureId", header.getId().toString());
        evidence.put("sourceUnqualifiedReportId", source.getId().toString());
        evidence.put("sourceSnapshot", source.getSourceSnapshot());
        evidence.put("sourceFingerprint", source.getSourceFingerprint());
        evidence.put("stockId", header.getStockId().toString());
        evidence.put("inflowId", header.getInflowId().toString());
        evidence.put("caseId", header.getCaseId().toString());
        evidence.put("tenantId", source.getTenantId().toString());
        evidence.put("factoryCode", factory);
        evidence.put("actorId", header.getActorId().toString());
        evidence.put("reportMode", header.getReportMode());
        evidence.put("indivisible", header.getIndivisible().toString());
        evidence.put("quantity", number(header.getQuantity()));
        evidence.put("unit", header.getUnit());
        evidence.put("materialAccountingMode", header.getMaterialAccountingMode());
        evidence.put("materialOperationId", material == null ? null : material.getId().toString());
        evidence.put("materialFingerprint", material == null ? null : material.getCommandFingerprint());
        evidence.put("locationSnapshot", codec.snapshot(locationFacts(location)));
        evidence.put("stockSnapshot", codec.snapshot(stockFacts(stock)));
        header.setEvidenceSnapshot(codec.snapshot(evidence));
        codec.objectDocument(header.getEvidenceSnapshot(), 8 * 1024 * 1024);
        header.setEvidenceFingerprint(codec.fingerprint(EVIDENCE_VERSION, header.getEvidenceSnapshot()));
        require(save(header), "生产不良采集永久主账保存失败");
        require(stocks.insert(stock) == 1, "隔离库存必要写失败，整笔回滚");
        var inflow = flow(stock, header.getInflowId(), null, source.getRequestId(), BigDecimal.ZERO,
                source.getUnqualifiedNum(), source.getUnqualifiedNum(), "in", "production_ng_quarantine");
        require(flows.insert(inflow) == 1, "生产不良初始入库事实保存失败，整笔回滚");
        var qualityCase = new MesInventoryQualityCaseEntity();
        qualityCase.setId(header.getCaseId());
        qualityCase.setTenantId(source.getTenantId());
        qualityCase.setFactoryCode(factory);
        qualityCase.setOriginType("PRODUCTION_NG");
        qualityCase.setSourceUnqualifiedReportId(source.getId());
        qualityCase.setProductionNgCaptureId(header.getId());
        qualityCase.setInitialInflowId(header.getInflowId());
        qualityCase.setStockId(stock.getId());
        qualityCase.setReceiverId(header.getActorId());
        qualityCase.setReceiverCode(source.getWorkerCode());
        qualityCase.setQuantity(source.getUnqualifiedNum());
        qualityCase.setMatCode(stock.getMatCode());
        qualityCase.setMatName(stock.getMatName());
        qualityCase.setMatBatch(stock.getMatBatch());
        qualityCase.setMatBarcode(stock.getMatBarcode());
        qualityCase.setMatUnit(stock.getMatUnit());
        qualityCase.setLocationCode(location.getLocationCode());
        qualityCase.setLocationName(location.getLocationName());
        qualityCase.setExceptionType("PRODUCTION_NG");
        qualityCase.setReason(text(command, "reason"));
        qualityCase.setSegregationEvidence(text(command, "segregationEvidence"));
        qualityCase.setSourceSnapshot(header.getEvidenceSnapshot());
        qualityCase.setCreateBy(SecurityUtils.getUser().getUsername());
        qualityCase.setDelFlag("0");
        require(cases.insert(qualityCase) == 1, "生产不良隔离案件保存失败，整笔回滚");
        return verified(getById(header.getId()));
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public MesProductionNgCaptureDTO.Receipt receipt(String requestId) {
        String factory = access.require("read");
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{8,64}"), "原请求号非法");
        ready();
        var row = getOne(scoped(factory).eq(MesProductionNgCaptureEntity::getRequestId, requestId));
        var cancelled = cancellation(requestId, factory);
        require(row == null || cancelled == null, "同原号出现隔离与作废冲突，禁止猜终态");
        if (cancelled != null) return cancellationReceipt(cancelled);
        if (row == null) return new MesProductionNgCaptureDTO.Receipt(requestId, "ABSENT",
                TenantContextHolder.getTenantId().toString(), factory, SecurityUtils.getUser().getId().toString(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        require(Objects.equals(row.getActorId(), SecurityUtils.getUser().getId()), "只有首次操作人可以恢复原采集请求");
        return verified(row);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    @Transactional(propagation = Propagation.NEVER)
    public MesProductionNgCaptureDTO.Receipt cancel(String cancellationRaw) {
        String factory = access.require("write");
        var wrapper = codec.objectDocument(cancellationRaw, 128 * 1024);
        require(wrapper.size() == 2 && wrapper.path("originalRaw").isTextual() && wrapper.path("reason").isTextual(),
                "作废须提交完整原文originalRaw及文本reason，不能只报请求号");
        String raw = text(wrapper, "originalRaw");
        var body = originalCommand(raw, factory);
        String reason = text(wrapper, "reason");
        codec.reason(reason);
        require(reason.length() <= 500, "作废原因不能超过500字符");
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            ready();
            String requestId = text(body, "requestId");
            require(Boolean.TRUE.equals(baseMapper.tryRequest(codec.snapshot(List.of(COMMAND_VERSION,
                    TenantContextHolder.getTenantId().toString(), requestId)))), "原请求仍在执行，保留原号查询，不能作废未知事务");
            var captured = getOne(scoped(factory).eq(MesProductionNgCaptureEntity::getRequestId, requestId));
            if (captured != null) {
                require(Objects.equals(captured.getActorId(), SecurityUtils.getUser().getId())
                        && Objects.equals(captured.getCommandSnapshot(), raw), "原号原文或操作人不一致");
                return verified(captured);
            }
            var existing = cancellation(requestId, factory);
            if (existing != null) {
                require(Objects.equals(existing.getCommandSnapshot(), raw), "原号已作废，不能改变原文");
                return cancellationReceipt(existing);
            }
            require(source(requestId, factory) == null, "原号已有生产不良事实，不允许作废为未执行");
            var row = new MesProductionNgCancellationEntity();
            row.setId(IdWorker.getId());
            row.setTenantId(TenantContextHolder.getTenantId());
            row.setFactoryCode(factory);
            row.setRequestId(requestId);
            row.setActorId(SecurityUtils.getUser().getId());
            row.setReason(reason);
            row.setCommandVersion(COMMAND_VERSION);
            row.setCommandSnapshot(raw);
            row.setCommandFingerprint(codec.fingerprint(COMMAND_VERSION, raw));
            row.setEvidenceVersion(CANCELLATION_VERSION);
            row.setStatus("CANCELLED");
            row.setEvidenceSnapshot(codec.snapshot(cancellationFacts(row)));
            row.setEvidenceFingerprint(codec.fingerprint(CANCELLATION_VERSION, row.getEvidenceSnapshot()));
            row.setCreateBy(SecurityUtils.getUser().getUsername());
            row.setDelFlag("0");
            require(cancellations.insert(row) == 1, "永久作废必要写失败，原号仍待核");
            return cancellationReceipt(cancellations.selectById(row.getId()));
        });
    }

    private MesProductionNgCancellationEntity cancellation(String requestId, String factory) {
        return cancellations.selectOne(new LambdaQueryWrapper<MesProductionNgCancellationEntity>()
                .eq(MesProductionNgCancellationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProductionNgCancellationEntity::getFactoryCode, factory)
                .eq(MesProductionNgCancellationEntity::getRequestId, requestId));
    }

    private Map<String, String> cancellationFacts(MesProductionNgCancellationEntity row) {
        var facts = new TreeMap<String, String>();
        facts.put("version", CANCELLATION_VERSION);
        facts.put("cancellationId", row.getId().toString());
        facts.put("requestId", row.getRequestId());
        facts.put("tenantId", row.getTenantId().toString());
        facts.put("factoryCode", row.getFactoryCode());
        facts.put("actorId", row.getActorId().toString());
        facts.put("commandFingerprint", row.getCommandFingerprint());
        facts.put("reason", row.getReason());
        facts.put("state", "CANCELLED");
        return facts;
    }

    /**
     * 作废是永久负向事实，不根据当前ABSENT或本机缓存猜未执行。
     */
    private MesProductionNgCaptureDTO.Receipt cancellationReceipt(MesProductionNgCancellationEntity row) {
        require(row != null && Objects.equals(row.getActorId(), SecurityUtils.getUser().getId())
                && Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
                && COMMAND_VERSION.equals(row.getCommandVersion()) && CANCELLATION_VERSION.equals(row.getEvidenceVersion())
                && "CANCELLED".equals(row.getStatus())
                && Objects.equals(row.getCommandFingerprint(), codec.fingerprint(COMMAND_VERSION, row.getCommandSnapshot()))
                && Objects.equals(row.getEvidenceFingerprint(), codec.fingerprint(CANCELLATION_VERSION, row.getEvidenceSnapshot()))
                && Objects.equals(codec.snapshot(cancellationFacts(row)), row.getEvidenceSnapshot()), "永久作废原证据或本人身份不匹配");
        originalCommand(row.getCommandSnapshot(), row.getFactoryCode());
        return new MesProductionNgCaptureDTO.Receipt(row.getRequestId(), "CANCELLED", row.getTenantId().toString(),
                row.getFactoryCode(), row.getActorId().toString(), null, null, null, null, null,
                row.getCommandVersion(), row.getCommandSnapshot(), row.getCommandFingerprint(), row.getEvidenceVersion(),
                row.getEvidenceSnapshot(), row.getEvidenceFingerprint(), null, null, null, null);
    }

    @Override
    public boolean validateOrigin(MesInventoryQualityCaseEntity qualityCase, MesWarehouseLiveStockEntity root) {
        require(qualityCase != null && "PRODUCTION_NG".equals(qualityCase.getOriginType())
                        && Objects.equals(qualityCase.getTenantId(), TenantContextHolder.getTenantId())
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip())
                        && Objects.equals(qualityCase.getFactoryCode(),
                        com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()),
                "须为实际工厂真实生产NG案件");
        ready();
        var header = getById(qualityCase.getProductionNgCaptureId());
        require(header != null && Objects.equals(header.getCaseId(), qualityCase.getId()), "实际原NG采集账缺失");
        verified(header);
        require(root != null && Objects.equals(root.getId(), header.getStockId()), "案件根库存不一致");
        return Boolean.TRUE.equals(header.getIndivisible());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void dispositionFlows(MesInventoryQualityCaseEntity qualityCase, MesWarehouseLiveStockEntity root,
                                 MesWarehouseLiveStockEntity released, MesCaseDispositionReceiptEntity receipt) {
        require("PRODUCTION_NG".equals(qualityCase.getOriginType())
                && receipt.getBeforeQuantity().compareTo(receipt.getAfterQuantity()) > 0, "仅真实NG实物扣减登记处置流水");
        var out = flow(root, IdWorker.getId(), receipt.getId(), receipt.getRequestId(), receipt.getBeforeQuantity(),
                receipt.getAfterQuantity(), receipt.getQuantity(), "out", "production_ng_disposition");
        require(flows.insert(out) == 1, "NG处置OUT必要事实失败，整笔回滚");
        if (released != null) {
            var in = flow(released, IdWorker.getId(), receipt.getId(), receipt.getRequestId(), BigDecimal.ZERO,
                    released.getNum(), released.getNum(), "in", "production_ng_release");
            require(flows.insert(in) == 1, "NG放行IN必要事实失败，整笔回滚");
        }
    }

    /**
     * 原库位依据只采集一次；不以当前MDM变化改写永久历史。
     */
    private MdmWarehouseLocationEntity location(String code, String factory) {
        var response = locations.getByCode(code);
        var value = response == null ? null : response.getData();
        require(response != null && response.isOk() && value != null && Objects.equals(value.getLocationCode(), code)
                        && Objects.equals(value.getFactoryCode(), factory) && Objects.equals(value.getTenantId(), TenantContextHolder.getTenantId())
                        && Boolean.TRUE.equals(value.getStatus()) && Integer.valueOf(3).equals(value.getLocationLevel()),
                "实际启用三级隔离库位和组织不可核验");
        var warehouse = warehouses.getByCode(value.getWarehouseCode());
        require(warehouse != null && warehouse.isOk() && warehouse.getData() != null
                && Objects.equals(warehouse.getData().getWarehouseCode(), value.getWarehouseCode())
                && Objects.equals(warehouse.getData().getTenantId(), value.getTenantId())
                && Objects.equals(warehouse.getData().getFactoryCode(), factory)
                && Boolean.TRUE.equals(warehouse.getData().getStatus()), "隔离库位所属仓库不可核验");
        return value;
    }

    private MesWarehouseLiveStockEntity stock(MesProductionNgCaptureEntity header, MesTaskReportUnqualifiedEntity source,
                                              MesTaskArrangeEntity arrange, MdmWarehouseLocationEntity location) {
        var row = new MesWarehouseLiveStockEntity();
        row.setId(header.getStockId());
        row.setTenantId(source.getTenantId());
        row.setFactoryCode(source.getFactoryCode());
        row.setSourceUnqualifiedReportId(source.getId());
        row.setProductionNgCaptureId(header.getId());
        row.setQualityCaseId(header.getCaseId());
        row.setBillNo(source.getArrangeCode());
        row.setProduceTaskCode(source.getProduceTaskCode());
        row.setRouteCode(arrange.getRouteCode());
        row.setRouteName(arrange.getRouteName());
        row.setMatCode(source.getMatCode());
        row.setMatName(source.getMatName());
        row.setMatSpec(source.getMatSpec());
        row.setMatBatch(arrange.getBatchNo());
        row.setMatBarcode(source.getTransferCode());
        row.setMatUnit(source.getReportUnit());
        row.setNum(source.getUnqualifiedNum());
        row.setMatDetailType("WIP");
        row.setWarehouseCode(location.getWarehouseCode());
        row.setWarehouseName(location.getWarehouseName());
        row.setSiteCode(location.getLocationCode());
        row.setSiteName(location.getLocationName());
        row.setPhysicalLocationCode(location.getLocationCode());
        row.setPhysicalLocationName(location.getLocationName());
        row.setPhysicalConfirmedTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        row.setInTime(row.getPhysicalConfirmedTime());
        row.setStatus("1");
        row.setInspectionStatus("PENDING");
        row.setCreateBy(SecurityUtils.getUser().getUsername());
        row.setDelFlag("0");
        return row;
    }

    private MesWarehouseInoutEntity flow(MesWarehouseLiveStockEntity stock, Long id, Long dispositionId,
                                         String requestId, BigDecimal before, BigDecimal after, BigDecimal quantity,
                                         String direction, String business) {
        var row = new MesWarehouseInoutEntity();
        BeanUtils.copyProperties(stock, row, "id", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
        row.setId(id);
        row.setSourceStockId(stock.getId());
        row.setQualityDispositionReceiptId(dispositionId);
        row.setBillId(dispositionId == null ? stock.getProductionNgCaptureId() : dispositionId);
        row.setResourceNo(requestId);
        row.setStockBeforeNum(before);
        row.setStockAfterNum(after);
        row.setOperateNum(quantity);
        row.setOperateType(direction);
        row.setBizType(business);
        row.setStockProofVersion("STOCK_LINE_V1");
        row.setAllocationPolicy(EVIDENCE_VERSION);
        row.setCreateBy(SecurityUtils.getUser().getUsername());
        row.setDelFlag("0");
        return row;
    }

    /**
     * 明确固定原stock字段；数量的当前余额不改变首次原采集事实。
     */
    private Map<String, String> stockFacts(MesWarehouseLiveStockEntity stock) {
        var facts = new TreeMap<String, String>();
        facts.put("id", stock.getId().toString());
        facts.put("tenantId", stock.getTenantId().toString());
        facts.put("factoryCode", stock.getFactoryCode());
        facts.put("sourceUnqualifiedReportId", stock.getSourceUnqualifiedReportId().toString());
        facts.put("productionNgCaptureId", stock.getProductionNgCaptureId().toString());
        facts.put("qualityCaseId", stock.getQualityCaseId().toString());
        facts.put("billNo", stock.getBillNo());
        facts.put("produceTaskCode", stock.getProduceTaskCode());
        facts.put("routeCode", stock.getRouteCode());
        facts.put("routeName", stock.getRouteName());
        facts.put("matCode", stock.getMatCode());
        facts.put("matName", stock.getMatName());
        facts.put("matSpec", stock.getMatSpec());
        facts.put("matBatch", stock.getMatBatch());
        facts.put("matBarcode", stock.getMatBarcode());
        facts.put("matUnit", stock.getMatUnit());
        facts.put("matDetailType", stock.getMatDetailType());
        facts.put("warehouseCode", stock.getWarehouseCode());
        facts.put("warehouseName", stock.getWarehouseName());
        facts.put("siteCode", stock.getSiteCode());
        facts.put("siteName", stock.getSiteName());
        facts.put("physicalLocationCode", stock.getPhysicalLocationCode());
        facts.put("physicalLocationName", stock.getPhysicalLocationName());
        facts.put("physicalConfirmedTime", stock.getPhysicalConfirmedTime().toString());
        facts.put("inTime", stock.getInTime().toString());
        return facts;
    }

    private Map<String, String> locationFacts(MdmWarehouseLocationEntity location) {
        var facts = new TreeMap<String, String>();
        facts.put("id", location.getId().toString());
        facts.put("tenantId", location.getTenantId().toString());
        facts.put("factoryCode", location.getFactoryCode());
        facts.put("locationCode", location.getLocationCode());
        facts.put("locationName", location.getLocationName());
        facts.put("warehouseCode", location.getWarehouseCode());
        facts.put("warehouseName", location.getWarehouseName());
        facts.put("status", location.getStatus().toString());
        facts.put("locationLevel", location.getLocationLevel().toString());
        return facts;
    }

    private MesProductionNgCaptureDTO.Receipt verified(MesProductionNgCaptureEntity row) {
        require(row != null && COMMAND_VERSION.equals(row.getCommandVersion()) && EVIDENCE_VERSION.equals(row.getEvidenceVersion())
                        && "ISOLATED".equals(row.getStatus())
                        && Objects.equals(row.getCommandFingerprint(), codec.fingerprint(COMMAND_VERSION, row.getCommandSnapshot()))
                        && Objects.equals(row.getEvidenceFingerprint(), codec.fingerprint(EVIDENCE_VERSION, row.getEvidenceSnapshot())),
                "原采集版本、原文或SHA不一致");
        var source = reports.selectById(row.getSourceUnqualifiedReportId());
        require(source != null && sourceCodec.verify(source), "原生产不良冻结事实缺失");
        var evidence = codec.objectDocument(row.getEvidenceSnapshot(), 8 * 1024 * 1024);
        require(evidence.size() == 20 && evidence.path("sourceSnapshot").asText().equals(source.getSourceSnapshot())
                && evidence.path("sourceFingerprint").asText().equals(source.getSourceFingerprint()), "原NG计量证据不一致");
        var stock = caseReceipts.rootStock(row.getStockId(), row.getTenantId(), row.getFactoryCode());
        var inflow = flows.selectById(row.getInflowId());
        var qualityCase = cases.selectById(row.getCaseId());
        require(stock != null && stock.getSourceReportId() == null && inflow != null && qualityCase != null
                        && Objects.equals(codec.snapshot(stockFacts(stock)), evidence.path("stockSnapshot").asText())
                        && Objects.equals(qualityCase.getSourceSnapshot(), row.getEvidenceSnapshot())
                        && Objects.equals(qualityCase.getProductionNgCaptureId(), row.getId())
                        && Objects.equals(qualityCase.getSourceUnqualifiedReportId(), source.getId())
                        && Objects.equals(qualityCase.getInitialInflowId(), inflow.getId())
                        && Objects.equals(inflow.getSourceStockId(), stock.getId())
                        && Objects.equals(inflow.getProductionNgCaptureId(), row.getId())
                        && Objects.equals(inflow.getSourceUnqualifiedReportId(), source.getId())
                        && inflow.getOperateNum() != null && inflow.getOperateNum().compareTo(row.getQuantity()) == 0
                        && inflow.getStockBeforeNum() != null && inflow.getStockBeforeNum().signum() == 0
                        && inflow.getStockAfterNum() != null && inflow.getStockAfterNum().compareTo(row.getQuantity()) == 0
                        && qualityCase.getQuantity() != null && qualityCase.getQuantity().compareTo(row.getQuantity()) == 0,
                "原NG采集五方事实或落库数量精度不一致");
        return new MesProductionNgCaptureDTO.Receipt(row.getRequestId(), row.getStatus(), row.getTenantId().toString(),
                row.getFactoryCode(), row.getActorId().toString(), row.getId().toString(), source.getId().toString(),
                stock.getId().toString(), inflow.getId().toString(), qualityCase.getId().toString(), row.getCommandVersion(),
                row.getCommandSnapshot(), row.getCommandFingerprint(), row.getEvidenceVersion(),
                row.getEvidenceSnapshot(), row.getEvidenceFingerprint(), row.getExecutionChannel(),
                source.getIdentificationVersion(), source.getIdentificationSnapshot(), source.getIdentificationFingerprint());
    }

    private MesTaskReportUnqualifiedEntity source(String requestId, String factory) {
        return reports.selectOne(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
                .eq(MesTaskReportUnqualifiedEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportUnqualifiedEntity::getFactoryCode, factory)
                .eq(MesTaskReportUnqualifiedEntity::getRequestId, requestId));
    }

    private LambdaQueryWrapper<MesProductionNgCaptureEntity> scoped(String factory) {
        return new LambdaQueryWrapper<MesProductionNgCaptureEntity>()
                .eq(MesProductionNgCaptureEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProductionNgCaptureEntity::getFactoryCode, factory);
    }

    private void ready() {
        require(Boolean.TRUE.equals(baseMapper.ready()), "生产NG隔离底座未就绪，请先部署V2第259节和配套服务");
    }

    private String text(JsonNode node, String key) {
        return node.get(key).textValue();
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
