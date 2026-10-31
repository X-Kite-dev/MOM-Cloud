package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mes.dto.MesMaterialBindingSupplementDTO.*;
import com.kitex.mom.mes.dto.MesStartMaterialBindingDTO.Source;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesMaterialBindingSupplementMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 暂停批次的永久来源池追加；只确认已投来源，不开工、不扣库存。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialBindingSupplementService {
    private static final String KIND = "MATERIAL_BINDING_SUPPLEMENT";
    private final MesMaterialBindingSupplementMapper mapper;
    private final MesStartMaterialBindingReader reader;
    private final com.kitex.mom.mes.mapper.MesStartMaterialBindingMapper initialMapper;
    private final MesMaterialBindingPoolReader pools;
    private final MesTerminalExecutionGuard terminal;
    private final MesMaterialBindingSupplementCodec codec;
    private final MesPhysicalTransformCodec physical;
    private final MesActualFactoryAccessService access;
    private final MesExecutionTaskLock locks;
    private final MesStartMaterialSourceVerifier sourceVerifier;
    private final MesMaterialConsumptionModeResolver modes;
    private final MesExecutionTemplateService templates;
    private final MesProductionFeedAllocationPlanner planner;
    private final MesExecutionCommandCancellationService cancellations;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preparation preparation(String arrangeId) {
        var owner = owner("read");
        var arrange = arrange(owner, physical.id(arrangeId), false);
        var initial = requiredInitial(arrange);
        var pool = pools.pool(initial, arrange);
        var reasons = new ArrayList<String>();
        var sources = new ArrayList<Source>();
        boolean eligible = false;
        try {
            var context = context(owner, arrange, initial);
            validateExisting(owner, context, initial, pool);
            require(Integer.parseInt(pool.revision()) < 50 && pool.feedIds().size() < 100, "累计池或追加历史已达上限");
            var candidates = initialMapper.candidates(owner.tenant(), owner.factory(), arrange.getCode());
            require(candidates != null && candidates.size() <= 100, "派工来源超过100条，不能截断为完整候选");
            eligible = true;
            for (var feed : candidates) {
                if (pool.feedIds().contains(feed.getId().toString())) {
                    continue;
                }
                try {
                    sources.add(source(owner, context, feed, true));
                } catch (CheckedException error) {
                    reasons.add("来源" + feed.getId() + "：" + error.getMessage());
                }
            }
            if (sources.isEmpty()) {
                reasons.add("没有完整证明且可用的新增已投来源");
            }
        } catch (CheckedException error) {
            reasons.add(error.getMessage());
        }
        return new Preparation(MesMaterialBindingSupplementCodec.VERSION, owner.tenant().toString(), owner.factory(),
                owner.actor().toString(), now(), arrangeId, arrange.getCode(), arrange.getTaskCode(), arrange.getProduceTaskCode(),
                arrange.getSourceRouteNodeCode(), arrange.getBatchNo(), eligible && !sources.isEmpty(),
                List.copyOf(reasons), List.copyOf(sources), pool);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Query query) {
        require(query != null, "追加预览缺少完整查询");
        var owner = owner("read");
        var arrange = arrange(owner, physical.id(query.getArrangeId()), false);
        var prepared = prepare(owner, arrange, codec.ids(query.getAddedFeedIds()), false);
        return new Preview(MesMaterialBindingSupplementCodec.VERSION, owner.tenant().toString(), owner.factory(),
                owner.actor().toString(), now(), arrange.getId().toString(), arrange.getCode(), arrange.getTaskCode(),
                arrange.getProduceTaskCode(), arrange.getSourceRouteNodeCode(), arrange.getBatchNo(),
                prepared.fingerprint(), prepared.sources(), prepared.pool());
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt execute(String raw) {
        var command = codec.command(raw);
        var owner = owner("write");
        assertOwner(owner, command);
        lock(owner, command.getRequestId());
        var original = original(owner, command.getRequestId());
        if (original != null) {
            assertReplay(owner, command, original);
            return receipt(owner, command.getRequestId(), "APPLIED", verifiedOriginal(owner, original));
        }
        cancellations.assertActive(KIND, command.getRequestId(), codec.requestHash(command));
        var before = locks.arrange(physical.id(command.getArrangeId()));
        var arrange = arrange(owner, physical.id(command.getArrangeId()), true);
        locks.assertArrange(before, arrange);
        var ids = codec.ids(command.getAddedFeedIds());
        var prepared = prepare(owner, arrange, ids, true);
        require(Objects.equals(prepared.pool().fingerprint(), command.getExpectedPoolFingerprint())
                        && Objects.equals(prepared.fingerprint(), command.getExpectedPreviewFingerprint()),
                "累计池、工程、余额或资格已变化，请保留原号重新核对预览");
        var row = new MesMaterialBindingSupplementEntity();
        row.setId(IdWorker.getId());
        row.setTenantId(owner.tenant());
        row.setFactoryCode(owner.factory());
        row.setArrangeId(arrange.getId());
        row.setArrangeCode(arrange.getCode());
        row.setTaskCode(arrange.getTaskCode());
        row.setProduceTaskCode(arrange.getProduceTaskCode());
        row.setRouteNodeCode(arrange.getSourceRouteNodeCode());
        row.setProductBatchNo(arrange.getBatchNo());
        row.setRequestId(command.getRequestId());
        row.setRequestHash(codec.requestHash(command));
        row.setPreviewFingerprint(prepared.fingerprint());
        row.setInitialBindingId(physical.id(prepared.pool().initialBindingId()));
        row.setSequenceNo(Integer.parseInt(prepared.pool().revision()) + 1);
        row.setPreviousFingerprint(prepared.pool().fingerprint());
        row.setOperatorId(owner.actor());
        row.setOperatorCode(owner.actorCode());
        row.setConfirmedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        row.setCreateBy(owner.actorCode());
        row.setCreateTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        row.setDelFlag("0");
        var document = new LinkedHashMap<>(prepared.proof());
        document.put("contractVersion", MesMaterialBindingSupplementCodec.VERSION);
        document.put("command", command);
        document.put("commandJson", physical.snapshot(command));
        document.put("previewFingerprint", prepared.fingerprint());
        document.put("confirmedAt", row.getConfirmedAt().toString());
        row.setSupplementJson(physical.snapshot(document));
        row.setSupplementFingerprint(physical.fingerprint(MesMaterialBindingSupplementCodec.VERSION, row.getSupplementJson()));
        require(mapper.insert(row) == 1, "追加证据必要写失败，来源池不变");
        var saved = original(owner, command.getRequestId());
        require(saved != null && Objects.equals(saved.getId(), row.getId()) && Objects.equals(saved.getSupplementJson(), row.getSupplementJson()),
                "追加完整原文回读不一致，来源池不变");
        pools.pool(requiredInitial(arrange), arrange);
        return receipt(owner, command.getRequestId(), "APPLIED", pools.verified(saved));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(String raw) {
        var command = codec.command(raw);
        var owner = owner("write");
        assertOwner(owner, command);
        lock(owner, command.getRequestId());
        var original = original(owner, command.getRequestId());
        if (original != null) {
            assertReplay(owner, command, original);
            return receipt(owner, command.getRequestId(), "APPLIED", verifiedOriginal(owner, original));
        }
        cancellations.cancel(KIND, command.getRequestId(), codec.requestHash(command));
        return receipt(owner, command.getRequestId(), "CANCELLED", null);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Receipt request(String requestId) {
        codec.requestId(requestId);
        var owner = owner("read");
        var original = original(owner, requestId);
        if (original != null) {
            require(Objects.equals(original.getOperatorId(), owner.actor()), "原请求属于其他实际操作者");
            return receipt(owner, requestId, "APPLIED", verifiedOriginal(owner, original));
        }
        return receipt(owner, requestId, cancellations.cancelled(KIND, requestId) ? "CANCELLED" : "ABSENT", null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Arrangement arrangement(String arrangeId) {
        var owner = owner("read");
        var arrange = arrange(owner, physical.id(arrangeId), false);
        return new Arrangement(MesMaterialBindingSupplementCodec.VERSION, owner.tenant().toString(), owner.factory(),
                owner.actor().toString(), now(), arrangeId, pools.pool(requiredInitial(arrange), arrange));
    }

    private Prepared prepare(Owner owner, MesTaskArrangeEntity arrange, List<Long> addedIds, boolean locked) {
        var initial = requiredInitial(arrange);
        var pool = pools.pool(initial, arrange);
        var context = context(owner, arrange, initial);
        require(Integer.parseInt(pool.revision()) < 50, "追加历史最多50次");
        var allIds = new TreeSet<Long>();
        pool.feedIds().stream().map(Long::valueOf).forEach(allIds::add);
        for (Long id : addedIds) {
            require(allIds.add(id), "新增来源已经属于累计池，禁止删除、替换或重复加入");
        }
        require(allIds.size() <= 100, "累计来源池最多100个精确ID");
        var selected = initialMapper.selected(owner.tenant(), owner.factory(), List.copyOf(allIds), locked);
        require(selected != null && selected.stream().map(MesWarehouseFeedEntity::getId).toList().equals(List.copyOf(allIds)),
                "每个累计及新增来源必须存在于当前实际组织");
        var originals = pools.sources(reader.verified(initial).get("selectedSources"),
                new MesStartMaterialBindingCodec(physical).command((String) reader.verified(initial).get("commandJson")).getFeedIds(), false);
        for (var history : pool.history()) {
            var command = codec.command((String) history.get("commandJson"));
            originals.putAll(pools.sources(history.get("selectedSources"), command.getAddedFeedIds(), false));
        }
        var sources = new ArrayList<Source>();
        var states = new ArrayList<SourceState>();
        var existing = new ArrayList<ExistingSource>();
        var available = new ArrayList<MesWarehouseFeedEntity>();
        for (var feed : selected) {
            boolean added = addedIds.contains(feed.getId());
            var fact = source(owner, context, feed, added);
            if (added) {
                sources.add(fact);
                states.add(new SourceState(fact.feedId(), feed.getIsLock(),
                        feed.getMatValidEndDate() == null ? null : feed.getMatValidEndDate().toString(), sourceVerifier.eligible(feed)));
            } else {
                var map = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(fact,
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                        });
                pools.assertIdentity(originals.get(fact.feedId()), map);
                existing.add(new ExistingSource(fact.feedId(), fact.birthId(), fact.birthFingerprint(), fact.requirementMaterialCode(),
                        fact.actualMaterialCode(), fact.batchNo(), fact.barcode(), fact.unit(), fact.ratio(), fact.feedQuantity(), fact.usedQuantity(),
                        feed.getIsLock(), feed.getMatValidEndDate() == null ? null : feed.getMatValidEndDate().toString(), sourceVerifier.eligible(feed)));
            }
            if (sourceVerifier.eligible(feed)) {
                available.add(feed);
            }
        }
        var requirements = MesTaskUtils.getCurrentProcess(context.task().getProcessRouteSnapshot(), arrange.getRouteCode(),
                arrange.getSourceRouteNodeCode()).getMdmProcessProcedureMatList();
        require(requirements != null && !requirements.isEmpty(), "原工序没有完整实际物料需求");
        planner.plan(new MesProductionFeedAllocationPlanner.Scope(owner.tenant(), owner.factory(), arrange.getTaskCode(),
                        arrange.getProduceTaskCode(), arrange.getCode(), context.unit()),
                List.of(new MesProductionFeedAllocationPlanner.OutputDemand("SUPPLEMENT_REMAINING", MesProductionFeedAllocationPlanner.OutputKind.GOOD,
                        context.remaining(), context.unit())), requirements, context.task().getBomSnapshot(), available,
                code -> measure(owner, code));
        var proof = new LinkedHashMap<String, Object>();
        proof.put("tenantId", owner.tenant().toString());
        proof.put("factoryCode", owner.factory());
        proof.put("actorId", owner.actor().toString());
        proof.put("arrangeId", arrange.getId().toString());
        proof.put("arrangeCode", arrange.getCode());
        proof.put("taskCode", arrange.getTaskCode());
        proof.put("produceTaskCode", arrange.getProduceTaskCode());
        proof.put("routeNodeCode", arrange.getSourceRouteNodeCode());
        proof.put("productBatchNo", arrange.getBatchNo());
        proof.put("routeSnapshot", context.task().getProcessRouteSnapshot());
        proof.put("bomSnapshot", context.task().getBomSnapshot());
        proof.put("remainingQuantity", physical.amount(context.remaining()));
        proof.put("productionUnit", context.unit());
        proof.put("selectedSources", List.copyOf(sources));
        proof.put("selectedSourceStates", List.copyOf(states));
        proof.put("existingSources", List.copyOf(existing));
        proof.put("initialBindingId", pool.initialBindingId());
        proof.put("sequenceNo", Integer.toString(Integer.parseInt(pool.revision()) + 1));
        proof.put("previousFingerprint", pool.fingerprint());
        proof.put("expectedPoolFingerprint", pool.fingerprint());
        return new Prepared(proof, physical.fingerprint(MesMaterialBindingSupplementCodec.PREVIEW, physical.snapshot(proof)), List.copyOf(sources), pool);
    }

    private void validateExisting(Owner owner, Context context, MesStartMaterialBindingEntity initial, Pool pool) {
        var root = reader.verified(initial);
        var originals = pools.sources(root.get("selectedSources"),
                new MesStartMaterialBindingCodec(physical).command((String) root.get("commandJson")).getFeedIds(), false);
        for (var history : pool.history()) {
            var command = codec.command((String) history.get("commandJson"));
            originals.putAll(pools.sources(history.get("selectedSources"), command.getAddedFeedIds(), false));
        }
        var ids = pool.feedIds().stream().map(Long::valueOf).toList();
        var selected = initialMapper.selected(owner.tenant(), owner.factory(), ids, false);
        require(selected != null && selected.stream().map(MesWarehouseFeedEntity::getId).toList().equals(ids), "累计来源全集缺失或跨组织");
        for (var feed : selected) {
            var fact = source(owner, context, feed, false);
            var map = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(fact,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    });
            pools.assertIdentity(originals.get(fact.feedId()), map);
        }
    }

    private Context context(Owner owner, MesTaskArrangeEntity arrange, MesStartMaterialBindingEntity initial) {
        require("PAUSE".equals(arrange.getStatus()) && arrange.getStartTime() != null
                && Objects.equals(arrange.getStartTime(), initial.getStartedAt()), "仅允许原实际开工批次在暂停期间追加来源");
        require(arrange.getWorkerCode() != null && Arrays.stream(arrange.getWorkerCode().split(","))
                .map(String::trim).anyMatch(owner.actorCode()::equals), "当前用户不是派工指定的实际执行员工");
        terminal.assertUnoccupied(arrange);
        require(ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType()) == ReportModeEnum.QUANTITY,
                "仅支持普通数量报工的累计来源池");
        var contract = templates.contract(arrange);
        require(contract != null && contract.getIdentifierNodePolicy() != null
                && "NONE".equals(contract.getIdentifierNodePolicy().getAction()), "动态标识节点不支持追加来源");
        var tasks = initialMapper.tasks(owner.tenant(), owner.factory(), arrange.getTaskCode());
        require(tasks != null && tasks.size() == 1, "实际父工单不唯一或缺失");
        var task = tasks.get(0);
        require(task.getReworkRequestId() == null && task.getReworkMaterialPlanId() == null && !"REWORK".equals(task.getType()), "返工不支持本追加来源协议");
        require(modes.resolve(arrange) == MesMaterialConsumptionModeResolver.Mode.FEED, "仅支持实际FEED投料消耗");
        var root = reader.verified(initial);
        require(Objects.equals(root.get("routeSnapshot"), task.getProcessRouteSnapshot())
                && Objects.equals(root.get("bomSnapshot"), task.getBomSnapshot()), "原冻结工程与当前工单不同，禁止重解释累计池");
        require(arrange.getNoticeNum() != null && arrange.getCompleteNum() != null && arrange.getCompleteNum().signum() >= 0,
                "原计划量或实际完成量未知，不能推算剩余需求");
        BigDecimal remaining = physical.quantity(physical.amount(arrange.getNoticeNum().subtract(arrange.getCompleteNum())), false, "原派工剩余量");
        String unit = measure(owner, arrange.getMatCode()).getBasicUnit();
        require(Objects.equals(root.get("productionUnit"), unit), "产品基本单位与原开工计量不同，不能重解释原计划剩余量");
        return new Context(arrange, task, remaining, unit);
    }

    private MesStartMaterialBindingEntity requiredInitial(MesTaskArrangeEntity arrange) {
        var initial = reader.binding(arrange);
        require(initial != null, "本派工没有原不可变批次来源绑定，禁止补建");
        return initial;
    }

    private Source source(Owner owner, Context context, MesWarehouseFeedEntity feed, boolean added) {
        return sourceVerifier.source(owner.tenant(), owner.factory(), context.arrange(), context.task(), feed, added);
    }

    private MdmMaterialMeasureDTO measure(Owner owner, String code) {
        return sourceVerifier.measure(owner.tenant(), owner.factory(), code);
    }

    private MesTaskArrangeEntity arrange(Owner owner, Long id, boolean locked) {
        var row = locked ? initialMapper.lockedArrange(owner.tenant(), owner.factory(), id) : initialMapper.arrange(owner.tenant(), owner.factory(), id);
        require(row != null, "当前实际租户工厂未找到原派工");
        return row;
    }

    private MesMaterialBindingSupplementEntity original(Owner owner, String request) {
        require(Boolean.TRUE.equals(mapper.ready()), "追加来源目录保护未就绪");
        var rows = mapper.byRequest(owner.tenant(), owner.factory(), request);
        require(rows != null && rows.size() <= 1, "原请求绑定不唯一或无法读取");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertReplay(Owner owner, Command command, MesMaterialBindingSupplementEntity row) {
        verifiedOriginal(owner, row);
        require(Objects.equals(row.getOperatorId(), owner.actor()) && Objects.equals(row.getRequestHash(), codec.requestHash(command)), "原号属于其他操作者或不同原内容，不能覆盖");
    }

    private Map<String, Object> verifiedOriginal(Owner owner, MesMaterialBindingSupplementEntity row) {
        var document = pools.verified(row);
        var arrange = arrange(owner, row.getArrangeId(), false);
        var pool = pools.pool(requiredInitial(arrange), arrange);
        require(pool.history().stream().anyMatch(history -> Objects.equals(history.get("commandJson"), document.get("commandJson"))),
                "原成功追加不属于完整累计链");
        return document;
    }

    private void assertOwner(Owner owner, Command command) {
        require(Objects.equals(owner.tenant().toString(), command.getExpectedTenantId()) && Objects.equals(owner.factory(), command.getExpectedFactoryCode())
                && Objects.equals(owner.actor().toString(), command.getExpectedActorId()), "追加原命令与当前实际认证组织或本人不同");
    }

    private void lock(Owner owner, String request) {
        require(Boolean.TRUE.equals(mapper.ready()), "追加来源目录保护未就绪");
        require(Objects.equals(mapper.lockRequest(owner.tenant(), owner.factory(), request), 1), "原请求锁未取得，请保留原号查询");
    }

    private Owner owner(String level) {
        String factory = access.require(level);
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && user != null && user.getId() != null && user.getId() > 0, "追加来源协议缺少实际租户或本人");
        return new Owner(tenant, factory, user.getId(), physical.code(user.getUsername(), "实际账号", 100));
    }

    private Receipt receipt(Owner owner, String request, String state, Map<String, Object> binding) {
        return new Receipt(MesMaterialBindingSupplementCodec.VERSION, owner.tenant().toString(), owner.factory(), owner.actor().toString(),
                now(), request, state, binding);
    }

    private String now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    private record Owner(Long tenant, String factory, Long actor, String actorCode) {
    }

    private record Context(MesTaskArrangeEntity arrange, MesTaskEntity task, BigDecimal remaining, String unit) {
    }

    private record Prepared(Map<String, Object> proof, String fingerprint, List<Source> sources, Pool pool) {
    }
}
