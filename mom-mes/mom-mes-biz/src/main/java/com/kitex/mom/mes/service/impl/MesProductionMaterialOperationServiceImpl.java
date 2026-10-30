package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesProductionMaterialAllocationMapper;
import com.kitex.mom.mes.mapper.MesProductionMaterialOperationMapper;
import com.kitex.mom.mes.mapper.MesProductionMaterialReversalMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.service.MesProductionMaterialOperationService;
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
 * 新不良投料耗用：原量一次分配到真实feed，独立于旧GOOD报告的冲销路径。
 * 投料时已从线边库存领出，此处只增加实际used_num，绝不再次出库。
 */
@Service
@RequiredArgsConstructor
public class MesProductionMaterialOperationServiceImpl
        extends ServiceImpl<MesProductionMaterialOperationMapper, MesProductionMaterialOperationEntity>
        implements MesProductionMaterialOperationService {
    public static final String VERSION = "MES_NG_FEED_CONSUMPTION_V1";
    private final MesProductionMaterialAllocationMapper allocations;
    private final MesProductionMaterialReversalMapper reversalMapper;
    private final MesWarehouseFeedMapper feeds;
    private final RemoteMaterialService materials;
    private final MesMaterialConsumptionModeResolver consumptionModes;
    private final MesStartMaterialBindingReader materialBindings;
    private final MesProductionNgSourceCodec sources;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;
    private final MesProductionFeedAllocationPlanner feedPlanner;

    @Override
    public String mode(String requested) {
        String result = requested == null || requested.isEmpty() ? "NONE" : requested;
        require(Set.of("NONE", "FEED").contains(result), "不良耗料方式仅接受NONE或FEED，不能猜测选择");
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordFeed(MesTaskReportUnqualifiedEntity source, MesTaskArrangeEntity arrange, MesTaskEntity task) {
        String factory = access.require("write");
        require(Boolean.TRUE.equals(baseMapper.ready()), "生产实际投料耗用账未就绪，请先执行V2第257节");
        require(Boolean.TRUE.equals(reversalMapper.ready()), "投料耗用的受控恢复保护未就绪，请先执行V2第258节");
        require(sources.verify(source) && Objects.equals(source.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(source.getFactoryCode(), factory)
                && Objects.equals(source.getCapturedActorId(), SecurityUtils.getUser().getId())
                && Objects.equals(source.getArrangeCode(), arrange.getCode())
                && Objects.equals(source.getTaskCode(), task.getCode())
                && Objects.equals(task.getTenantId(), source.getTenantId())
                && Objects.equals(task.getFactoryCode(), factory), "耗用只能归属本次实际不良生产来源");
        require(original(source) == null, "原不良已有耗用账，禁止再次扣料");
        var route = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(),
                arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
        var requirements = Objects.requireNonNullElse(route.getMdmProcessProcedureMatList(),
                List.<ProcessProcedureDTO.ProcessProcedureMatDTO>of());
        require(requirements.size() <= 1000, "工序耗料需求超过1000条，不能截断登记");
        require(consumptionModes.resolve(arrange) == MesMaterialConsumptionModeResolver.Mode.FEED,
                "原派工固定为倒冲消耗，不能选择投料耗用；不良倒冲须使用独立原库存分配协议");
        var feedQuery = new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getTenantId, source.getTenantId())
                .eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                .eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode())
                .eq(MesWarehouseFeedEntity::getIsLock, Boolean.FALSE)
                .and(w -> w.isNull(MesWarehouseFeedEntity::getMatValidEndDate)
                        .or().ge(MesWarehouseFeedEntity::getMatValidEndDate, LocalDate.now()))
                .orderByAsc(MesWarehouseFeedEntity::getId);
        materialBindings.constrain(arrange, feedQuery);
        var eligible = requirements.isEmpty() ? List.<MesWarehouseFeedEntity>of() : feeds.selectList(feedQuery.last("LIMIT 5001 FOR UPDATE NOWAIT"));
        require(eligible.size() <= 5000, "实际投料超过5000条，不能截断后记耗用");
        String outputKey = source.getId().toString();
        var plan = feedPlanner.plan(new MesProductionFeedAllocationPlanner.Scope(source.getTenantId(), factory,
                        task.getCode(), arrange.getProduceTaskCode(), arrange.getCode(), source.getReportUnit()),
                List.of(new MesProductionFeedAllocationPlanner.OutputDemand(outputKey,
                        MesProductionFeedAllocationPlanner.OutputKind.NG, source.getUnqualifiedNum(), source.getReportUnit())),
                requirements, task.getBomSnapshot(), eligible, this::readMeasure);
        require(plan.processedQuantity().compareTo(source.getUnqualifiedNum()) == 0
                        && plan.attributions().size() == plan.consumptions().size()
                        && plan.attributions().stream().allMatch(edge -> outputKey.equals(edge.outputKey())
                        && edge.kind() == MesProductionFeedAllocationPlanner.OutputKind.NG),
                "原NG路径只能保存本次单NG全部份额，不能借原V1接组合总耗量");
        var lines = new ArrayList<MesProductionMaterialAllocationEntity>();
        for (var consumption : plan.consumptions()) {
            var line = new MesProductionMaterialAllocationEntity();
            line.setId(IdWorker.getId());
            line.setTenantId(source.getTenantId());
            line.setSourceUnqualifiedReportId(source.getId());
            line.setFeedId(consumption.feedId());
            line.setRequirementMatCode(consumption.requirementMatCode());
            line.setRequirementUnit(consumption.requirementUnit());
            line.setMatCode(consumption.matCode());
            line.setMatUnit(consumption.matUnit());
            line.setConsumeNum(consumption.consumeQuantity());
            line.setRequirementNum(consumption.requirementQuantity());
            line.setSubstituteRatio(consumption.substituteRatio());
            line.setSubstituteFlag(consumption.substitute());
            line.setFeedNum(consumption.feedQuantity());
            line.setUsedBeforeNum(consumption.usedBefore());
            line.setUsedAfterNum(consumption.usedAfter());
            line.setMaterialId(consumption.materialId());
            line.setMaterialUpdatedAt(consumption.materialUpdatedAt());
            line.setFactoryCode(factory);
            lines.add(line);
        }
        var operation = new MesProductionMaterialOperationEntity();
        operation.setId(IdWorker.getId());
        operation.setTenantId(source.getTenantId());
        operation.setFactoryCode(factory);
        operation.setRequestId(source.getRequestId());
        operation.setSourceUnqualifiedReportId(source.getId());
        operation.setTaskCode(task.getCode());
        operation.setProduceTaskCode(arrange.getProduceTaskCode());
        operation.setArrangeCode(arrange.getCode());
        operation.setRouteCode(arrange.getRouteCode());
        operation.setProcessedQuantity(source.getUnqualifiedNum());
        operation.setProductionUnit(source.getReportUnit());
        operation.setAccountingMode("FEED");
        operation.setStatus("MATERIAL_RECORDED");
        operation.setActorId(source.getCapturedActorId());
        operation.setSourceFingerprint(source.getSourceFingerprint());
        operation.setRouteSnapshot(task.getProcessRouteSnapshot());
        operation.setBomSnapshot(task.getBomSnapshot());
        operation.setCommandVersion(VERSION);
        var document = document(operation);
        document.put("allocations", lines.stream().map(this::lineFacts).sorted(Comparator.comparing(m -> (String) m.get("id"))).toList());
        operation.setCommandSnapshot(codec.snapshot(document));
        require(operation.getCommandSnapshot().length() <= 8 * 1024 * 1024, "完整耗用正文超过8Mi字符，未扣料");
        operation.setCommandFingerprint(codec.fingerprint(VERSION, operation.getCommandSnapshot()));
        stamp(operation, source);
        require(save(operation), "实际生产物料主账未保存，整笔回滚");
        for (var line : lines) {
            var cas = new LambdaUpdateWrapper<MesWarehouseFeedEntity>().eq(MesWarehouseFeedEntity::getId, line.getFeedId())
                    .eq(MesWarehouseFeedEntity::getTenantId, source.getTenantId()).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                    .eq(MesWarehouseFeedEntity::getFeedNum, line.getFeedNum())
                    .and(w -> {
                        w.eq(MesWarehouseFeedEntity::getUsedNum, line.getUsedBeforeNum());
                        if (line.getUsedBeforeNum().signum() == 0) w.or().isNull(MesWarehouseFeedEntity::getUsedNum);
                    });
            require(feeds.update(null, cas.set(MesWarehouseFeedEntity::getUsedNum, line.getUsedAfterNum())) == 1,
                    "实际投料耗用更新失败，整笔回滚");
            var persisted = feeds.selectById(line.getFeedId());
            require(persisted != null && persisted.getUsedNum() != null && persisted.getUsedNum().compareTo(line.getUsedAfterNum()) == 0,
                    "实际投料字段精度不足，禁止静默舍入");
            line.setOperationId(operation.getId());
            stamp(line, source);
            require(allocations.insert(line) == 1, "实际耗用分配未保存，整笔回滚");
        }
        var saved = original(source);
        require(saved != null && Objects.equals(saved.getCommandSnapshot(), operation.getCommandSnapshot()), "完整耗用回执回读不一致");
    }

    @Override
    public MesProductionMaterialOperationEntity original(MesTaskReportUnqualifiedEntity source) {
        String factory = access.require("read");
        require(source != null && Objects.equals(source.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(source.getFactoryCode(), factory), "只能读取当前租户工厂原耗用账");
        require(Boolean.TRUE.equals(baseMapper.ready()), "生产实际投料耗用账未就绪，不能将查不到当成未耗料");
        var result = getOne(new LambdaQueryWrapper<MesProductionMaterialOperationEntity>()
                .eq(MesProductionMaterialOperationEntity::getTenantId, source.getTenantId())
                .eq(MesProductionMaterialOperationEntity::getFactoryCode, factory)
                .eq(MesProductionMaterialOperationEntity::getSourceUnqualifiedReportId, source.getId()));
        if (result == null) return null;
        require(VERSION.equals(result.getCommandVersion()) && "MATERIAL_RECORDED".equals(result.getStatus())
                        && Objects.equals(result.getRequestId(), source.getRequestId())
                        && Objects.equals(result.getSourceFingerprint(), source.getSourceFingerprint())
                        && Objects.equals(result.getCommandFingerprint(), codec.fingerprint(VERSION, result.getCommandSnapshot())),
                "原耗用来源/版本/SHA不一致，不能猜测成功");
        var rows = allocations.selectList(new LambdaQueryWrapper<MesProductionMaterialAllocationEntity>()
                .eq(MesProductionMaterialAllocationEntity::getTenantId, source.getTenantId())
                .eq(MesProductionMaterialAllocationEntity::getFactoryCode, factory)
                .eq(MesProductionMaterialAllocationEntity::getOperationId, result.getId()).orderByAsc(MesProductionMaterialAllocationEntity::getId));
        var expected = document(result);
        expected.put("allocations", rows.stream().map(this::lineFacts).sorted(Comparator.comparing(m -> (String) m.get("id"))).toList());
        var actual = codec.objectDocument(result.getCommandSnapshot(), 8 * 1024 * 1024);
        require(actual.equals(codec.objectDocument(codec.snapshot(expected), 8 * 1024 * 1024)), "原耗用分配全集或数量回读不一致");
        return result;
    }

    /**
     * 原计量观察由真实实名Feign读取；单位/组织/版本由共用分配内核逐来源核验。
     */
    private MdmMaterialMeasureDTO readMeasure(String matCode) {
        var response = materials.productionMeasure(matCode);
        require(response != null && response.isOk() && response.getData() != null, "无法取得实际投料物料计量");
        return response.getData();
    }

    /**
     * 固定字段不反射实体；后续新增字段不会改变原V1语义。
     */
    private Map<String, Object> document(MesProductionMaterialOperationEntity op) {
        var result = new TreeMap<String, Object>();
        result.put("version", VERSION);
        result.put("operationId", op.getId().toString());
        result.put("requestId", op.getRequestId());
        result.put("tenantId", op.getTenantId().toString());
        result.put("factoryCode", op.getFactoryCode());
        result.put("actorId", op.getActorId().toString());
        result.put("unqualifiedReportId", op.getSourceUnqualifiedReportId().toString());
        result.put("sourceFingerprint", op.getSourceFingerprint());
        result.put("taskCode", op.getTaskCode());
        result.put("produceTaskCode", op.getProduceTaskCode());
        result.put("arrangeCode", op.getArrangeCode());
        result.put("routeCode", op.getRouteCode());
        result.put("processedQuantity", number(op.getProcessedQuantity()));
        result.put("productionUnit", op.getProductionUnit());
        result.put("accountingMode", op.getAccountingMode());
        result.put("routeSnapshot", op.getRouteSnapshot());
        result.put("bomSnapshot", op.getBomSnapshot());
        return result;
    }

    private Map<String, Object> lineFacts(MesProductionMaterialAllocationEntity line) {
        var result = new TreeMap<String, Object>();
        result.put("id", line.getId().toString());
        result.put("feedId", line.getFeedId().toString());
        result.put("requirementMatCode", line.getRequirementMatCode());
        result.put("requirementUnit", line.getRequirementUnit());
        result.put("matCode", line.getMatCode());
        result.put("matUnit", line.getMatUnit());
        result.put("consumeNum", number(line.getConsumeNum()));
        result.put("requirementNum", number(line.getRequirementNum()));
        result.put("substituteRatio", number(line.getSubstituteRatio()));
        result.put("substituteFlag", line.getSubstituteFlag().toString());
        result.put("feedNum", number(line.getFeedNum()));
        result.put("usedBeforeNum", number(line.getUsedBeforeNum()));
        result.put("usedAfterNum", number(line.getUsedAfterNum()));
        result.put("materialId", line.getMaterialId().toString());
        result.put("materialUpdatedAt", line.getMaterialUpdatedAt());
        return result;
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void stamp(com.kitex.common.core.entity.BaseEntity<?> row, MesTaskReportUnqualifiedEntity source) {
        row.setCreateBy(source.getWorkerCode());
        row.setDelFlag("0");
    }
}
