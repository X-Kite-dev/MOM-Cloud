package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesProductionFeedBatchEntity;
import com.kitex.mom.mes.entity.MesProductionFeedConsumptionEntity;
import com.kitex.mom.mes.entity.MesProductionFeedReversalEntity;
import com.kitex.mom.mes.entity.MesProductionFeedReversalOutputEntity;
import com.kitex.mom.mes.entity.MesProductionFeedRestoreLineEntity;
import com.kitex.mom.mes.entity.MesProductionFeedRestoreShareEntity;
import com.kitex.mom.mes.entity.MesProductionFeedShareEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.mapper.MesProductionFeedConsumptionMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedOutputMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedReversalMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedReversalOutputMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedRestoreLineMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedRestoreShareMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedShareMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTerminalExecutionCommandMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.service.MesProductionFeedReversalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 一次选择若干真实产出，只恢复它们完整的原耗料份额。
 * 保留原报告与库存；质量、入库、流转、包装、换码和下游已有事实时不借本协议逆向。
 * 无公开入口和客户端旁路，原号回放先于可变资格门，实际负向凭证由PG独立认领。
 * 四张恢复永久账及负向凭证合同已在累计V2提供；完整来源资格与业务ready尚未提供，仍未开放。
 * installed门先拒绝，不以永久账目录就绪冒充可执行，也不把只恢复耗料当生产/库存撤销。
 */
@Service
@RequiredArgsConstructor
public class MesProductionFeedReversalServiceImpl
        extends ServiceImpl<MesProductionFeedReversalMapper, MesProductionFeedReversalEntity>
        implements MesProductionFeedReversalService {
    public static final String COMMAND_VERSION = "MES_PRODUCTION_FEED_RESTORE_COMMAND_V1";
    public static final String EVIDENCE_VERSION = "MES_PRODUCTION_FEED_RESTORE_V1";
    private final MesProductionFeedOutputMapper outputs;
    private final MesProductionFeedConsumptionMapper consumptions;
    private final MesProductionFeedShareMapper shares;
    private final MesProductionFeedReversalOutputMapper members;
    private final MesProductionFeedRestoreLineMapper lines;
    private final MesProductionFeedRestoreShareMapper restoredShares;
    private final MesTaskArrangeMapper arrangements;
    private final MesTerminalExecutionCommandMapper terminals;
    private final MesWarehouseFeedMapper feeds;
    private final MesPhysicalTransformSourceScopeLock locks;
    private final MesActualFactoryAccessService access;
    private final MesPhysicalTransformCodec codec;
    private final MesProductionFeedLedgerFacts facts;
    private final MesProductionFeedEvidenceCodec evidence;

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse')")
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesProductionFeedReversalEntity restoreOutputs(String requestId, Long batchId, String expectedFingerprint,
                                                          List<Long> outputIds, String reason) {
        String factory = access.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        Long actor = SecurityUtils.getUser().getId();
        require(tenant != null && tenant > 0 && actor != null && actor > 0, "耗料纠正须实际认证租户与本人");
        validateRequest(requestId);
        require(batchId != null && batchId > 0 && expectedFingerprint != null && expectedFingerprint.matches("[0-9a-f]{64}"),
                "耗料纠正须真实原batch和完整SHA");
        codec.reason(reason);
        require(outputIds != null && !outputIds.isEmpty() && outputIds.size() <= 1000
                && outputIds.stream().allMatch(id -> id != null && id > 0)
                && new HashSet<>(outputIds).size() == outputIds.size(), "须显式选择1至1000个不重复真实产出");
        var selectedIds = outputIds.stream().map(Object::toString).sorted().toList();
        String command = codec.snapshot(command(requestId, tenant, factory, actor, batchId, expectedFingerprint, selectedIds, reason));
        ready();
        require(Boolean.TRUE.equals(baseMapper.tryRequest("MES_PRODUCTION_FEED_RESTORE_REQUEST_V1|" + tenant + "|" + requestId)),
                "原耗料纠正号仍在执行，请保留原号，不另号重投");
        var replay = baseMapper.permanentRequest(tenant, requestId);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory) && Objects.equals(replay.getActorId(), actor)
                    && Objects.equals(replay.getCommandSnapshot(), command), "原纠正号不可换厂、换人或变更完整参数");
            return verified(replay);
        }

        locks.acquire(tenant, factory);
        var batch = baseMapper.originalBatch(tenant, factory, batchId);
        require(batch != null && Objects.equals(batch.getEvidenceFingerprint(), expectedFingerprint),
                "只有已提交且SHA一致的原总耗用账允许纠正");
        locks.task(tenant, factory, batch.getTaskCode());
        var candidates = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, batch.getArrangeCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
        require(candidates.size() == 1, "原派工未唯一归属当前实际组织");
        var arrange = candidates.get(0);
        require(Objects.equals(arrange.getTaskCode(), batch.getTaskCode())
                && Objects.equals(arrange.getProduceTaskCode(), batch.getProduceTaskCode()), "原工单/工序归属不同");
        // 不借移动执行ThreadLocal许可：内部材料纠正不允许混入未决终端事务。
        require(terminals.pending(tenant, factory, arrange.getId()).isEmpty(), "原派工存在移动执行未决原号，禁止裸恢复耗料");
        var originalOutputs = outputs.permanentRows(tenant, factory, batchId);
        var originalConsumptions = consumptions.permanentRows(tenant, factory, batchId);
        var originalShares = shares.permanentRows(tenant, factory, batchId);
        facts.verify(batch, originalOutputs, originalConsumptions, originalShares);
        baseMapper.assertOriginalBatch(batch.getId());
        var selected = new HashSet<>(outputIds);
        var selectedOutputs = originalOutputs.stream().filter(row -> selected.contains(row.getId())).toList();
        require(selectedOutputs.size() == selected.size(), "选定产出未全部唯一属于原总耗用账");
        // 本地原件与跨域资格是两层必要条件；完整集合一次提交，不逐产出重复验证相同工序图。
        baseMapper.assertLocalOrigins(codec.snapshot(selectedIds));
        for (var output : selectedOutputs) {
            baseMapper.assertOutputClear(output.getId());
        }
        // 只选原永久边，不按当前BOM、比例或剩余产量重新分摊。
        var selectedShares = originalShares.stream().filter(row -> selected.contains(row.getOutputId())).toList();
        Map<Long, MesProductionFeedConsumptionEntity> originals = new HashMap<>();
        originalConsumptions.forEach(row -> require(originals.put(row.getId(), row) == null, "原耗用根主键重复"));
        var grouped = new TreeMap<Long, List<MesProductionFeedShareEntity>>();
        for (var share : selectedShares) {
            var consumption = originals.get(share.getConsumptionId());
            require(consumption != null, "原份额没有真实原耗用根");
            grouped.computeIfAbsent(consumption.getFeedId(), ignored -> new ArrayList<>()).add(share);
        }

        var reversal = new MesProductionFeedReversalEntity();
        reversal.setId(IdWorker.getId());
        reversal.setRequestId(requestId);
        reversal.setBatchId(batchId);
        reversal.setActorId(actor);
        reversal.setOriginalFingerprint(expectedFingerprint);
        reversal.setReason(reason);
        reversal.setStatus("MATERIAL_RESTORED");
        reversal.setCommandVersion(COMMAND_VERSION);
        reversal.setCommandSnapshot(command);
        reversal.setCommandFingerprint(codec.fingerprint(COMMAND_VERSION, command));
        reversal.setEvidenceVersion(EVIDENCE_VERSION);
        stamp(reversal, tenant, factory, actor);
        var memberRows = new ArrayList<MesProductionFeedReversalOutputEntity>();
        for (var output : selectedOutputs) {
            var member = new MesProductionFeedReversalOutputEntity();
            member.setId(IdWorker.getId());
            member.setReversalId(reversal.getId());
            member.setBatchId(batchId);
            member.setOutputId(output.getId());
            member.setOriginalOutputSnapshot(codec.snapshot(facts.outputFacts(output)));
            stamp(member, tenant, factory, actor);
            memberRows.add(member);
        }
        Map<Long, MesWarehouseFeedEntity> locked = new HashMap<>();
        var lineRows = new ArrayList<MesProductionFeedRestoreLineEntity>();
        for (var group : grouped.entrySet()) {
            var consumption = originals.get(group.getValue().get(0).getConsumptionId());
            require(group.getValue().stream().allMatch(row -> Objects.equals(row.getConsumptionId(), consumption.getId())),
                    "同feed恢复不能混入另一原耗用根");
            var candidatesFeed = feeds.selectList(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                    .eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                    .eq(MesWarehouseFeedEntity::getId, group.getKey()).last("LIMIT 2 FOR UPDATE NOWAIT"));
            require(candidatesFeed.size() == 1, "原feed不存在或未唯一归属当前工厂");
            var feed = candidatesFeed.get(0);
            require(Boolean.FALSE.equals(feed.getIsLock()) && Objects.equals(feed.getTaskCode(), batch.getTaskCode())
                    && Objects.equals(feed.getProduceTaskCode(), batch.getProduceTaskCode())
                    && Objects.equals(feed.getArrangeCode(), batch.getArrangeCode())
                    && Objects.equals(feed.getMatCode(), consumption.getMatCode()) && Objects.equals(feed.getMatUnit(), consumption.getMatUnit())
                    && feed.getFeedNum() != null && feed.getFeedNum().compareTo(consumption.getFeedNum()) == 0
                    && feed.getUsedNum() != null, "原投料锁定、来源/单位/总量不同或当前耗用未知，不能换料恢复");
            BigDecimal quantity = group.getValue().stream().map(MesProductionFeedShareEntity::getConsumeNum)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(quantity.signum() > 0 && feed.getUsedNum().compareTo(quantity) >= 0, "原选定份额不为正量或当前余额不足");
            var line = new MesProductionFeedRestoreLineEntity();
            line.setId(IdWorker.getId());
            line.setReversalId(reversal.getId());
            line.setBatchId(batchId);
            line.setConsumptionId(consumption.getId());
            line.setFeedId(feed.getId());
            line.setMatCode(consumption.getMatCode());
            line.setMatUnit(consumption.getMatUnit());
            line.setFeedNum(consumption.getFeedNum());
            line.setRestoredNum(quantity);
            line.setUsedBeforeNum(feed.getUsedNum());
            line.setUsedAfterNum(feed.getUsedNum().subtract(quantity));
            line.setOriginalConsumptionSnapshot(codec.snapshot(facts.consumptionFacts(consumption)));
            stamp(line, tenant, factory, actor);
            lineRows.add(line);
            locked.put(feed.getId(), feed);
        }
        Map<Long, MesProductionFeedRestoreLineEntity> lineIndex = new HashMap<>();
        lineRows.forEach(row -> lineIndex.put(row.getConsumptionId(), row));
        var shareRows = new ArrayList<MesProductionFeedRestoreShareEntity>();
        for (var original : selectedShares) {
            var row = new MesProductionFeedRestoreShareEntity();
            row.setId(IdWorker.getId());
            row.setReversalId(reversal.getId());
            row.setBatchId(batchId);
            row.setOriginalShareId(original.getId());
            row.setOutputId(original.getOutputId());
            row.setConsumptionId(original.getConsumptionId());
            row.setRestoreLineId(lineIndex.get(original.getConsumptionId()).getId());
            row.setConsumeNum(original.getConsumeNum());
            row.setRequirementNum(original.getRequirementNum());
            row.setOriginalShareSnapshot(codec.snapshot(facts.shareFacts(original)));
            stamp(row, tenant, factory, actor);
            shareRows.add(row);
        }
        // 原不可变batch以主键/SHA引用，选中原行全文保留；不重复复制整个batch大正文。
        var document = header(reversal);
        document.put("outputs", sorted(memberRows.stream().map(this::memberFacts).toList()));
        document.put("lines", sorted(lineRows.stream().map(this::lineFacts).toList()));
        document.put("shares", sorted(shareRows.stream().map(this::shareFacts).toList()));
        reversal.setEvidenceSnapshot(evidence.freezeRestore(document));
        reversal.setEvidenceFingerprint(codec.fingerprint(EVIDENCE_VERSION, reversal.getEvidenceSnapshot()));
        require(save(reversal), "逐产出耗料纠正永久主账必要写失败");
        require(members.insertFrozenRows(rowsJson(memberRows.stream().map(this::memberFacts).toList(), reversal),
                tenant.toString(), factory, reversal.getId().toString()) == memberRows.size(), "选定产出全集必要写失败");
        for (var line : lineRows) {
            var feed = locked.get(line.getFeedId());
            var update = new LambdaUpdateWrapper<MesWarehouseFeedEntity>().eq(MesWarehouseFeedEntity::getId, feed.getId())
                    .eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                    .eq(MesWarehouseFeedEntity::getTaskCode, batch.getTaskCode()).eq(MesWarehouseFeedEntity::getProduceTaskCode, batch.getProduceTaskCode())
                    .eq(MesWarehouseFeedEntity::getArrangeCode, batch.getArrangeCode()).eq(MesWarehouseFeedEntity::getMatCode, line.getMatCode())
                    .eq(MesWarehouseFeedEntity::getMatUnit, line.getMatUnit()).eq(MesWarehouseFeedEntity::getFeedNum, line.getFeedNum())
                    .eq(MesWarehouseFeedEntity::getUsedNum, line.getUsedBeforeNum()).eq(MesWarehouseFeedEntity::getIsLock, false);
            require(feeds.update(null, update.set(MesWarehouseFeedEntity::getUsedNum, line.getUsedAfterNum())) == 1,
                    "选定原份额实际feed负向CAS必要写失败");
            var after = feeds.selectById(feed.getId());
            require(after != null && after.getUsedNum() != null && after.getUsedNum().compareTo(line.getUsedAfterNum()) == 0,
                    "实际feed精度不足，禁止舍入或以当前值替代原回执");
        }
        if (!lineRows.isEmpty()) {
            require(lines.insertFrozenRows(rowsJson(lineRows.stream().map(this::lineFacts).toList(), reversal),
                    tenant.toString(), factory, reversal.getId().toString()) == lineRows.size(), "逐feed恢复根全集必要写失败");
            require(restoredShares.insertFrozenRows(rowsJson(shareRows.stream().map(this::shareFacts).toList(), reversal),
                    tenant.toString(), factory, reversal.getId().toString()) == shareRows.size(), "选定全部原份额全集必要写失败");
        }
        var saved = verified(baseMapper.permanentRequest(tenant, requestId));
        require(Objects.equals(saved.getEvidenceSnapshot(), reversal.getEvidenceSnapshot()), "纠正原正文回读不同");
        return saved;
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse')")
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesProductionFeedReversalEntity original(String requestId) {
        String factory = access.require("read");
        validateRequest(requestId);
        ready();
        var reversal = baseMapper.permanentRequest(TenantContextHolder.getTenantId(), requestId);
        if (reversal == null) {
            return null;
        }
        require(Objects.equals(reversal.getFactoryCode(), factory) && Objects.equals(reversal.getActorId(), SecurityUtils.getUser().getId()),
                "原耗料纠正不是当前工厂本人，不能隐藏原号并另号重做");
        return verified(reversal);
    }

    /** 回放仅核永久原事实，不以当前包装/质量/余额改变已经执行的回执。 */
    private MesProductionFeedReversalEntity verified(MesProductionFeedReversalEntity reversal) {
        require(reversal != null && COMMAND_VERSION.equals(reversal.getCommandVersion())
                && EVIDENCE_VERSION.equals(reversal.getEvidenceVersion()) && "MATERIAL_RESTORED".equals(reversal.getStatus())
                && Objects.equals(reversal.getCommandFingerprint(), codec.fingerprint(COMMAND_VERSION, reversal.getCommandSnapshot()))
                && Objects.equals(reversal.getEvidenceFingerprint(), codec.fingerprint(EVIDENCE_VERSION, reversal.getEvidenceSnapshot())),
                "逐产出耗料纠正原版本或SHA不一致");
        var original = evidence.restoreDocument(reversal.getEvidenceSnapshot());
        var observed = header(reversal);
        observed.put("outputs", sorted(members.permanentRows(reversal.getTenantId(), reversal.getFactoryCode(), reversal.getId())
                .stream().map(this::memberFacts).toList()));
        observed.put("lines", sorted(lines.permanentRows(reversal.getTenantId(), reversal.getFactoryCode(), reversal.getId())
                .stream().map(this::lineFacts).toList()));
        observed.put("shares", sorted(restoredShares.permanentRows(reversal.getTenantId(), reversal.getFactoryCode(), reversal.getId())
                .stream().map(this::shareFacts).toList()));
        require(original.equals(evidence.restoreDocument(codec.snapshot(observed))),
                "逐产出耗料纠正永久全集回读不一致");
        return reversal;
    }

    private void ready() {
        require(Boolean.TRUE.equals(baseMapper.installed()), "完整逐产出恢复业务目录尚未部署，禁止执行或判定原号未受理");
        require(Boolean.TRUE.equals(baseMapper.ready()), "逐产出恢复业务目录或真实来源保护不完整，禁止执行");
    }

    private void validateRequest(String requestId) {
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{8,64}"), "耗料纠正原号须为8至64位稳定文本");
    }

    private Map<String, Object> command(String request, Long tenant, String factory, Long actor, Long batch,
                                        String fingerprint, List<String> ids, String reason) {
        var result = new TreeMap<String, Object>();
        result.put("version", COMMAND_VERSION);
        result.put("requestId", request);
        result.put("tenantId", text(tenant));
        result.put("factoryCode", factory);
        result.put("actorId", text(actor));
        result.put("batchId", text(batch));
        result.put("originalFingerprint", fingerprint);
        result.put("outputIds", ids);
        result.put("reason", reason);
        return result;
    }

    private Map<String, Object> header(MesProductionFeedReversalEntity row) {
        var result = new TreeMap<String, Object>();
        result.put("version", EVIDENCE_VERSION);
        result.put("reversalId", text(row.getId()));
        result.put("requestId", row.getRequestId());
        result.put("tenantId", text(row.getTenantId()));
        result.put("factoryCode", row.getFactoryCode());
        result.put("actorId", text(row.getActorId()));
        result.put("batchId", text(row.getBatchId()));
        result.put("originalFingerprint", row.getOriginalFingerprint());
        result.put("reason", row.getReason());
        result.put("commandSnapshot", row.getCommandSnapshot());
        return result;
    }

    private Map<String, Object> memberFacts(MesProductionFeedReversalOutputEntity row) {
        var result = child(row.getId(), row.getReversalId(), row.getBatchId());
        result.put("outputId", text(row.getOutputId()));
        result.put("originalOutputSnapshot", row.getOriginalOutputSnapshot());
        return result;
    }

    private Map<String, Object> lineFacts(MesProductionFeedRestoreLineEntity row) {
        var result = child(row.getId(), row.getReversalId(), row.getBatchId());
        result.put("consumptionId", text(row.getConsumptionId()));
        result.put("feedId", text(row.getFeedId()));
        result.put("matCode", row.getMatCode());
        result.put("matUnit", row.getMatUnit());
        result.put("feedNum", codec.amount(row.getFeedNum()));
        result.put("restoredNum", codec.amount(row.getRestoredNum()));
        result.put("usedBeforeNum", codec.amount(row.getUsedBeforeNum()));
        result.put("usedAfterNum", codec.amount(row.getUsedAfterNum()));
        result.put("originalConsumptionSnapshot", row.getOriginalConsumptionSnapshot());
        return result;
    }

    private Map<String, Object> shareFacts(MesProductionFeedRestoreShareEntity row) {
        var result = child(row.getId(), row.getReversalId(), row.getBatchId());
        result.put("originalShareId", text(row.getOriginalShareId()));
        result.put("outputId", text(row.getOutputId()));
        result.put("consumptionId", text(row.getConsumptionId()));
        result.put("restoreLineId", text(row.getRestoreLineId()));
        result.put("consumeNum", codec.amount(row.getConsumeNum()));
        result.put("requirementNum", codec.amount(row.getRequirementNum()));
        result.put("originalShareSnapshot", row.getOriginalShareSnapshot());
        return result;
    }

    private Map<String, Object> child(Long id, Long reversal, Long batch) {
        var result = new TreeMap<String, Object>();
        result.put("id", text(id));
        result.put("reversalId", text(reversal));
        result.put("batchId", text(batch));
        return result;
    }

    private List<Map<String, Object>> sorted(List<Map<String, Object>> rows) {
        return rows.stream().sorted(Comparator.comparing(row -> (String) row.get("id"))).toList();
    }

    private String rowsJson(List<Map<String, Object>> rows, MesProductionFeedReversalEntity reversal) {
        return codec.snapshot(sorted(rows).stream().map(row -> {
            var result = new LinkedHashMap<>(row);
            result.put("tenantId", text(reversal.getTenantId()));
            result.put("factoryCode", reversal.getFactoryCode());
            result.put("createBy", text(reversal.getActorId()));
            result.put("delFlag", "0");
            return result;
        }).toList());
    }

    private String text(Long value) {
        return value == null ? null : value.toString();
    }

    /** 审计只取当前真实本人；created_xid和feedMutationId仅由PG写入。 */
    private void stamp(BaseEntity<?> entity, Long tenant, String factory, Long actor) {
        entity.setTenantId(tenant);
        entity.setCreateBy(actor.toString());
        entity.setDelFlag("0");
        if (entity instanceof MesProductionFeedReversalEntity row) {
            row.setFactoryCode(factory);
        } else if (entity instanceof MesProductionFeedReversalOutputEntity row) {
            row.setFactoryCode(factory);
        } else if (entity instanceof MesProductionFeedRestoreLineEntity row) {
            row.setFactoryCode(factory);
        } else if (entity instanceof MesProductionFeedRestoreShareEntity row) {
            row.setFactoryCode(factory);
        }
    }
}
