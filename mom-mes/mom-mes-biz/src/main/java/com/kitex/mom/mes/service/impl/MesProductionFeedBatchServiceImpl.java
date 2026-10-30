package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesProductionFeedBatchService;
import com.kitex.mom.mes.service.impl.material.MesProductionFeedAllocationPlanner;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 真实产出先落库，再统一选料、一次扣总feed并保存每份产出归属。
 * 本组件只负责本地总投料事实，不能替代生产协调器的全部生产门、库存、计数与未知恢复。
 * 当前无公开组合入口；新账没有独立份额恢复前，数据库拒绝旧消费者裸逆向。
 */
@Service
@RequiredArgsConstructor
public class MesProductionFeedBatchServiceImpl
        extends ServiceImpl<MesProductionFeedBatchMapper, MesProductionFeedBatchEntity>
        implements MesProductionFeedBatchService {

    public static final String VERSION = MesProductionFeedLedgerFacts.BATCH_VERSION;
    private static final int MAX_DOCUMENT = MesProductionFeedEvidenceCodec.MAX_BATCH_DOCUMENT;
    private final MesProductionFeedOutputMapper outputs;
    private final MesProductionFeedConsumptionMapper consumptions;
    private final MesProductionFeedShareMapper shares;
    private final MesTaskArrangeMapper arrangements;
    private final MesTaskMapper tasks;
    private final MesWarehouseFeedMapper feeds;
    private final MesExecutionTaskLock taskLock;
    private final MesActualFactoryAccessService access;
    private final MesPhysicalTransformCodec codec;
    private final MesProductionFeedLedgerFacts ledgerFacts;
    private final MesProductionFeedEvidenceCodec evidence;
    private final MesProductionFeedAllocationPlanner planner;
    private final RemoteMaterialService materials;
    private final MesMaterialConsumptionModeResolver consumptionModes;
    private final MesStartMaterialBindingReader materialBindings;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public MesProductionFeedBatchEntity recordFeed(String requestId, String arrangeCode, List<SourceRef> references) {
        String factory = access.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        Long actor = SecurityUtils.getUser().getId();
        require(tenant != null && tenant > 0 && actor != null && actor > 0, "总投料需要实际认证租户和本人");
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{8,64}"), "总投料原号须为8至64位稳定文本");
        require(Boolean.TRUE.equals(baseMapper.ready()), "独立总投料原账未就绪，请先升级V2第262节");
        require(Boolean.TRUE.equals(baseMapper.tryRequest("MES_PRODUCTION_FEED_BATCH_REQUEST_V1|" + tenant + "|" + requestId)),
                "本生产原号正在处理，不能另号重投");
        require(baseMapper.permanentRequest(tenant, requestId) == null,
                "原生产已有总投料原账，须由协调器回放原回执，不能重新计算或耗料");

        var ownership = taskLock.arrange(codec.code(arrangeCode, "真实派工", 100));
        var candidates = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, arrangeCode).last("LIMIT 2 FOR UPDATE NOWAIT"));
        require(candidates.size() == 1, "总投料原派工未唯一归属当前组织");
        var arrange = candidates.get(0);
        taskLock.assertArrange(ownership, arrange);
        var parents = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
        require(parents.size() == 1 && "PRODUCT".equals(arrange.getStatus()), "总投料需要唯一真实开工工单及派工");
        var task = parents.get(0);
        require(references != null && !references.isEmpty() && references.size() <= 1000,
                "本次真实产出须为1至1000条，零量不得伪建");

        var outputRows = new ArrayList<MesProductionFeedOutputEntity>();
        var keys = new HashSet<String>();
        var sourceKeys = new HashSet<String>();
        String productionUnit = null;
        for (var reference : references) {
            require(reference != null && reference.kind() != null && reference.sourceId() != null && reference.sourceId() > 0,
                    "产出必须明确真实GOOD或NG报告域");
            String key = codec.code(reference.outputKey(), "产出稳定键", 100);
            require(keys.add(key) && sourceKeys.add(reference.kind().name() + "|" + reference.sourceId()),
                    "真实产出key或报告重复，不能两次认领同一产出");
            var birth = baseMapper.actualBirth(tenant, factory, reference.kind().name(), reference.sourceId());
            require(birth != null && Boolean.TRUE.equals(birth.getSameTransaction()),
                    "仅允许本事务首次真实产出，旧报告不得补建组合耗用");
            JsonNode facts = evidence.batchDocument(birth.getSourceSnapshot());
            require(facts.path("id").asText().equals(reference.sourceId().toString())
                    && facts.path("tenant_id").asText().equals(tenant.toString())
                    && factory.equals(facts.path("factory_code").asText())
                    && task.getCode().equals(facts.path("task_code").asText())
                    && arrange.getProduceTaskCode().equals(facts.path("produce_task_code").asText())
                    && arrangeCode.equals(facts.path("arrange_code").asText())
                    && arrange.getMatCode().equals(facts.path("mat_code").asText())
                    && "0".equals(facts.path("del_flag").asText()), "真实产出原归属与本派工不一致");
            boolean good = reference.kind() == MesProductionFeedAllocationPlanner.OutputKind.GOOD;
            BigDecimal quantity = codec.quantity(birth.getQuantity(), false, "原产出数量");
            String unit = codec.code(facts.path("report_unit").asText(), "原产出基本单位", 100);
            require(productionUnit == null || productionUnit.equals(unit), "本次产出原单位不同，不能隐式合计");
            productionUnit = unit;
            if (good) {
                require("EFFECTIVE".equals(facts.path("status").asText())
                                && !"ROUTE_RETURN".equals(facts.path("execution_kind").asText())
                                && codec.quantity(birth.getEffectiveQuantity(), false, "原GOOD有效数量").compareTo(quantity) == 0
                                && codec.quantity(birth.getCorrectedQuantity(), true, "原GOOD纠正数量").signum() == 0,
                        "只有真实有效正量GOOD允许总投料；退回执行不再次耗料");
            } else {
                require("MES_PRODUCTION_NG_SOURCE_V1".equals(facts.path("source_version").asText())
                        && actor.toString().equals(facts.path("captured_actor_id").asText()), "NG缺本次本人真实原计量");
            }
            var row = new MesProductionFeedOutputEntity();
            row.setId(IdWorker.getId());
            row.setOutputKey(key);
            row.setOutputKind(reference.kind().name());
            row.setGoodReportId(good ? reference.sourceId() : null);
            row.setNgReportId(good ? null : reference.sourceId());
            row.setSourceBirthId(birth.getId());
            row.setSourceSnapshot(birth.getSourceSnapshot());
            row.setQuantity(quantity);
            row.setProductionUnit(unit);
            outputRows.add(row);
        }
        outputRows.sort(Comparator.comparing(MesProductionFeedOutputEntity::getOutputKey));
        var route = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(),
                arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
        var requirements = Objects.requireNonNullElse(route.getMdmProcessProcedureMatList(),
                List.<ProcessProcedureDTO.ProcessProcedureMatDTO>of());
        require(consumptionModes.resolve(arrange) == MesMaterialConsumptionModeResolver.Mode.FEED,
                "原派工固定为倒冲，需独立真实库存协议，不能借FEED记账");
        var feedQuery = new LambdaQueryWrapper<MesWarehouseFeedEntity>().eq(MesWarehouseFeedEntity::getTenantId, tenant)
                .eq(MesWarehouseFeedEntity::getFactoryCode, factory).eq(MesWarehouseFeedEntity::getTaskCode, task.getCode())
                .eq(MesWarehouseFeedEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                .eq(MesWarehouseFeedEntity::getArrangeCode, arrangeCode).eq(MesWarehouseFeedEntity::getIsLock, false)
                .and(w -> w.isNull(MesWarehouseFeedEntity::getMatValidEndDate)
                        .or().ge(MesWarehouseFeedEntity::getMatValidEndDate, LocalDate.now()))
                .orderByAsc(MesWarehouseFeedEntity::getId);
        materialBindings.constrain(arrange, feedQuery);
        var eligible = requirements.isEmpty() ? List.<MesWarehouseFeedEntity>of() : feeds.selectList(feedQuery.last("LIMIT 5001 FOR UPDATE NOWAIT"));
        require(eligible.size() <= 5000, "实际投料超过5000条，不能截断");
        var demands = outputRows.stream().map(row -> new MesProductionFeedAllocationPlanner.OutputDemand(row.getOutputKey(),
                MesProductionFeedAllocationPlanner.OutputKind.valueOf(row.getOutputKind()), row.getQuantity(), row.getProductionUnit())).toList();
        var plan = planner.plan(new MesProductionFeedAllocationPlanner.Scope(tenant, factory, task.getCode(),
                arrange.getProduceTaskCode(), arrangeCode, productionUnit), demands, requirements, task.getBomSnapshot(), eligible, this::measure);

        var batch = new MesProductionFeedBatchEntity();
        batch.setId(IdWorker.getId());
        batch.setRequestId(requestId);
        batch.setActorId(actor);
        batch.setTaskCode(task.getCode());
        batch.setProduceTaskCode(arrange.getProduceTaskCode());
        batch.setArrangeCode(arrangeCode);
        batch.setProductionUnit(productionUnit);
        batch.setProcessedQuantity(plan.processedQuantity());
        batch.setStatus("MATERIAL_RECORDED");
        batch.setEvidenceVersion(VERSION);
        stamp(batch, tenant, factory, actor);
        outputRows.forEach(row -> {
            row.setBatchId(batch.getId());
            stamp(row, tenant, factory, actor);
        });
        outputRows.sort(Comparator.comparing(row -> row.getId().toString()));
        var consumptionRows = consumptionRows(plan, batch);
        var shareRows = shareRows(plan, batch, outputRows, consumptionRows);
        var document = ledgerFacts.header(batch);
        document.put("routeSnapshot", task.getProcessRouteSnapshot());
        document.put("bomSnapshot", task.getBomSnapshot());
        document.put("requirements", requirements.stream().map(ledgerFacts::requirementFacts).toList());
        document.put("outputs", outputRows.stream().map(ledgerFacts::outputFacts).toList());
        document.put("consumptions", consumptionRows.stream().map(ledgerFacts::consumptionFacts).toList());
        document.put("shares", shareRows.stream().map(ledgerFacts::shareFacts).toList());
        batch.setEvidenceSnapshot(codec.snapshot(document));
        require(batch.getEvidenceSnapshot().length() <= MAX_DOCUMENT, "总投料完整正文超过32Mi字符，未耗料");
        evidence.batchDocument(batch.getEvidenceSnapshot());
        batch.setEvidenceFingerprint(codec.fingerprint(VERSION, batch.getEvidenceSnapshot()));
        require(save(batch), "总投料永久主账必要写失败");
        require(outputs.insertFrozenRows(rowsJson(outputRows.stream().map(ledgerFacts::outputFacts).toList(), batch),
                tenant.toString(), factory, batch.getId().toString()) == outputRows.size(), "真实产出全集必要写失败");

        Map<Long, MesWarehouseFeedEntity> before = new HashMap<>();
        eligible.forEach(feed -> before.put(feed.getId(), feed));
        // 每个实际feed只变更一次；归属边绝不再次CAS或认领mutation。
        for (var consumption : consumptionRows) {
            baseMapper.assertFeedBirth(consumption.getFeedId());
            var actual = before.get(consumption.getFeedId());
            require(actual != null, "耗用根没有本次真实锁定来源");
            var update = new LambdaUpdateWrapper<MesWarehouseFeedEntity>().eq(MesWarehouseFeedEntity::getId, actual.getId())
                    .eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                    .eq(MesWarehouseFeedEntity::getTaskCode, task.getCode()).eq(MesWarehouseFeedEntity::getProduceTaskCode, arrange.getProduceTaskCode())
                    .eq(MesWarehouseFeedEntity::getArrangeCode, arrangeCode).eq(MesWarehouseFeedEntity::getFeedNum, consumption.getFeedNum())
                    .eq(MesWarehouseFeedEntity::getMatUnit, consumption.getMatUnit()).eq(MesWarehouseFeedEntity::getIsLock, false);
            if (actual.getUsedNum() == null) {
                update.isNull(MesWarehouseFeedEntity::getUsedNum);
            } else {
                update.eq(MesWarehouseFeedEntity::getUsedNum, consumption.getUsedBeforeNum());
            }
            require(feeds.update(null, update.set(MesWarehouseFeedEntity::getUsedNum, consumption.getUsedAfterNum())) == 1,
                    "总投料实际CAS必要写失败");
            var savedFeed = feeds.selectById(actual.getId());
            require(savedFeed != null && savedFeed.getUsedNum() != null
                    && savedFeed.getUsedNum().compareTo(consumption.getUsedAfterNum()) == 0, "实际feed字段精度不足，禁止舍入");
        }
        if (!consumptionRows.isEmpty()) {
            require(consumptions.insertFrozenRows(rowsJson(consumptionRows.stream().map(ledgerFacts::consumptionFacts).toList(), batch),
                    tenant.toString(), factory, batch.getId().toString()) == consumptionRows.size(), "总耗用根全集必要写失败");
        }
        if (!shareRows.isEmpty()) {
            require(shares.insertFrozenRows(rowsJson(shareRows.stream().map(ledgerFacts::shareFacts).toList(), batch),
                    tenant.toString(), factory, batch.getId().toString()) == shareRows.size(), "逐产出份额全集必要写失败");
        }
        var saved = original(requestId);
        require(saved != null && Objects.equals(saved.getEvidenceSnapshot(), batch.getEvidenceSnapshot()), "总投料原正文回读不一致");
        return saved;
    }

    @Override
    public MesProductionFeedBatchEntity original(String requestId) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(Boolean.TRUE.equals(baseMapper.ready()), "总投料目录不完整，不能把查不到当未耗料");
        var batch = baseMapper.permanentRequest(tenant, requestId);
        if (batch == null) {
            return null;
        }
        require(Objects.equals(batch.getFactoryCode(), factory) && Objects.equals(batch.getActorId(), SecurityUtils.getUser().getId()),
                "原生产号不是当前工厂本人，禁止复用或跨厂读取");
        ledgerFacts.verify(batch, outputs.permanentRows(tenant, factory, batch.getId()),
                consumptions.permanentRows(tenant, factory, batch.getId()), shares.permanentRows(tenant, factory, batch.getId()));
        return batch;
    }

    /**
     * 实际计量读取用于本次原单位验证，不读取当前数据解释永久历史。
     */
    private MdmMaterialMeasureDTO measure(String code) {
        var response = materials.productionMeasure(code);
        require(response != null && response.isOk() && response.getData() != null, "实际投料计量不可核定");
        return response.getData();
    }

    private List<MesProductionFeedConsumptionEntity> consumptionRows(MesProductionFeedAllocationPlanner.Plan plan,
                                                                     MesProductionFeedBatchEntity batch) {
        var rows = new ArrayList<MesProductionFeedConsumptionEntity>();
        var feedIds = new HashSet<Long>();
        for (var item : plan.consumptions()) {
            require(feedIds.add(item.feedId()), "同一feed出现多个耗用根，须明确原需求而非重复变动");
            var row = new MesProductionFeedConsumptionEntity();
            row.setId(IdWorker.getId());
            row.setBatchId(batch.getId());
            row.setFeedId(item.feedId());
            row.setRequirementMatCode(item.requirementMatCode());
            row.setRequirementUnit(item.requirementUnit());
            row.setMatCode(item.matCode());
            row.setMatUnit(item.matUnit());
            row.setConsumeNum(item.consumeQuantity());
            row.setRequirementNum(item.requirementQuantity());
            row.setSubstituteRatio(item.substituteRatio());
            row.setSubstituteFlag(item.substitute());
            row.setFeedNum(item.feedQuantity());
            row.setUsedBeforeNum(item.usedBefore());
            row.setUsedAfterNum(item.usedAfter());
            row.setMaterialId(item.materialId());
            row.setMaterialUpdatedAt(item.materialUpdatedAt());
            stamp(row, batch.getTenantId(), batch.getFactoryCode(), batch.getActorId());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(row -> row.getId().toString()));
        return rows;
    }

    private List<MesProductionFeedShareEntity> shareRows(MesProductionFeedAllocationPlanner.Plan plan, MesProductionFeedBatchEntity batch,
                                                         List<MesProductionFeedOutputEntity> outputRows,
                                                         List<MesProductionFeedConsumptionEntity> consumptionRows) {
        Map<String, MesProductionFeedOutputEntity> outputIndex = new HashMap<>();
        outputRows.forEach(row -> outputIndex.put(row.getOutputKey(), row));
        Map<Long, MesProductionFeedConsumptionEntity> consumptionIndex = new HashMap<>();
        consumptionRows.forEach(row -> consumptionIndex.put(row.getFeedId(), row));
        var result = new ArrayList<MesProductionFeedShareEntity>();
        for (var item : plan.attributions()) {
            var output = outputIndex.get(item.outputKey());
            var consumption = consumptionIndex.get(item.feedId());
            require(output != null && consumption != null && output.getOutputKind().equals(item.kind().name())
                            && consumption.getRequirementMatCode().equals(item.requirementMatCode())
                            && consumption.getRequirementUnit().equals(item.requirementUnit())
                            && consumption.getMatCode().equals(item.matCode()) && consumption.getMatUnit().equals(item.matUnit()),
                    "归属边不能按首条投料或物料猜根");
            var row = new MesProductionFeedShareEntity();
            row.setId(IdWorker.getId());
            row.setBatchId(batch.getId());
            row.setOutputId(output.getId());
            row.setConsumptionId(consumption.getId());
            row.setConsumeNum(item.consumeQuantity());
            row.setRequirementNum(item.requirementQuantity());
            stamp(row, batch.getTenantId(), batch.getFactoryCode(), batch.getActorId());
            result.add(row);
        }
        result.sort(Comparator.comparing(row -> row.getId().toString()));
        return result;
    }

    /**
     * 一次完整集合提交，不为50000份额展开大量绑定参数或分事务。
     */
    private String rowsJson(List<Map<String, Object>> facts, MesProductionFeedBatchEntity batch) {
        return codec.snapshot(facts.stream().map(row -> {
            var result = new LinkedHashMap<>(row);
            result.put("tenantId", text(batch.getTenantId()));
            result.put("factoryCode", batch.getFactoryCode());
            result.put("createBy", text(batch.getActorId()));
            return result;
        }).toList());
    }

    private String text(Long value) {
        return value == null ? null : value.toString();
    }

    /**
     * 业务实体审计由实际本人设置；数据库事务技术字段不从应用传入。
     */
    private void stamp(com.kitex.common.core.entity.BaseEntity<?> entity, Long tenant, String factory, Long actor) {
        entity.setTenantId(tenant);
        entity.setCreateBy(actor.toString());
        entity.setDelFlag("0");
        if (entity instanceof MesProductionFeedBatchEntity row) {
            row.setFactoryCode(factory);
        } else if (entity instanceof MesProductionFeedOutputEntity row) {
            row.setFactoryCode(factory);
        } else if (entity instanceof MesProductionFeedConsumptionEntity row) {
            row.setFactoryCode(factory);
        } else if (entity instanceof MesProductionFeedShareEntity row) {
            row.setFactoryCode(factory);
        }
    }
}
