package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesProductionMaterialReversalDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesProductionMaterialOperationService;
import com.kitex.mom.mes.service.MesProductionMaterialReversalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 原NG投料误记的独立全额纠正。保持原报告/原耗用证据，恢复当前余额中的原分配份额，
 * 不取消生产声明、不调用GOOD冲销、不产生实物退料；数据库另核真实负向凭证及旧GOOD混写。
 */
@Service
@RequiredArgsConstructor
public class MesProductionMaterialReversalServiceImpl
        extends ServiceImpl<MesProductionMaterialReversalMapper, MesProductionMaterialReversalEntity>
        implements MesProductionMaterialReversalService {
    public static final String COMMAND_VERSION = "MES_NG_FEED_REVERSE_COMMAND_V1";
    public static final String EVIDENCE_VERSION = "MES_NG_FEED_RESTORE_V1";
    private static final Set<String> KEYS = Set.of("version", "requestId", "expectedTenantId", "expectedFactoryCode",
            "expectedActorUserId", "operationId", "expectedFingerprint", "reason");
    private final MesProductionMaterialOperationService operations;
    private final MesProductionMaterialOperationMapper operationMapper;
    private final MesProductionMaterialAllocationMapper allocations;
    private final MesProductionMaterialRestoreMapper restores;
    private final MesTaskReportUnqualifiedMapper reports;
    private final MesTaskArrangeMapper arrangements;
    private final MesWarehouseFeedMapper feeds;
    private final MesProductionNgSourceCodec sourceCodec;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;
    private final MesPhysicalTransformSourceScopeLock locks;
    private final MesTerminalExecutionGuard terminalGuard;
    private final PlatformTransactionManager transactions;

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse')")
    @Transactional(propagation = Propagation.NEVER)
    public MesProductionMaterialReversalDTO.Receipt reverse(String raw) {
        String factory = access.require("write");
        JsonNode command = command(raw, factory);
        // NEVER拒绝外层业务事务，模板产生唯一实际事务；网络不明只读查原号，不换号。
        return new TransactionTemplate(transactions).execute(status -> execute(raw, command, factory));
    }

    /**
     * 写入与只读冲突核账共用同一严格原文/实际身份协议，不从客户端账号猜用户ID。
     */
    private JsonNode command(String raw, String factory) {
        JsonNode command = codec.objectDocument(raw, 64 * 1024);
        require(command.size() == KEYS.size(), "耗料纠正原文缺字段或包含额外字段");
        KEYS.forEach(key -> require(command.has(key) && command.get(key).isTextual(), key + "必须是明确文本字段"));
        require(COMMAND_VERSION.equals(command.get("version").textValue()), "耗料纠正协议版本未知");
        codec.id(text(command, "operationId"));
        codec.reason(text(command, "reason"));
        require(text(command, "requestId").matches("[A-Za-z0-9_-]{8,64}"), "独立耗料纠正请求号须为8至64位");
        require(text(command, "expectedFingerprint").matches("[0-9a-f]{64}"), "须提交原耗用SHA文本");
        require(Objects.equals(codec.id(text(command, "expectedTenantId")), TenantContextHolder.getTenantId())
                        && Objects.equals(text(command, "expectedFactoryCode"), factory)
                        && Objects.equals(codec.id(text(command, "expectedActorUserId")), SecurityUtils.getUser().getId()),
                "耗料纠正只能使用当前实际实名租户工厂");
        return command;
    }

    private MesProductionMaterialReversalDTO.Receipt execute(String raw, JsonNode command, String factory) {
        ready();
        String requestId = text(command, "requestId");
        require(Boolean.TRUE.equals(baseMapper.tryRequest(codec.snapshot(List.of(COMMAND_VERSION,
                TenantContextHolder.getTenantId().toString(), requestId)))), "原纠正请求仍在执行，请保留原号查询结果");
        var replay = getOne(scoped(factory).eq(MesProductionMaterialReversalEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getActorId(), SecurityUtils.getUser().getId())
                    && Objects.equals(replay.getCommandSnapshot(), raw), "原纠正请求不能变更正文或操作人");
            return verified(replay);
        }
        var initial = operationMapper.selectOne(new LambdaQueryWrapper<MesProductionMaterialOperationEntity>()
                .eq(MesProductionMaterialOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProductionMaterialOperationEntity::getFactoryCode, factory)
                .eq(MesProductionMaterialOperationEntity::getId, codec.id(text(command, "operationId"))));
        require(initial != null, "当前工厂未找到精确原FEED耗用账");
        locks.task(initial.getTenantId(), factory, initial.getTaskCode());
        var arrange = arrangements.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, initial.getTenantId()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, initial.getArrangeCode()).last("FOR UPDATE NOWAIT"));
        require(arrange != null && Objects.equals(arrange.getTaskCode(), initial.getTaskCode())
                && Objects.equals(arrange.getProduceTaskCode(), initial.getProduceTaskCode()), "原派工归属已不一致");
        terminalGuard.assertUnoccupied(arrange);
        var source = reports.selectOne(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>()
                .eq(MesTaskReportUnqualifiedEntity::getTenantId, initial.getTenantId()).eq(MesTaskReportUnqualifiedEntity::getFactoryCode, factory)
                .eq(MesTaskReportUnqualifiedEntity::getId, initial.getSourceUnqualifiedReportId()).last("FOR SHARE NOWAIT"));
        require(source != null && sourceCodec.verify(source), "历史或未知不良来源不能补造耗料恢复");
        var op = operations.original(source);
        require(op != null && Objects.equals(op.getId(), initial.getId())
                && Objects.equals(op.getCommandFingerprint(), text(command, "expectedFingerprint")), "原耗用全文指纹不一致");
        require(attached(op) == null, "该原耗用已全额纠正，不能另号再次恢复");
        var original = allocations.selectList(new LambdaQueryWrapper<MesProductionMaterialAllocationEntity>()
                .eq(MesProductionMaterialAllocationEntity::getTenantId, op.getTenantId()).eq(MesProductionMaterialAllocationEntity::getFactoryCode, factory)
                .eq(MesProductionMaterialAllocationEntity::getOperationId, op.getId()));
        require(original.size() <= 5000, "原分配超过协议容量，不能截断恢复");
        var grouped = new TreeMap<Long, List<MesProductionMaterialAllocationEntity>>();
        original.forEach(line -> grouped.computeIfAbsent(line.getFeedId(), ignored -> new ArrayList<>()).add(line));
        var lines = new ArrayList<MesProductionMaterialRestoreEntity>();
        for (var group : grouped.entrySet()) {
            var feed = feeds.selectOne(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getTenantId, op.getTenantId()).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                    .eq(MesWarehouseFeedEntity::getId, group.getKey()).last("FOR UPDATE NOWAIT"));
            var first = group.getValue().get(0);
            require(feed != null && !Boolean.TRUE.equals(feed.getIsLock()) && Objects.equals(feed.getArrangeCode(), op.getArrangeCode())
                            && Objects.equals(feed.getTaskCode(), op.getTaskCode()) && Objects.equals(feed.getProduceTaskCode(), op.getProduceTaskCode())
                            && Objects.equals(feed.getMatCode(), first.getMatCode()) && Objects.equals(feed.getMatUnit(), first.getMatUnit())
                            && feed.getFeedNum() != null && feed.getFeedNum().compareTo(first.getFeedNum()) == 0 && feed.getUsedNum() != null,
                    "原投料不存在、锁定或归属/原单位/总量不一致，不能换投料恢复");
            BigDecimal quantity = group.getValue().stream().map(MesProductionMaterialAllocationEntity::getConsumeNum)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var line = new MesProductionMaterialRestoreEntity();
            line.setId(IdWorker.getId());
            line.setTenantId(op.getTenantId());
            line.setFactoryCode(factory);
            line.setOperationId(op.getId());
            line.setFeedId(feed.getId());
            line.setMatCode(feed.getMatCode());
            line.setMatUnit(feed.getMatUnit());
            line.setFeedNum(exact(feed.getFeedNum()));
            line.setRestoredNum(exact(quantity));
            line.setUsedBeforeNum(exact(feed.getUsedNum()));
            line.setUsedAfterNum(exact(feed.getUsedNum().subtract(quantity)));
            line.setAllocationIds(codec.snapshot(group.getValue().stream().map(a -> a.getId().toString()).sorted().toList()));
            line.setCreateBy(SecurityUtils.getUser().getUsername());
            line.setDelFlag("0");
            lines.add(line);
        }
        var header = new MesProductionMaterialReversalEntity();
        header.setId(IdWorker.getId());
        header.setTenantId(op.getTenantId());
        header.setFactoryCode(factory);
        header.setRequestId(requestId);
        header.setOperationId(op.getId());
        header.setSourceUnqualifiedReportId(source.getId());
        header.setActorId(SecurityUtils.getUser().getId());
        header.setReason(text(command, "reason"));
        header.setOriginalFingerprint(op.getCommandFingerprint());
        header.setCommandVersion(COMMAND_VERSION);
        header.setCommandSnapshot(raw);
        header.setCommandFingerprint(codec.fingerprint(COMMAND_VERSION, raw));
        header.setEvidenceVersion(EVIDENCE_VERSION);
        header.setStatus("MATERIAL_RESTORED");
        header.setCreateBy(SecurityUtils.getUser().getUsername());
        header.setDelFlag("0");
        header.setEvidenceSnapshot(codec.snapshot(evidence(header, lines)));
        require(header.getEvidenceSnapshot().length() <= 8 * 1024 * 1024, "完整恢复证据超过容量，尚未改投料余额");
        header.setEvidenceFingerprint(codec.fingerprint(EVIDENCE_VERSION, header.getEvidenceSnapshot()));
        require(save(header), "耗料恢复主账必要写失败，整笔回滚");
        for (var line : lines) {
            require(feeds.update(null, new LambdaUpdateWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getTenantId, op.getTenantId()).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                    .eq(MesWarehouseFeedEntity::getId, line.getFeedId()).eq(MesWarehouseFeedEntity::getFeedNum, line.getFeedNum())
                    .eq(MesWarehouseFeedEntity::getUsedNum, line.getUsedBeforeNum())
                    .set(MesWarehouseFeedEntity::getUsedNum, line.getUsedAfterNum())) == 1, "投料恢复必要写失败，整笔回滚");
            var current = feeds.selectById(line.getFeedId());
            require(current != null && current.getUsedNum() != null && current.getUsedNum().compareTo(line.getUsedAfterNum()) == 0,
                    "投料恢复精度回读不一致，禁止静默舍入");
            line.setReversalId(header.getId());
            require(restores.insert(line) == 1, "原分配恢复行未保存，整笔回滚");
        }
        return verified(getById(header.getId()));
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public MesProductionMaterialReversalDTO.Receipt receipt(String requestId) {
        String factory = access.require("read");
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{8,64}"), "原纠正请求号非法");
        ready();
        var row = getOne(scoped(factory).eq(MesProductionMaterialReversalEntity::getRequestId, requestId));
        if (row == null) return new MesProductionMaterialReversalDTO.Receipt(requestId, "ABSENT", null, null,
                TenantContextHolder.getTenantId().toString(), factory, SecurityUtils.getUser().getId().toString(),
                null, null, null, null, null, null);
        require(Objects.equals(row.getActorId(), SecurityUtils.getUser().getId()), "仅原操作人可恢复该待核纠正请求");
        return verified(row);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse','mesTaskReport:show','mesTaskReport:unqualified')")
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public MesProductionMaterialReversalDTO.Resolution resolve(String raw) {
        String factory = access.require("read");
        JsonNode command = command(raw, factory);
        ready();
        String requestId = text(command, "requestId");
        var own = getOne(scoped(factory).eq(MesProductionMaterialReversalEntity::getRequestId, requestId));
        MesProductionMaterialReversalDTO.Receipt applied = null;
        String state = "ABSENT";
        if (own != null) {
            require(Objects.equals(own.getActorId(), SecurityUtils.getUser().getId())
                    && Objects.equals(own.getCommandSnapshot(), raw), "原纠正请求不能变更正文或操作人");
            applied = verified(own);
            state = "APPLIED";
        } else {
            var original = operationMapper.selectOne(new LambdaQueryWrapper<MesProductionMaterialOperationEntity>()
                    .eq(MesProductionMaterialOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesProductionMaterialOperationEntity::getFactoryCode, factory)
                    .eq(MesProductionMaterialOperationEntity::getId, codec.id(text(command, "operationId"))));
            require(original != null && Objects.equals(original.getCommandFingerprint(), text(command, "expectedFingerprint")),
                    "未找到完整原文所指的精确原耗用账，不能清除待核");
            var other = getOne(scoped(factory).eq(MesProductionMaterialReversalEntity::getOperationId, original.getId()));
            if (other != null) {
                require(!Objects.equals(other.getRequestId(), requestId)
                                && Objects.equals(other.getOriginalFingerprint(), text(command, "expectedFingerprint")),
                        "另一恢复账不属于精确原耗用，不能清除待核");
                applied = verified(other);
                // 永久唯一operation及不可改/删原账保证本号以后也无法再执行；不是把ABSENT当授权。
                state = "NOT_APPLIED_ALREADY_RESTORED";
            }
        }
        return new MesProductionMaterialReversalDTO.Resolution(requestId, state,
                TenantContextHolder.getTenantId().toString(), factory, SecurityUtils.getUser().getId().toString(),
                COMMAND_VERSION, raw, codec.fingerprint(COMMAND_VERSION, raw), applied);
    }

    @Override
    public MesProductionMaterialReversalDTO.Receipt attached(MesProductionMaterialOperationEntity original) {
        String factory = access.require("read");
        require(original != null && Objects.equals(original.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(original.getFactoryCode(), factory), "只能查询当前工厂原耗用恢复账");
        ready();
        var row = getOne(scoped(factory).eq(MesProductionMaterialReversalEntity::getOperationId, original.getId()));
        return row == null ? null : verified(row);
    }

    private MesProductionMaterialReversalDTO.Receipt verified(MesProductionMaterialReversalEntity row) {
        require(row != null && COMMAND_VERSION.equals(row.getCommandVersion()) && EVIDENCE_VERSION.equals(row.getEvidenceVersion())
                && "MATERIAL_RESTORED".equals(row.getStatus())
                && Objects.equals(row.getCommandFingerprint(), codec.fingerprint(COMMAND_VERSION, row.getCommandSnapshot()))
                && Objects.equals(row.getEvidenceFingerprint(), codec.fingerprint(EVIDENCE_VERSION, row.getEvidenceSnapshot())), "原恢复账版本/原文/SHA不一致");
        var lines = restores.selectList(new LambdaQueryWrapper<MesProductionMaterialRestoreEntity>()
                .eq(MesProductionMaterialRestoreEntity::getTenantId, row.getTenantId()).eq(MesProductionMaterialRestoreEntity::getFactoryCode, row.getFactoryCode())
                .eq(MesProductionMaterialRestoreEntity::getReversalId, row.getId()));
        require(codec.objectDocument(row.getEvidenceSnapshot(), 8 * 1024 * 1024)
                .equals(codec.objectDocument(codec.snapshot(evidence(row, lines)), 8 * 1024 * 1024)), "原恢复分配全集回读不一致");
        return new MesProductionMaterialReversalDTO.Receipt(row.getRequestId(), "APPLIED", row.getId().toString(), row.getOperationId().toString(),
                row.getTenantId().toString(), row.getFactoryCode(), row.getActorId().toString(), row.getCommandVersion(), row.getCommandSnapshot(),
                row.getCommandFingerprint(), row.getEvidenceVersion(), row.getEvidenceSnapshot(), row.getEvidenceFingerprint());
    }

    /**
     * 固定原证据字段，实体增加属性不改变V1。
     */
    private Map<String, Object> evidence(MesProductionMaterialReversalEntity row, List<MesProductionMaterialRestoreEntity> lines) {
        var result = new TreeMap<String, Object>();
        result.put("version", EVIDENCE_VERSION);
        result.put("reversalId", row.getId().toString());
        result.put("operationId", row.getOperationId().toString());
        result.put("unqualifiedReportId", row.getSourceUnqualifiedReportId().toString());
        result.put("tenantId", row.getTenantId().toString());
        result.put("factoryCode", row.getFactoryCode());
        result.put("actorId", row.getActorId().toString());
        result.put("originalFingerprint", row.getOriginalFingerprint());
        result.put("requestId", row.getRequestId());
        result.put("reason", row.getReason());
        result.put("commandFingerprint", row.getCommandFingerprint());
        result.put("lines", lines.stream().map(this::lineFacts).sorted(Comparator.comparing(l -> (String) l.get("id"))).toList());
        return result;
    }

    private Map<String, Object> lineFacts(MesProductionMaterialRestoreEntity line) {
        var result = new TreeMap<String, Object>();
        result.put("id", line.getId().toString());
        result.put("feedId", line.getFeedId().toString());
        result.put("matCode", line.getMatCode());
        result.put("matUnit", line.getMatUnit());
        result.put("feedNum", number(line.getFeedNum()));
        result.put("restoredNum", number(line.getRestoredNum()));
        result.put("usedBeforeNum", number(line.getUsedBeforeNum()));
        result.put("usedAfterNum", number(line.getUsedAfterNum()));
        result.put("allocationIds", line.getAllocationIds());
        return result;
    }

    private LambdaQueryWrapper<MesProductionMaterialReversalEntity> scoped(String factory) {
        return new LambdaQueryWrapper<MesProductionMaterialReversalEntity>().eq(MesProductionMaterialReversalEntity::getTenantId,
                TenantContextHolder.getTenantId()).eq(MesProductionMaterialReversalEntity::getFactoryCode, factory);
    }

    private void ready() {
        require(Boolean.TRUE.equals(baseMapper.ready()), "耗料恢复保护未就绪，请先执行V2第258节；不能猜测未恢复");
    }

    private String text(JsonNode node, String key) {
        return node.get(key).textValue();
    }

    private String number(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private BigDecimal exact(BigDecimal quantity) {
        return codec.quantity(number(quantity), true, "原feed恢复量");
    }
}
