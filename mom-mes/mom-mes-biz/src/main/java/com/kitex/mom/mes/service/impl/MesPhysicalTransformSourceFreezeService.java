package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import com.kitex.mom.mdm.support.MdmPhysicalTransformStandardCodec;
import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceLockMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesWarehouseLiveStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 服务器真实来源V2：完整写域保护→全部原工单try锁→父行/全图/派工/报工/精确库存NOWAIT。
 * 返回的是本次锁内事实；事务结束即释放锁，不登记claim，不产生PREPARED或执行许可。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalTransformSourceFreezeService {
    public static final String SOURCE_VERSION = "MES_PHYSICAL_SOURCE_V2";
    public static final String BUNDLE_VERSION = "MES_PHYSICAL_SOURCE_BUNDLE_V2";
    private static final int MAX_SNAPSHOT = 16 * 1024 * 1024;

    private final MesPhysicalTransformSourceScopeLock scopeLock;
    private final MesPhysicalTransformSourceLockMapper locks;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesTaskReportMapper reports;
    private final MesPhysicalTransformSourceService originalSources;
    private final MesPhysicalTransformPolicyValidator policies;
    private final MesPhysicalTransformEngineeringFreezeService engineering;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;

    /**
     * 对外完整观察没有持久写入，但实际FOR UPDATE不能包装成PG只读事务。
     * 权限仍沿库存查看/转运查询范围，不从观察权限取得实际转换操作权。
     */
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_view') or @pms.hasPermission('mes_wipTransfer_dispatch')")
    @Transactional(rollbackFor = Exception.class)
    public MesPhysicalTransformSourceBundleDTO observe(List<String> stockIds) {
        return lockAndRead(stockIds);
    }

    /** 后续真正prepare必须在其原号事务内重新调用；不能直接持久化客户端返回包。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesPhysicalTransformSourceBundleDTO lockAndRead(List<String> stockIds) {
        require(stockIds != null && !stockIds.isEmpty() && stockIds.size() <= MesPhysicalTransformCodec.MAX_SOURCES,
                "须提供1至50个精确库存主键，禁止同码/FIFO补选");
        List<Long> ids = stockIds.stream().map(codec::id).sorted().toList();
        require(new HashSet<>(ids).size() == ids.size(), "来源库存主键不能重复");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = access.require("read");
        scopeLock.acquire(tenant, factory);

        // 写域先保护所有实际MES表，源归属发现不使用客户端工单编码。
        var observedStocks = stocks.selectList(new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getTenantId, tenant).eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
                .in(MesWarehouseLiveStockEntity::getId, ids).orderByAsc(MesWarehouseLiveStockEntity::getId));
        require(observedStocks.size() == ids.size(), "精确库存缺失、已归档或越界，不能补选另一行");
        List<Long> reportIds = observedStocks.stream().map(MesWarehouseLiveStockEntity::getSourceReportId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<MesTaskReportEntity> observedReports = reportIds.isEmpty() ? List.of()
                : reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, tenant).eq(MesTaskReportEntity::getFactoryCode, factory)
                .in(MesTaskReportEntity::getId, reportIds).orderByAsc(MesTaskReportEntity::getId));
        List<String> taskCodes = observedReports.stream().map(MesTaskReportEntity::getTaskCode)
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
        for (String taskCode : taskCodes) scopeLock.task(tenant, factory, taskCode);

        Map<String, MesTaskEntity> tasks = new TreeMap<>();
        Map<String, List<MesProduceTaskEntity>> graphs = new TreeMap<>();
        for (String taskCode : taskCodes) {
            var found = locks.tasks(tenant, factory, taskCode);
            require(found.size() <= 1, "同组织原工单编码不唯一，禁止猜父行");
            if (!found.isEmpty()) tasks.put(taskCode, found.get(0));
            var graph = locks.graph(tenant, factory, taskCode);
            require(graph.size() <= 2000, "原工单实际工序图超过2000节点，拒绝截断");
            graphs.put(taskCode, graph);
        }
        Map<String, MesTaskArrangeEntity> arranges = new TreeMap<>();
        List<String> arrangeCodes = observedReports.stream().map(MesTaskReportEntity::getArrangeCode)
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
        for (String code : arrangeCodes) {
            var found = locks.arranges(tenant, factory, code);
            require(found.size() <= 1, "同组织原派工编码不唯一，禁止猜原工程");
            if (!found.isEmpty()) arranges.put(code, found.get(0));
        }
        Map<Long, MesTaskReportEntity> lockedReports = new TreeMap<>();
        for (var observed : observedReports) {
            var locked = locks.report(tenant, factory, observed.getId());
            require(locked != null && Objects.equals(observed.getTaskCode(), locked.getTaskCode())
                    && Objects.equals(observed.getArrangeCode(), locked.getArrangeCode())
                    && Objects.equals(observed.getProduceTaskCode(), locked.getProduceTaskCode()),
                    "原报工归属在取锁时改变，不能持行锁补取另一工单");
            lockedReports.put(locked.getId(), locked);
        }
        for (var observed : observedStocks) {
            var locked = locks.stock(tenant, factory, observed.getId());
            require(locked != null && Objects.equals(observed.getSourceReportId(), locked.getSourceReportId())
                    && Objects.equals(observed.getProduceTaskCode(), locked.getProduceTaskCode()),
                    "原库存来源在取锁时改变，不能更换另一来源");
        }

        var original = originalSources.sources(ids.stream().map(Object::toString).toList());
        List<Map<String, String>> capacities = capacities();
        List<MesPhysicalTransformSourceBundleDTO.Entry> entries = new ArrayList<>();
        for (var fact : original.getSources()) {
            var report = fact.getSourceReportId() == null ? null : lockedReports.get(codec.id(fact.getSourceReportId()));
            var task = report == null ? null : tasks.get(report.getTaskCode());
            var arrange = report == null ? null : arranges.get(report.getArrangeCode());
            var graph = task == null ? List.<MesProduceTaskEntity>of() : graphs.get(task.getCode());
            entries.add(entry(fact, report, task, arrange, graph, capacities, original));
        }
        var bundle = new MesPhysicalTransformSourceBundleDTO();
        bundle.setVersion(BUNDLE_VERSION);
        bundle.setTenantId(tenant.toString());
        bundle.setFactoryCode(factory);
        bundle.setActorUserId(SecurityUtils.getUser().getId().toString());
        bundle.setActorCode(SecurityUtils.getUser().getUsername());
        bundle.setQuantityColumns(capacities);
        bundle.setSources(entries);
        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("version", BUNDLE_VERSION);
        evidence.put("tenantId", bundle.getTenantId());
        evidence.put("factoryCode", factory);
        evidence.put("actorUserId", bundle.getActorUserId());
        evidence.put("actorCode", bundle.getActorCode());
        evidence.put("quantityColumns", capacities);
        evidence.put("sources", entries);
        bundle.setBundleSnapshot(document(evidence));
        bundle.setBundleFingerprint(codec.fingerprint(BUNDLE_VERSION, bundle.getBundleSnapshot()));
        bundle.setExecutionAvailable(false);
        bundle.setMessage("锁内完整原工单/工序/派工/工程观察，返回时锁释放；尚无永久claim、真实母件位置/全部下游资格或转换库存事务，不是PREPARED。V1旧观察原号不改写为V2。");
        return bundle;
    }

    /** 原工程只消费本地原派工，不查询当前MDM标准修补历史。 */
    private MesPhysicalTransformSourceBundleDTO.Entry entry(MesPhysicalTransformDTO.SourceFact fact, MesTaskReportEntity report,
            MesTaskEntity task, MesTaskArrangeEntity arrange, List<MesProduceTaskEntity> graph,
            List<Map<String, String>> capacities, MesPhysicalTransformDTO.SourceView owner) {
        List<String> blocked = new ArrayList<>(fact.getBlockingReasons());
        // 正常派工后父工单保留DISPATCH，实际开工由下面的工序及原派工事实共同证明。
        check(blocked, task != null && Set.of("DISPATCH", "PRODUCT", "FINISH").contains(Objects.toString(task.getStatus(), "")),
                "原生产工单缺失、未下达派工、已关闭或状态未确认");
        List<MesProduceTaskEntity> nodes = report == null ? List.of() : graph.stream()
                .filter(node -> Objects.equals(report.getProduceTaskCode(), node.getCode())).toList();
        var node = nodes.size() == 1 ? nodes.get(0) : null;
        check(blocked, node != null && Set.of("PRODUCT", "PAUSE", "FINISH").contains(Objects.toString(node.getStatus(), "")),
                "原实际工序缺失、不唯一、未实际开工或已关闭");
        check(blocked, task != null && arrange != null && node != null
                && Objects.equals(task.getCode(), arrange.getTaskCode()) && Objects.equals(task.getCode(), node.getTaskCode())
                && Objects.equals(node.getCode(), arrange.getProduceTaskCode()) && Objects.equals(node.getRouteCode(), arrange.getRouteCode())
                && Objects.equals(task.getMatCode(), fact.getMaterialCode()) && Objects.equals(node.getMatCode(), fact.getMaterialCode())
                && task.getProcessRouteId() != null && Objects.equals(task.getProcessRouteId(), node.getProcessRouteId())
                && Objects.equals(task.getProcessRouteCode(), node.getProcessRouteCode())
                && task.getProcessRouteSnapshot() != null && Objects.equals(task.getProcessRouteSnapshot(), node.getProcessRouteSnapshot()),
                "原工单/工序/派工物料、路线及实际来源归属不一致");

        MdmPhysicalTransformStandardDTO.Frozen standard = null;
        String configuration = null;
        if (arrange != null) {
            var policy = policies.readFrozen(arrange);
            configuration = policy.getConfigurationJson();
            if (MesPhysicalTransformPolicyValidator.NONE.equals(configuration)) blocked.add("原派工是NONE或缺转换工程，不能从当前模板补造转换资格");
            else {
                standard = engineering.readFrozen(arrange, configuration);
                var body = MdmPhysicalTransformStandardCodec.verifyFrozen(standard);
                var dependency = codec.objectDocument(body.path("dependencySnapshot").textValue(), 4 * 1024 * 1024);
                check(blocked, task != null && task.getProcessRouteId() != null
                        && task.getProcessRouteId().toString().equals(body.path("processRouteId").textValue())
                        && Objects.equals(task.getProcessRouteCode(), body.path("processRouteCode").textValue())
                        && Objects.equals(task.getProcessRouteSnapshot(), dependency.path("route").path("procedureSnapshot").textValue()),
                        "原派工正式工程与实际原工单路线不一致，不能用当前路线修补");
                if ("DEPANEL".equals(codec.policy(configuration).getOperationType()))
                    blocked.add("当前尚无永久母件实际位置及合格/报废/缺位余额账，批准布局不能代替实物位置");
            }
        } else blocked.add("原派工不存在，不能证明真实转换工程");

        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("version", SOURCE_VERSION);
        evidence.put("tenantId", owner.getTenantId());
        evidence.put("factoryCode", owner.getFactoryCode());
        evidence.put("actorUserId", owner.getActorUserId());
        evidence.put("actorCode", owner.getActorCode());
        evidence.put("originVersion", MesPhysicalTransformCodec.SOURCE_VERSION);
        evidence.put("originFingerprint", fact.getFingerprint());
        evidence.put("originSnapshot", fact.getFactSnapshot());
        evidence.put("task", codec.facts(task));
        evidence.put("produceTask", codec.facts(node));
        evidence.put("completeTaskGraph", graph.stream().map(codec::facts).toList());
        evidence.put("arrange", codec.facts(arrange));
        evidence.put("report", codec.facts(report));
        evidence.put("policyConfiguration", configuration);
        evidence.put("standard", standard);
        evidence.put("quantityColumns", capacities);
        evidence.put("blockingReasons", List.copyOf(blocked));
        var entry = new MesPhysicalTransformSourceBundleDTO.Entry();
        entry.setStockId(fact.getStockId());
        entry.setSourceVersion(SOURCE_VERSION);
        entry.setOriginFingerprint(fact.getFingerprint());
        entry.setSourceSnapshot(document(evidence));
        entry.setSourceFingerprint(codec.fingerprint(SOURCE_VERSION, entry.getSourceSnapshot()));
        entry.setStandardId(standard == null ? null : standard.getStandardId());
        entry.setStandardFingerprint(standard == null ? null : standard.getContentFingerprint());
        entry.setPolicyConfiguration(configuration);
        entry.setSourceChecksPassed(blocked.isEmpty());
        entry.setBlockingReasons(List.copyOf(blocked));
        // V1本地对象只用于计算适配；保留原V1 raw/SHA于entry，不回写任何旧历史。
        fact.setFactSnapshot(entry.getSourceSnapshot());
        fact.setFingerprint(entry.getSourceFingerprint());
        fact.setSourceChecksPassed(blocked.isEmpty());
        fact.setBlockingReasons(entry.getBlockingReasons());
        entry.setSource(fact);
        return entry;
    }

    /** 明确全四列，没有能力证据时拒绝把空集合当8位小数容量。 */
    private List<Map<String, String>> capacities() {
        var rows = locks.quantityColumns();
        require(rows != null && rows.size() == 4, "目标库库存/流水四个实际数量字段不可核验，不能假定Java精度等于PG容量");
        List<Map<String, String>> result = new ArrayList<>();
        for (var row : rows) {
            var normalized = new LinkedHashMap<String, String>();
            for (String key : List.of("table_name", "column_name", "data_type", "numeric_precision", "numeric_scale"))
                normalized.put(key, row.get(key));
            require("numeric".equals(normalized.get("data_type")), "实际库存/流水数量列不是numeric，须先明确精确类型兼容方案");
            result.add(normalized);
        }
        return result;
    }

    /** 全文和外层均按UTF-16限制，超界拒绝，不截取图、履历或来源。 */
    private String document(Object value) {
        String raw = codec.snapshot(value);
        codec.objectDocument(raw, MAX_SNAPSHOT);
        return raw;
    }

    private void check(List<String> blocked, boolean passed, String message) {
        if (!passed) blocked.add(message);
    }
}
