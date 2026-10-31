package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mes.dto.MesStartMaterialBindingDTO.*;
import com.kitex.mom.mes.entity.MesStartMaterialBindingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesStartMaterialBindingMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
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
 * 首次批次开工来源池；与真实start共事务，既不新记产也不二次扣库存。
 */
@Service
@RequiredArgsConstructor
public class MesStartMaterialBindingService {
    private static final String KIND = "START_MATERIAL_BINDING";
    private final MesStartMaterialBindingMapper mapper;
    private final MesStartMaterialBindingReader reader;
    private final MesStartMaterialBindingCodec codec;
    private final MesPhysicalTransformCodec physical;
    private final MesActualFactoryAccessService access;
    private final MesExecutionTaskLock locks;
    private final MesTaskArrangeService arrangements;
    private final MesStartMaterialSourceVerifier sourceVerifier;
    private final MesMaterialConsumptionModeResolver modes;
    private final MesExecutionTemplateService templates;
    private final MesProductionFeedAllocationPlanner planner;
    private final MesExecutionCommandCancellationService cancellations;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preparation preparation(String arrangeId) {
        var owner = owner("read");
        var arrange = arrange(owner, physical.id(arrangeId), false);
        var reasons = new ArrayList<String>();
        var sources = new ArrayList<Source>();
        String consumption = null;
        String reportMode = arrange.getReportMode();
        boolean eligible = false;
        try {
            var context = context(owner, arrange);
            consumption = "FEED";
            reportMode = ReportModeEnum.QUANTITY.name();
            var candidates = mapper.candidates(owner.tenant(), owner.factory(), arrange.getCode());
            require(candidates != null && candidates.size() <= 100,
                    "本派工投料来源超过100条，当前入口容量不足；未截断来源列表，请受控核对");
            eligible = true;
            for (var feed : candidates) {
                try {
                    sources.add(source(owner, context, feed));
                } catch (CheckedException error) {
                    reasons.add("来源" + feed.getId() + "：" + error.getMessage());
                }
            }
            if (sources.isEmpty()) {
                reasons.add("没有完整证明且可用的投料来源");
            }
        } catch (CheckedException error) {
            reasons.add(error.getMessage());
        }
        return new Preparation(MesStartMaterialBindingCodec.VERSION, owner.tenant().toString(), owner.factory(),
                owner.actor().toString(), now(), arrangeId, arrange.getCode(), arrange.getTaskCode(), arrange.getProduceTaskCode(),
                arrange.getSourceRouteNodeCode(), arrange.getBatchNo(), reportMode, consumption,
                eligible && !sources.isEmpty(), List.copyOf(reasons), List.copyOf(sources));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Query query) {
        require(query != null, "开工来源预览缺少完整查询");
        var owner = owner("read");
        var arrange = arrange(owner, physical.id(query.getArrangeId()), false);
        var prepared = prepare(owner, arrange, codec.ids(query.getFeedIds()), false);
        return new Preview(MesStartMaterialBindingCodec.VERSION, owner.tenant().toString(), owner.factory(),
                owner.actor().toString(), now(), arrange.getId().toString(), arrange.getCode(), arrange.getTaskCode(),
                arrange.getProduceTaskCode(), arrange.getSourceRouteNodeCode(), arrange.getBatchNo(),
                prepared.fingerprint(), prepared.sources());
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
            return receipt(owner, command.getRequestId(), "APPLIED", reader.verified(original));
        }
        cancellations.assertActive(KIND, command.getRequestId(), codec.requestHash(command));
        var before = locks.arrange(physical.id(command.getArrangeId()));
        var arrange = arrange(owner, physical.id(command.getArrangeId()), true);
        locks.assertArrange(before, arrange);
        var ids = codec.ids(command.getFeedIds());
        var prepared = prepare(owner, arrange, ids, true);
        require(Objects.equals(prepared.fingerprint(), command.getExpectedPreviewFingerprint()),
                "生产批次、工程、来源或原数量已变化，请保留原号并重新核对预览");

        arrangements.startWithMaterialSources(arrange.getId(), ids);
        var started = arrange(owner, arrange.getId(), false);
        require("PRODUCT".equals(started.getStatus()) && started.getStartTime() != null && started.getMaterialGateRecordId() != null,
                "实际开工未形成完整开工时刻和物料门凭据，绑定回滚");
        require(Objects.equals(arrange.getCode(), started.getCode()) && Objects.equals(arrange.getTaskCode(), started.getTaskCode())
                && Objects.equals(arrange.getProduceTaskCode(), started.getProduceTaskCode())
                && Objects.equals(arrange.getBatchNo(), started.getBatchNo())
                && Objects.equals(arrange.getSourceRouteNodeCode(), started.getSourceRouteNodeCode()), "实际开工改变原批次或派工归属，绑定回滚");
        var row = new MesStartMaterialBindingEntity();
        row.setId(IdWorker.getId());
        row.setTenantId(owner.tenant());
        row.setFactoryCode(owner.factory());
        row.setArrangeId(started.getId());
        row.setArrangeCode(started.getCode());
        row.setTaskCode(started.getTaskCode());
        row.setProduceTaskCode(started.getProduceTaskCode());
        row.setRouteNodeCode(started.getSourceRouteNodeCode());
        row.setProductBatchNo(started.getBatchNo());
        row.setRequestId(command.getRequestId());
        row.setRequestHash(codec.requestHash(command));
        row.setPreviewFingerprint(prepared.fingerprint());
        row.setMaterialGateRecordId(started.getMaterialGateRecordId());
        row.setOperatorId(owner.actor());
        row.setOperatorCode(owner.actorCode());
        row.setStartedAt(started.getStartTime());
        row.setCreateBy(owner.actorCode());
        row.setCreateTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        row.setDelFlag("0");
        var document = new LinkedHashMap<>(prepared.proof());
        document.put("contractVersion", MesStartMaterialBindingCodec.VERSION);
        document.put("command", command);
        document.put("commandJson", physical.snapshot(command));
        document.put("previewFingerprint", prepared.fingerprint());
        document.put("materialGateRecordId", row.getMaterialGateRecordId().toString());
        document.put("startedAt", row.getStartedAt().toString());
        row.setBindingJson(physical.snapshot(document));
        row.setBindingFingerprint(physical.fingerprint(MesStartMaterialBindingCodec.VERSION, row.getBindingJson()));
        require(mapper.insert(row) == 1, "开工来源绑定必要写失败，真实开工一起回滚");
        var saved = original(owner, command.getRequestId());
        require(saved != null && Objects.equals(saved.getId(), row.getId()) && Objects.equals(saved.getBindingJson(), row.getBindingJson()),
                "绑定完整原文回读不一致，真实开工一起回滚");
        reader.binding(started);
        return receipt(owner, command.getRequestId(), "APPLIED", reader.verified(saved));
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
            return receipt(owner, command.getRequestId(), "APPLIED", reader.verified(original));
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
            return receipt(owner, requestId, "APPLIED", reader.verified(original));
        }
        return receipt(owner, requestId, cancellations.cancelled(KIND, requestId) ? "CANCELLED" : "ABSENT", null);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Receipt arrangement(String arrangeId) {
        var owner = owner("read");
        var arrange = arrange(owner, physical.id(arrangeId), false);
        var row = reader.binding(arrange);
        return receipt(owner, row == null ? null : row.getRequestId(), row == null ? "ABSENT" : "APPLIED",
                row == null ? null : reader.verified(row));
    }

    private Prepared prepare(Owner owner, MesTaskArrangeEntity arrange, List<Long> ids, boolean locked) {
        var context = context(owner, arrange);
        var selected = mapper.selected(owner.tenant(), owner.factory(), ids, locked);
        require(selected != null && selected.size() == ids.size()
                && selected.stream().map(MesWarehouseFeedEntity::getId).toList().equals(ids), "所选每个精确投料ID都必须存在且属于当前实际组织");
        var sources = selected.stream().map(feed -> source(owner, context, feed)).toList();
        var requirements = MesTaskUtils.getCurrentProcess(context.task().getProcessRouteSnapshot(), arrange.getRouteCode(),
                arrange.getSourceRouteNodeCode()).getMdmProcessProcedureMatList();
        require(requirements != null && !requirements.isEmpty(), "原工序没有实际物料需求，不能伪建来源绑定");
        planner.plan(new MesProductionFeedAllocationPlanner.Scope(owner.tenant(), owner.factory(), arrange.getTaskCode(),
                        arrange.getProduceTaskCode(), arrange.getCode(), context.unit()),
                List.of(new MesProductionFeedAllocationPlanner.OutputDemand("START_REMAINING", MesProductionFeedAllocationPlanner.OutputKind.GOOD,
                        context.remaining(), context.unit())), requirements, context.task().getBomSnapshot(), selected,
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
        proof.put("selectedSources", sources);
        return new Prepared(proof, physical.fingerprint(MesStartMaterialBindingCodec.PREVIEW, physical.snapshot(proof)), sources);
    }

    private Context context(Owner owner, MesTaskArrangeEntity arrange) {
        require("RECEIVE".equals(arrange.getStatus()) && arrange.getStartTime() == null,
                "来源绑定仅允许从未开工的已接收派工");
        require(reader.binding(arrange) == null, "本派工已有不可变来源绑定");
        require(mapper.reports(owner.tenant(), owner.factory(), arrange.getCode()) == 0, "已有GOOD或NG生产事实，不允许补建开工绑定");
        require(ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType()) == ReportModeEnum.QUANTITY,
                "首期仅支持数量报工的实际生产批次共用来源池");
        physical.code(arrange.getBatchNo(), "服务器生产批次", 100);
        physical.code(arrange.getSourceRouteNodeCode(), "实际稳定路线节点", 100);
        var contract = templates.contract(arrange);
        require(contract != null && contract.getIdentifierNodePolicy() != null
                && "NONE".equals(contract.getIdentifierNodePolicy().getAction()), "动态标识节点不支持本批次来源池协议");
        var tasks = mapper.tasks(owner.tenant(), owner.factory(), arrange.getTaskCode());
        require(tasks != null && tasks.size() == 1, "实际父工单不唯一或缺失");
        var task = tasks.get(0);
        require(task.getReworkRequestId() == null && task.getReworkMaterialPlanId() == null && !"REWORK".equals(task.getType()), "返工不支持本首次批次来源池协议");
        require(modes.resolve(arrange) == MesMaterialConsumptionModeResolver.Mode.FEED, "倒冲须独立实际库存协议，不能建立投料来源绑定");
        require(arrange.getCompleteNum() == null || arrange.getCompleteNum().signum() == 0, "已有完成量不能补建首次开工绑定");
        BigDecimal remaining = physical.quantity(physical.amount(Objects.requireNonNull(arrange.getNoticeNum())), false, "原派工剩余生产量");
        String unit = measure(owner, arrange.getMatCode()).getBasicUnit();
        return new Context(arrange, task, remaining, unit);
    }

    private Source source(Owner owner, Context context, MesWarehouseFeedEntity feed) {
        return sourceVerifier.source(owner.tenant(), owner.factory(), context.arrange(), context.task(), feed, true);
    }

    private MdmMaterialMeasureDTO measure(Owner owner, String code) {
        return sourceVerifier.measure(owner.tenant(), owner.factory(), code);
    }

    private MesTaskArrangeEntity arrange(Owner owner, Long id, boolean locked) {
        var row = locked ? mapper.lockedArrange(owner.tenant(), owner.factory(), id) : mapper.arrange(owner.tenant(), owner.factory(), id);
        require(row != null, "当前实际租户工厂未找到原派工");
        return row;
    }

    private MesStartMaterialBindingEntity original(Owner owner, String request) {
        require(Boolean.TRUE.equals(mapper.ready()), "开工来源绑定目录保护未就绪");
        var rows = mapper.byRequest(owner.tenant(), owner.factory(), request);
        require(rows != null && rows.size() <= 1, "原请求绑定不唯一或无法读取");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertReplay(Owner owner, Command command, MesStartMaterialBindingEntity row) {
        reader.verified(row);
        require(Objects.equals(row.getOperatorId(), owner.actor()) && Objects.equals(row.getRequestHash(), codec.requestHash(command)), "原号属于其他操作者或不同原内容，不能覆盖");
    }

    private void assertOwner(Owner owner, Command command) {
        require(Objects.equals(owner.tenant().toString(), command.getExpectedTenantId()) && Objects.equals(owner.factory(), command.getExpectedFactoryCode())
                && Objects.equals(owner.actor().toString(), command.getExpectedActorId()), "开工原命令与当前实际认证组织或本人不同");
    }

    private void lock(Owner owner, String request) {
        require(Boolean.TRUE.equals(mapper.ready()), "开工来源绑定目录保护未就绪");
        require(Objects.equals(mapper.lockRequest(owner.tenant(), owner.factory(), request), 1), "原请求锁未取得，请保留原号查询");
    }

    private Owner owner(String level) {
        String factory = access.require(level);
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && user != null && user.getId() != null && user.getId() > 0, "开工来源协议缺少实际租户或本人");
        return new Owner(tenant, factory, user.getId(), physical.code(user.getUsername(), "实际账号", 100));
    }

    private Receipt receipt(Owner owner, String request, String state, Map<String, Object> binding) {
        return new Receipt(MesStartMaterialBindingCodec.VERSION, owner.tenant().toString(), owner.factory(), owner.actor().toString(), now(), request, state, binding);
    }

    private String now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    private record Owner(Long tenant, String factory, Long actor, String actorCode) {
    }

    private record Context(MesTaskArrangeEntity arrange, MesTaskEntity task, BigDecimal remaining, String unit) {
    }

    private record Prepared(Map<String, Object> proof, String fingerprint, List<Source> sources) {
    }
}
