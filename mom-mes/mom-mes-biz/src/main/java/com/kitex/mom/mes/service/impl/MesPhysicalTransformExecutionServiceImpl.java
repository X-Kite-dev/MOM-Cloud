package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformExecutionDTO;
import com.kitex.mom.mes.entity.MesPhysicalPositionSurveyEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformAllocationEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformCommandEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformExecutionEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformInputEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformOutputEntity;
import com.kitex.mom.mes.entity.MesPhysicalTransformSourceHoldEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.mapper.MesPhysicalPositionConsumptionMapper;
import com.kitex.mom.mes.mapper.MesPhysicalPositionSurveyMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformAllocationMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformCommandMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformExecutionMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformInputMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformOutputMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceClaimMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceHoldMapper;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceLockMapper;
import com.kitex.mom.mes.service.MesPhysicalTransformExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 实物转换正式执行内核。
 * 同一事务内完成原号恢复、锁内来源重核、精确扣减、GOOD库存出生、SCRAP/MISSING事实、
 * 多来源分配、位置消费和命令/占用终态；不调用生产报工，因此不会重复累计工单产量。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalTransformExecutionServiceImpl
        extends ServiceImpl<MesPhysicalTransformExecutionMapper, MesPhysicalTransformExecutionEntity>
        implements MesPhysicalTransformExecutionService {

    private final MesPhysicalTransformCommandMapper commands;
    private final MesPhysicalTransformSourceHoldMapper holds;
    private final MesPhysicalTransformSourceClaimMapper claims;
    private final MesPhysicalTransformInputMapper inputs;
    private final MesPhysicalTransformOutputMapper outputs;
    private final MesPhysicalTransformAllocationMapper allocations;
    private final MesPhysicalPositionConsumptionMapper positionConsumptions;
    private final MesPhysicalPositionSurveyMapper surveys;
    private final MesPhysicalTransformSourceLockMapper sourceLocks;
    private final MesPhysicalTransformSourceFreezeService sourceFreeze;
    private final MesPhysicalTransformSourceScopeLock scope;
    private final MesPhysicalTransformSourceHoldCodec holdCodec;
    private final MesPhysicalTransformPlanner planner;
    private final MesPhysicalTransformCodec codec;
    private final MesPhysicalTransformExecutionCodec executionCodec;
    private final MesActualFactoryAccessService factoryAccess;
    private final PlatformTransactionManager transactionManager;

    @Override
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    public MesPhysicalTransformExecutionDTO.Receipt apply(String requestId, String rawJson) {
        codec.code(requestId, "转换永久请求号", 100);
        var apply = executionCodec.apply(rawJson);
        Identity owner = identity();
        assertExpected(apply, owner);
        String applyFingerprint = codec.fingerprint(MesPhysicalTransformExecutionCodec.VERSION, rawJson);
        return independent(() -> execute(requestId, rawJson, applyFingerprint, apply, owner));
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public MesPhysicalTransformExecutionDTO.Receipt receipt(String requestId) {
        codec.code(requestId, "转换永久请求号", 100);
        Identity owner = identity();
        return latestRead(() -> {
            if (!Boolean.TRUE.equals(baseMapper.available())) return empty(requestId, owner, "UNAVAILABLE");
            var command = commands.permanent(owner.tenantId(), requestId);
            if (command == null) return empty(requestId, owner, "ABSENT");
            assertCommand(command, owner);
            var execution = baseMapper.permanent(owner.tenantId(), command.getId());
            return execution == null ? empty(requestId, owner, "ABSENT") : receiptOf(command, execution, owner);
        });
    }

    private MesPhysicalTransformExecutionDTO.Receipt execute(String requestId, String rawJson, String applyFingerprint,
                                                              MesPhysicalTransformExecutionDTO.Apply apply, Identity owner) {
        requireReady();
        requestLock(owner, requestId);
        var command = commands.locked(owner.tenantId(), requestId);
        require(command != null, "永久原命令不存在；不根据ABSENT更换原号执行");
        assertCommand(command, owner);
        var prior = baseMapper.permanent(owner.tenantId(), command.getId());
        if (prior != null) {
            require(rawJson.equals(prior.getApplySnapshot()) && applyFingerprint.equals(prior.getApplyFingerprint()),
                    "原号已有另一正式执行确认正文，不能覆盖永久结果");
            return receiptOf(command, prior, owner);
        }
        require("REGISTERED".equals(command.getStatus()), "只有已登记且未封存/执行的原命令可以正式转换");
        require(apply.getExpectedCommandFingerprint().equals(command.getRequestFingerprint()), "执行确认的命令SHA与永久原文不一致");
        var hold = holds.locked(owner.tenantId(), command.getId());
        assertHold(hold, command, owner);
        require("SOURCE_HELD".equals(hold.getStatus()) && apply.getExpectedHoldFingerprint().equals(hold.getFrozenFingerprint()),
                "正式执行要求同一原命令的活动来源占用及精确原SHA");

        scope.acquire(owner.tenantId(), owner.factoryCode());
        requireReady();
        command = commands.locked(owner.tenantId(), requestId);
        assertCommand(command, owner);
        hold = holds.locked(owner.tenantId(), command.getId());
        assertHold(hold, command, owner);

        var body = codec.command(command.getCommandSnapshot());
        require(body instanceof MesPhysicalTransformCommandDTO.BodyV2, "正式执行只接受明确V2来源算法的永久原命令");
        var bundle = sourceFreeze.lockAndRead(body.getInputs().stream().map(MesPhysicalTransformDTO.Input::getStockId).toList());
        var fresh = holdCodec.freeze(command.getId().toString(), requestId, command.getRequestFingerprint(),
                (MesPhysicalTransformCommandDTO.BodyV2) body, bundle);
        require(fresh.snapshot().equals(hold.getFrozenSnapshot()) && fresh.fingerprint().equals(hold.getFrozenFingerprint()),
                "占用后来源、工程、计量或完整计算已变化，禁止用旧占用执行");
        var policy = codec.policy(bundle.getSources().get(0).getPolicyConfiguration());
        var plan = plan(body, bundle.getSources().get(0).getPolicyConfiguration());
        var calculation = planner.calculate(plan, policy, bundle.getSources().stream()
                .map(com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO.Entry::getSource).toList());
        String calculationSnapshot = codec.snapshot(calculation);
        String calculationFingerprint = codec.fingerprint(MesPhysicalTransformExecutionCodec.CALCULATION_VERSION, calculationSnapshot);

        MesPhysicalPositionSurveyEntity survey = validateSurvey(policy, body, bundle, apply, owner);
        Map<Long, MesWarehouseLiveStockEntity> stockRows = new TreeMap<>();
        for (var input : body.getInputs()) {
            Long stockId = codec.id(input.getStockId());
            BigDecimal requestedQuantity = codec.quantity(input.getQuantity(), false, "投入量");
            var stock = sourceLocks.stock(owner.tenantId(), owner.factoryCode(), stockId);
            require(stock != null && stock.getNum() != null && stock.getNum().compareTo(requestedQuantity) >= 0,
                    "精确投入库存取锁后不足或已改变");
            stockRows.put(stockId, stock);
        }
        validateCommonStock(stockRows.values(), policy);
        lockOutputIdentifiers(owner, body);

        LocalDateTime now = LocalDateTime.now();
        Long executionId = IdWorker.getId();
        var prepared = prepareRows(executionId, requestId, command, hold, survey, body, bundle, calculation,
                rawJson, applyFingerprint, calculationSnapshot, calculationFingerprint, owner, stockRows, now);
        var execution = prepared.execution();
        require(baseMapper.insert(execution) == 1, "正式转换执行主账必要写未恰好一行");

        // 先把原活动身份变为本执行永久CONSUMED，随后同一scope事务才可写被保护的原库存。
        // 转换子账先于库存/流水落账，使每一笔库存出生都能即时反查明确的转换输出，而非只依赖延迟外键。
        require(claims.consume(hold.getId(), owner.tenantId(), owner.factoryCode()) == hold.getIdentityCount(),
                "来源占用身份未完整转为已消费，整次转换回滚");
        require(inputs.insertRows(codec.snapshot(prepared.inputRows())) == prepared.inputRows().size(),
                "转换投入永久行未完整落账，整次转换回滚");
        require(outputs.insertRows(codec.snapshot(prepared.outputRows())) == prepared.outputRows().size(),
                "转换输出永久行未完整落账，整次转换回滚");
        require(allocations.insertRows(codec.snapshot(prepared.allocationRows())) == prepared.allocationRows().size(),
                "转换多来源分配边未完整落账，整次转换回滚");
        if (!prepared.positionRows().isEmpty()) {
            require(positionConsumptions.insertRows(codec.snapshot(prepared.positionRows())) == prepared.positionRows().size(),
                    "母件位置消费结果未完整落账，整次转换回滚");
        }
        require(baseMapper.consumeStocks(codec.snapshot(prepared.stockUpdates())) == prepared.inputEntities().size(),
                "精确来源库存CAS未完整成功，整次转换回滚");
        if (!prepared.outputStocks().isEmpty()) {
            require(baseMapper.insertOutputStocks(codec.snapshot(prepared.outputStocks())) == prepared.outputStocks().size(),
                    "GOOD输出库存未完整落账，整次转换回滚");
        }
        require(baseMapper.insertFlows(codec.snapshot(prepared.flowRows())) == prepared.flowRows().size(),
                "转换OUT/IN库存流水未完整落账，整次转换回滚");
        require(holds.apply(hold.getId(), owner.tenantId(), owner.factoryCode(), owner.actorId(), owner.actorCode(),
                hold.getFrozenFingerprint()) == 1, "来源占用未收口为正式执行终态");
        require(commands.apply(command.getId(), owner.tenantId(), owner.factoryCode(), owner.actorId(), owner.actorCode(),
                command.getRequestFingerprint()) == 1, "永久原命令未收口为正式执行终态");
        var stored = baseMapper.permanent(owner.tenantId(), command.getId());
        require(stored != null && executionId.equals(stored.getId()) && "APPLIED".equals(stored.getStatus()),
                "正式转换执行结果回读不完整");
        return receiptOf(command, stored, owner);
    }

    private MesPhysicalTransformDTO.Plan plan(MesPhysicalTransformCommandDTO.Body body, String policy) {
        var plan = new MesPhysicalTransformDTO.Plan();
        plan.setExpectedTenantId(body.getExpectedTenantId());
        plan.setExpectedFactoryCode(body.getExpectedFactoryCode());
        plan.setExpectedActorUserId(body.getExpectedActorUserId());
        plan.setPolicyDraft(policy);
        plan.setInputs(body.getInputs());
        plan.setOutputs(body.getOutputs());
        plan.setAllocations(body.getAllocations());
        plan.setReason(body.getReason());
        return plan;
    }

    private MesPhysicalPositionSurveyEntity validateSurvey(MesPhysicalTransformDTO.Policy policy,
            MesPhysicalTransformCommandDTO.Body body,
            com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO bundle,
            MesPhysicalTransformExecutionDTO.Apply apply, Identity owner) {
        if (!"DEPANEL".equals(policy.getOperationType())) {
            require(apply.getPositionSurveyId().isEmpty() && apply.getPositionSurveyFingerprint().isEmpty(),
                    "非拆板转换不能附带位置原账，避免把不相关观察当执行依据");
            return null;
        }
        require(!apply.getPositionSurveyId().isEmpty() && !apply.getPositionSurveyFingerprint().isEmpty(),
                "拆板正式执行必须明确已确认位置原账及原采集SHA");
        var survey = surveys.lockedById(owner.tenantId(), owner.factoryCode(), codec.id(apply.getPositionSurveyId()));
        require(survey != null && "CONFIRMED".equals(survey.getStatus())
                && apply.getPositionSurveyFingerprint().equals(survey.getSurveyFingerprint())
                && owner.actorId().equals(survey.getActorId()) && owner.actorCode().equals(survey.getActorCode()),
                "位置原账不存在、未异人确认、原SHA不一致或不是原执行本人采集");
        Set<String> standards = bundle.getSources().stream().map(e -> e.getStandardFingerprint()).collect(Collectors.toSet());
        require(standards.size() == 1 && standards.contains(survey.getStandardFingerprint()),
                "位置原账正式工程与本次来源占用工程不一致");
        JsonNode ledger = codec.objectDocument(survey.getLedgerSnapshot(), 64 * 1024 * 1024);
        Map<String, JsonNode> slots = new HashMap<>();
        for (var node : ledger.path("positions")) slots.put(node.path("position_code").textValue(), node);
        Map<String, Map<String, BigDecimal>> positionAllocations = new HashMap<>();
        for (var node : ledger.path("allocations")) positionAllocations
                .computeIfAbsent(node.path("position_code").textValue(), ignored -> new HashMap<>())
                .put(node.path("stock_id").asText(), codec.quantity(node.path("equivalent_quantity").asText(), false, "位置原分配"));
        Map<String, Map<String, BigDecimal>> commandAllocations = new HashMap<>();
        for (var edge : body.getAllocations()) commandAllocations
                .computeIfAbsent(edge.getOutputKey(), ignored -> new HashMap<>())
                .put(edge.getStockId(), codec.quantity(edge.getQuantity(), false, "执行分配"));
        for (var output : body.getOutputs()) {
            JsonNode slot = slots.get(output.getPositionCode());
            require(slot != null, "执行输出位置不属于原确认完整布局：" + output.getPositionCode());
            if ("MISSING".equals(output.getDisposition())) {
                require("MISSING".equals(slot.path("presence").textValue())
                        && !commandAllocations.containsKey(output.getOutputKey()), "缺位输出必须与原确认缺位一致且没有实物分配");
            } else {
                require("STOCKED".equals(slot.path("presence").textValue()), "合格/报废输出位置必须是原确认STOCKED位置");
                require(Objects.equals(positionAllocations.get(output.getPositionCode()), commandAllocations.get(output.getOutputKey())),
                        "逐位置原库存分配与正式执行分配不一致");
            }
        }
        return survey;
    }

    private void validateCommonStock(java.util.Collection<MesWarehouseLiveStockEntity> rows,
                                     MesPhysicalTransformDTO.Policy policy) {
        require(!rows.isEmpty(), "转换来源库存为空");
        var first = rows.iterator().next();
        require(rows.stream().allMatch(row -> Objects.equals(row.getMatCode(), first.getMatCode())
                        && Objects.equals(row.getMatName(), first.getMatName()) && Objects.equals(row.getMatSpec(), first.getMatSpec())
                        && Objects.equals(row.getMatUnit(), policy.getInputUnit())
                        && Objects.equals(row.getBillNo(), first.getBillNo())
                        && Objects.equals(row.getProduceTaskCode(), first.getProduceTaskCode())
                        && Objects.equals(row.getRouteCode(), first.getRouteCode())
                        && Objects.equals(row.getWarehouseCode(), first.getWarehouseCode())
                        && Objects.equals(row.getSiteCode(), first.getSiteCode())),
                "多来源必须属于同物料/任务/工序/仓位；跨工单或跨仓位汇合须使用后续明确交付事务，不能猜输出归属");
    }

    private void lockOutputIdentifiers(Identity owner, MesPhysicalTransformCommandDTO.Body body) {
        List<String> identifiers = body.getOutputs().stream().filter(o -> !"MISSING".equals(o.getDisposition()))
                .map(MesPhysicalTransformDTO.Output::getIdentifier).sorted().toList();
        for (String identifier : identifiers) {
            require(Boolean.TRUE.equals(baseMapper.tryIdentifierLock(owner.tenantId(), owner.factoryCode(), identifier)),
                    "输出标识正在被另一业务处理，请保留原号稍后核账/重试：" + identifier);
            require(!Boolean.TRUE.equals(baseMapper.identifierExists(owner.tenantId(), owner.factoryCode(), identifier)),
                    "输出标识已存在于库存、报工、别名、动态绑定、载具、包装或历史转换：" + identifier);
        }
    }

    private PreparedRows prepareRows(Long executionId, String requestId, MesPhysicalTransformCommandEntity command,
            MesPhysicalTransformSourceHoldEntity hold, MesPhysicalPositionSurveyEntity survey,
            MesPhysicalTransformCommandDTO.Body body,
            com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO bundle,
            MesPhysicalTransformDTO.Calculation calculation, String applySnapshot, String applyFingerprint,
            String calculationSnapshot, String calculationFingerprint, Identity owner,
            Map<Long, MesWarehouseLiveStockEntity> stockRows, LocalDateTime now) {
        var sourceById = bundle.getSources().stream().collect(Collectors.toMap(e -> e.getStockId(), Function.identity()));
        var inputIds = new HashMap<String, Long>();
        var outputIds = new HashMap<String, Long>();
        var inputEntities = new ArrayList<MesPhysicalTransformInputEntity>();
        var outputEntities = new ArrayList<MesPhysicalTransformOutputEntity>();
        var allocationEntities = new ArrayList<MesPhysicalTransformAllocationEntity>();
        var stockUpdates = new ArrayList<Map<String, Object>>();
        var outputStocks = new ArrayList<Map<String, Object>>();
        var flowRows = new ArrayList<Map<String, Object>>();
        var inputRows = new ArrayList<Map<String, Object>>();
        var outputRows = new ArrayList<Map<String, Object>>();
        var allocationRows = new ArrayList<Map<String, Object>>();
        var positionRows = new ArrayList<Map<String, Object>>();

        for (var input : body.getInputs()) {
            Long stockId = codec.id(input.getStockId());
            var stock = stockRows.get(stockId);
            BigDecimal quantity = codec.quantity(input.getQuantity(), false, "投入量");
            BigDecimal after = stock.getNum().subtract(quantity);
            Long inputId = IdWorker.getId();
            Long flowId = IdWorker.getId();
            inputIds.put(input.getStockId(), inputId);
            var entity = new MesPhysicalTransformInputEntity().setFactoryCode(owner.factoryCode()).setExecutionId(executionId)
                    .setStockId(stockId).setSourceReportId(stock.getSourceReportId()).setMaterialCode(stock.getMatCode())
                    .setUnit(stock.getMatUnit()).setQuantity(quantity).setBeforeQuantity(stock.getNum()).setAfterQuantity(after)
                    .setOutFlowId(flowId).setSourceFingerprint(sourceById.get(input.getStockId()).getSourceFingerprint());
            initialize(entity, inputId, owner, now);
            inputEntities.add(entity);
            stockUpdates.add(map("stock_id", stockId, "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                    "before_quantity", stock.getNum(), "after_quantity", after, "update_by", owner.actorCode(), "update_time", now));
            flowRows.add(flow(flowId, executionId, null, stock, stockId, stock.getSourceReportId(), requestId,
                    quantity, "OUT", "physical_transform_out", stock.getNum(), after, owner, now));
            inputRows.add(inputRow(entity, owner, now));
        }

        var template = stockRows.values().iterator().next();
        for (var output : body.getOutputs()) {
            Long outputId = IdWorker.getId();
            outputIds.put(output.getOutputKey(), outputId);
            boolean good = "GOOD".equals(output.getDisposition());
            BigDecimal quantity = codec.quantity(output.getQuantity(), "MISSING".equals(output.getDisposition()), "输出量");
            Long stockId = good ? IdWorker.getId() : null;
            Long flowId = good ? IdWorker.getId() : null;
            var entity = new MesPhysicalTransformOutputEntity().setFactoryCode(owner.factoryCode()).setExecutionId(executionId)
                    .setOutputKey(output.getOutputKey()).setIdentifier(output.getIdentifier().isEmpty() ? null : output.getIdentifier())
                    .setObjectType(output.getObjectType()).setDisposition(output.getDisposition())
                    .setPositionCode(output.getPositionCode().isEmpty() ? null : output.getPositionCode())
                    .setQuantity(quantity).setResultStockId(stockId).setInFlowId(flowId);
            initialize(entity, outputId, owner, now);
            outputEntities.add(entity);
            outputRows.add(outputRow(entity, owner, now));
            if (good) {
                String batch = "BATCH".equals(output.getObjectType()) ? output.getIdentifier()
                        : Objects.toString(template.getMatBatch(), "");
                outputStocks.add(map("id", stockId, "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                        "output_id", outputId, "bill_no", template.getBillNo(), "produce_task_code", template.getProduceTaskCode(),
                        "route_code", template.getRouteCode(), "route_name", template.getRouteName(), "mat_code", template.getMatCode(),
                        "mat_name", template.getMatName(), "mat_spec", template.getMatSpec(), "mat_batch", batch,
                        "mat_unit", calculation.getOutputUnit(), "mat_barcode", output.getIdentifier(),
                        "warehouse_code", text(template.getWarehouseCode()), "warehouse_name", text(template.getWarehouseName()),
                        "site_code", text(template.getSiteCode()), "site_name", text(template.getSiteName()),
                        "in_time", now, "quantity", quantity, "create_by", owner.actorCode(), "create_time", now));
                var resultStock = new MesWarehouseLiveStockEntity();
                resultStock.setMatCode(template.getMatCode());
                resultStock.setMatName(template.getMatName());
                resultStock.setMatSpec(template.getMatSpec());
                resultStock.setMatBatch(batch);
                resultStock.setMatUnit(calculation.getOutputUnit());
                resultStock.setMatBarcode(output.getIdentifier());
                resultStock.setWarehouseCode(template.getWarehouseCode());
                resultStock.setWarehouseName(template.getWarehouseName());
                resultStock.setSiteCode(template.getSiteCode());
                resultStock.setSiteName(template.getSiteName());
                resultStock.setBillNo(template.getBillNo());
                resultStock.setProduceTaskCode(template.getProduceTaskCode());
                resultStock.setRouteCode(template.getRouteCode());
                resultStock.setRouteName(template.getRouteName());
                flowRows.add(flow(flowId, executionId, outputId, resultStock, stockId, null, requestId,
                        quantity, "IN", "physical_transform_in", BigDecimal.ZERO, quantity, owner, now));
            }
            if (survey != null) {
                positionRows.add(map("id", IdWorker.getId(), "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                        "survey_id", survey.getId(), "position_code", output.getPositionCode(), "execution_id", executionId,
                        "output_id", outputId, "disposition", output.getDisposition(), "output_identifier", output.getIdentifier(),
                        "create_by", owner.actorCode(), "create_time", now));
            }
        }

        for (var edge : body.getAllocations()) {
            var entity = new MesPhysicalTransformAllocationEntity().setFactoryCode(owner.factoryCode()).setExecutionId(executionId)
                    .setInputId(inputIds.get(edge.getStockId())).setOutputId(outputIds.get(edge.getOutputKey()))
                    .setStockId(codec.id(edge.getStockId())).setOutputKey(edge.getOutputKey())
                    .setEquivalentQuantity(codec.quantity(edge.getQuantity(), false, "分配当量"));
            initialize(entity, IdWorker.getId(), owner, now);
            allocationEntities.add(entity);
            allocationRows.add(allocationRow(entity, owner, now));
        }

        var receipt = new TreeMap<String, Object>();
        receipt.put("version", MesPhysicalTransformExecutionCodec.RECEIPT_VERSION);
        receipt.put("executionId", executionId.toString());
        receipt.put("commandId", command.getId().toString());
        receipt.put("holdId", hold.getId().toString());
        receipt.put("positionSurveyId", survey == null ? "" : survey.getId().toString());
        receipt.put("tenantId", owner.tenantId().toString());
        receipt.put("factoryCode", owner.factoryCode());
        receipt.put("actorUserId", owner.actorId().toString());
        receipt.put("actorCode", owner.actorCode());
        receipt.put("operationType", calculation.getOperationType());
        receipt.put("materialCode", template.getMatCode());
        receipt.put("calculationFingerprint", calculationFingerprint);
        receipt.put("inputs", inputRows);
        receipt.put("outputs", outputRows);
        receipt.put("allocations", allocationRows);
        String receiptSnapshot = codec.snapshot(receipt);
        String receiptFingerprint = codec.fingerprint(MesPhysicalTransformExecutionCodec.RECEIPT_VERSION, receiptSnapshot);
        var execution = new MesPhysicalTransformExecutionEntity().setFactoryCode(owner.factoryCode()).setCommandId(command.getId())
                .setHoldId(hold.getId()).setPositionSurveyId(survey == null ? null : survey.getId())
                .setExecutionVersion(MesPhysicalTransformExecutionCodec.EXECUTION_VERSION)
                .setCommandFingerprint(command.getRequestFingerprint()).setHoldFingerprint(hold.getFrozenFingerprint())
                .setPositionSurveyFingerprint(survey == null ? null : survey.getSurveyFingerprint())
                .setOperationType(calculation.getOperationType()).setMaterialCode(template.getMatCode())
                .setInputUnit(calculation.getInputUnit()).setOutputUnit(calculation.getOutputUnit())
                .setInputQuantity(codec.quantity(calculation.getInputQuantity(), false, "总投入量"))
                .setGoodQuantity(codec.quantity(calculation.getGoodQuantity(), true, "合格输出量"))
                .setScrapQuantity(codec.quantity(calculation.getScrapQuantity(), true, "报废输出量"))
                .setCalculationSnapshot(calculationSnapshot).setCalculationFingerprint(calculationFingerprint)
                .setApplySnapshot(applySnapshot).setApplyFingerprint(applyFingerprint)
                .setReceiptSnapshot(receiptSnapshot).setReceiptFingerprint(receiptFingerprint)
                .setActorId(owner.actorId()).setActorCode(owner.actorCode()).setStatus("APPLIED").setAppliedTime(now);
        initialize(execution, executionId, owner, now);
        return new PreparedRows(execution, inputEntities, outputEntities, allocationEntities, stockUpdates,
                outputStocks, flowRows, inputRows, outputRows, allocationRows, positionRows);
    }

    private MesPhysicalTransformExecutionDTO.Receipt receiptOf(MesPhysicalTransformCommandEntity command,
            MesPhysicalTransformExecutionEntity execution, Identity owner) {
        require(Objects.equals(execution.getTenantId(), owner.tenantId()) && Objects.equals(execution.getFactoryCode(), owner.factoryCode())
                && Objects.equals(execution.getCommandId(), command.getId()) && Objects.equals(execution.getCommandFingerprint(), command.getRequestFingerprint())
                && Objects.equals(execution.getActorId(), owner.actorId()) && Objects.equals(execution.getActorCode(), owner.actorCode())
                && "APPLIED".equals(execution.getStatus()) && MesPhysicalTransformExecutionCodec.EXECUTION_VERSION.equals(execution.getExecutionVersion())
                && Objects.equals(execution.getApplyFingerprint(), codec.fingerprint(MesPhysicalTransformExecutionCodec.VERSION, execution.getApplySnapshot()))
                && Objects.equals(execution.getCalculationFingerprint(), codec.fingerprint(MesPhysicalTransformExecutionCodec.CALCULATION_VERSION,
                execution.getCalculationSnapshot())) && Objects.equals(execution.getReceiptFingerprint(),
                codec.fingerprint(MesPhysicalTransformExecutionCodec.RECEIPT_VERSION, execution.getReceiptSnapshot())),
                "正式转换永久主账归属、版本或原SHA损坏");
        var inputRows = inputs.permanentRows(owner.tenantId(), execution.getId());
        var outputRows = outputs.permanentRows(owner.tenantId(), execution.getId());
        var allocationRows = allocations.permanentRows(owner.tenantId(), execution.getId());
        require(!inputRows.isEmpty() && !outputRows.isEmpty() && !allocationRows.isEmpty(), "正式转换永久子事实不完整");
        var receipt = new MesPhysicalTransformExecutionDTO.Receipt();
        receipt.setRequestId(command.getRequestId());
        receipt.setExecutionId(execution.getId().toString());
        receipt.setExecutionVersion(execution.getExecutionVersion());
        receipt.setCommandId(command.getId().toString());
        receipt.setCommandFingerprint(execution.getCommandFingerprint());
        receipt.setHoldId(execution.getHoldId().toString());
        receipt.setHoldFingerprint(execution.getHoldFingerprint());
        receipt.setTenantId(owner.tenantId().toString());
        receipt.setFactoryCode(owner.factoryCode());
        receipt.setActorUserId(owner.actorId().toString());
        receipt.setActorCode(owner.actorCode());
        receipt.setStatus(execution.getStatus());
        receipt.setOperationType(execution.getOperationType());
        receipt.setMaterialCode(execution.getMaterialCode());
        receipt.setInputUnit(execution.getInputUnit());
        receipt.setOutputUnit(execution.getOutputUnit());
        receipt.setInputQuantity(codec.amount(execution.getInputQuantity()));
        receipt.setGoodQuantity(codec.amount(execution.getGoodQuantity()));
        receipt.setScrapQuantity(codec.amount(execution.getScrapQuantity()));
        receipt.setPositionSurveyId(execution.getPositionSurveyId() == null ? null : execution.getPositionSurveyId().toString());
        receipt.setApplySnapshot(execution.getApplySnapshot());
        receipt.setApplyFingerprint(execution.getApplyFingerprint());
        receipt.setCalculationSnapshot(execution.getCalculationSnapshot());
        receipt.setCalculationFingerprint(execution.getCalculationFingerprint());
        receipt.setReceiptSnapshot(execution.getReceiptSnapshot());
        receipt.setReceiptFingerprint(execution.getReceiptFingerprint());
        receipt.setAppliedTime(execution.getAppliedTime().toString());
        receipt.setInputs(inputRows.stream().map(this::inputLine).toList());
        receipt.setOutputs(outputRows.stream().map(this::outputLine).toList());
        receipt.setAllocations(allocationRows.stream().map(this::allocationLine).toList());
        receipt.setMessage("正式转换已同事务扣减精确投入并建立GOOD库存、SCRAP/MISSING事实、完整来源分配和位置消费；不重复累计生产报工产量");
        return receipt;
    }

    private MesPhysicalTransformExecutionDTO.InputLine inputLine(MesPhysicalTransformInputEntity row) {
        var line = new MesPhysicalTransformExecutionDTO.InputLine();
        line.setInputId(row.getId().toString());
        line.setStockId(row.getStockId().toString());
        line.setSourceReportId(row.getSourceReportId() == null ? null : row.getSourceReportId().toString());
        line.setMaterialCode(row.getMaterialCode());
        line.setUnit(row.getUnit());
        line.setQuantity(codec.amount(row.getQuantity()));
        line.setBeforeQuantity(codec.amount(row.getBeforeQuantity()));
        line.setAfterQuantity(codec.amount(row.getAfterQuantity()));
        line.setOutFlowId(row.getOutFlowId().toString());
        line.setSourceFingerprint(row.getSourceFingerprint());
        return line;
    }

    private MesPhysicalTransformExecutionDTO.OutputLine outputLine(MesPhysicalTransformOutputEntity row) {
        var line = new MesPhysicalTransformExecutionDTO.OutputLine();
        line.setOutputId(row.getId().toString());
        line.setOutputKey(row.getOutputKey());
        line.setIdentifier(row.getIdentifier());
        line.setObjectType(row.getObjectType());
        line.setDisposition(row.getDisposition());
        line.setPositionCode(row.getPositionCode());
        line.setQuantity(codec.amount(row.getQuantity()));
        line.setResultStockId(row.getResultStockId() == null ? null : row.getResultStockId().toString());
        line.setInFlowId(row.getInFlowId() == null ? null : row.getInFlowId().toString());
        return line;
    }

    private MesPhysicalTransformExecutionDTO.AllocationLine allocationLine(MesPhysicalTransformAllocationEntity row) {
        var line = new MesPhysicalTransformExecutionDTO.AllocationLine();
        line.setAllocationId(row.getId().toString());
        line.setStockId(row.getStockId().toString());
        line.setOutputKey(row.getOutputKey());
        line.setQuantity(codec.amount(row.getEquivalentQuantity()));
        return line;
    }

    private MesPhysicalTransformExecutionDTO.Receipt empty(String requestId, Identity owner, String status) {
        var receipt = new MesPhysicalTransformExecutionDTO.Receipt();
        receipt.setRequestId(requestId);
        receipt.setTenantId(owner.tenantId().toString());
        receipt.setFactoryCode(owner.factoryCode());
        receipt.setActorUserId(owner.actorId().toString());
        receipt.setActorCode(owner.actorCode());
        receipt.setStatus(status);
        receipt.setMessage("UNAVAILABLE".equals(status)
                ? "正式转换执行账尚未部署，不能解释为未执行或改用新原号"
                : "当前最新永久账未见正式执行结果；不证明原请求未到达，请保留同一原号和原正文核对");
        return receipt;
    }

    private void assertExpected(MesPhysicalTransformExecutionDTO.Apply apply, Identity owner) {
        require(owner.tenantId().toString().equals(apply.getExpectedTenantId())
                && owner.factoryCode().equals(apply.getExpectedFactoryCode())
                && owner.actorId().toString().equals(apply.getExpectedActorUserId()),
                "登录租户/工厂/实名已变化，不能执行旧页面确认");
    }

    private void assertCommand(MesPhysicalTransformCommandEntity row, Identity owner) {
        require(row != null && Objects.equals(row.getTenantId(), owner.tenantId())
                && Objects.equals(row.getFactoryCode(), owner.factoryCode()) && Objects.equals(row.getActorId(), owner.actorId())
                && Objects.equals(row.getActorCode(), owner.actorCode()) && "0".equals(row.getDelFlag())
                && Objects.equals(row.getRequestFingerprint(), codec.fingerprint(row.getCommandVersion(), row.getCommandSnapshot())),
                "永久原命令不属于当前实际租户/工厂/实名，或原SHA损坏");
    }

    private void assertHold(MesPhysicalTransformSourceHoldEntity row, MesPhysicalTransformCommandEntity command, Identity owner) {
        require(row != null && Objects.equals(row.getTenantId(), owner.tenantId())
                && Objects.equals(row.getFactoryCode(), owner.factoryCode()) && Objects.equals(row.getActorId(), owner.actorId())
                && Objects.equals(row.getActorCode(), owner.actorCode()) && Objects.equals(row.getCommandId(), command.getId())
                && Objects.equals(row.getCommandFingerprint(), command.getRequestFingerprint()) && "0".equals(row.getDelFlag())
                && Objects.equals(row.getFrozenFingerprint(), codec.fingerprint(MesPhysicalTransformSourceHoldCodec.VERSION, row.getFrozenSnapshot()))
                && row.getIdentityCount() != null && row.getIdentityCount() > 0
                && Objects.equals(row.getIdentityCount(), row.getMatchingIdentityCount()), "永久来源占用归属、原SHA或身份全集损坏");
    }

    private void requireReady() {
        require(Boolean.TRUE.equals(baseMapper.available()) && Boolean.TRUE.equals(baseMapper.readinessAvailable())
                && Boolean.TRUE.equals(baseMapper.ready()), "请先完整部署V3第312节正式转换表、守卫及提交证明");
    }

    private void requestLock(Identity owner, String requestId) {
        commands.lockRequest(codec.snapshot(List.of(MesPhysicalTransformCodec.REQUEST_LOCK_VERSION,
                owner.tenantId().toString(), requestId)));
    }

    private Identity identity() {
        var user = SecurityUtils.getUser();
        require(user != null && TenantContextHolder.getTenantId() != null, "正式转换需要实际租户/工厂/实名");
        String factory = codec.code(factoryAccess.require("write"), "实际工厂", 100);
        codec.id(TenantContextHolder.getTenantId().toString());
        codec.id(user.getId().toString());
        return new Identity(TenantContextHolder.getTenantId(), factory, user.getId(),
                codec.code(user.getUsername(), "实际实名账号", 100));
    }

    private <T> T independent(Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "正式转换入口禁止嵌入外围旧事务");
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> work.get());
    }

    private <T> T latestRead(Supplier<T> work) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(true);
        return tx.execute(status -> work.get());
    }

    private void initialize(com.kitex.common.core.entity.BaseEntity<?> entity, Long id, Identity owner, LocalDateTime time) {
        entity.setId(id);
        entity.setTenantId(owner.tenantId());
        entity.setCreateBy(owner.actorCode());
        entity.setCreateTime(time);
        entity.setDelFlag("0");
    }

    private Map<String, Object> inputRow(MesPhysicalTransformInputEntity row, Identity owner, LocalDateTime now) {
        return map("id", row.getId(), "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                "execution_id", row.getExecutionId(), "stock_id", row.getStockId(), "source_report_id", row.getSourceReportId(),
                "material_code", row.getMaterialCode(), "unit", row.getUnit(), "quantity", row.getQuantity(),
                "before_quantity", row.getBeforeQuantity(), "after_quantity", row.getAfterQuantity(), "out_flow_id", row.getOutFlowId(),
                "source_fingerprint", row.getSourceFingerprint(), "create_by", owner.actorCode(), "create_time", now);
    }

    private Map<String, Object> outputRow(MesPhysicalTransformOutputEntity row, Identity owner, LocalDateTime now) {
        return map("id", row.getId(), "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                "execution_id", row.getExecutionId(), "output_key", row.getOutputKey(), "identifier", text(row.getIdentifier()),
                "object_type", row.getObjectType(), "disposition", row.getDisposition(), "position_code", text(row.getPositionCode()),
                "quantity", row.getQuantity(), "result_stock_id", row.getResultStockId(), "in_flow_id", row.getInFlowId(),
                "create_by", owner.actorCode(), "create_time", now);
    }

    private Map<String, Object> allocationRow(MesPhysicalTransformAllocationEntity row, Identity owner, LocalDateTime now) {
        return map("id", row.getId(), "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                "execution_id", row.getExecutionId(), "input_id", row.getInputId(), "output_id", row.getOutputId(),
                "stock_id", row.getStockId(), "output_key", row.getOutputKey(), "equivalent_quantity", row.getEquivalentQuantity(),
                "create_by", owner.actorCode(), "create_time", now);
    }

    private Map<String, Object> flow(Long id, Long executionId, Long outputId, MesWarehouseLiveStockEntity stock,
            Long sourceStockId, Long sourceReportId, String requestId, BigDecimal quantity, String operation,
            String bizType, BigDecimal before, BigDecimal after, Identity owner, LocalDateTime now) {
        return map("id", id, "tenant_id", owner.tenantId(), "factory_code", owner.factoryCode(),
                "execution_id", executionId, "output_id", outputId, "source_stock_id", sourceStockId,
                "source_report_id", sourceReportId, "bill_no", requestId, "resource_no", requestId,
                "produce_task_code", stock.getProduceTaskCode(), "mat_code", stock.getMatCode(), "mat_name", stock.getMatName(),
                "mat_spec", stock.getMatSpec(), "mat_batch", text(stock.getMatBatch()), "mat_unit", stock.getMatUnit(),
                "mat_barcode", text(stock.getMatBarcode()), "warehouse_code", text(stock.getWarehouseCode()),
                "warehouse_name", text(stock.getWarehouseName()), "site_code", text(stock.getSiteCode()), "site_name", text(stock.getSiteName()),
                "quantity", quantity, "operate_type", operation, "biz_type", bizType, "inspection_status", "RELEASED",
                "before_quantity", before, "after_quantity", after, "allocation_policy", "EXACT_PHYSICAL_TRANSFORM_V1",
                "create_by", owner.actorCode(), "create_time", now);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            result.put((String) pairs[index], value instanceof BigDecimal number ? number.toPlainString()
                    : value instanceof LocalDateTime time ? time.toString() : value == null ? "" : value.toString());
        }
        return result;
    }

    private record Identity(Long tenantId, String factoryCode, Long actorId, String actorCode) {
    }

    private record PreparedRows(MesPhysicalTransformExecutionEntity execution,
            List<MesPhysicalTransformInputEntity> inputEntities,
            List<MesPhysicalTransformOutputEntity> outputEntities,
            List<MesPhysicalTransformAllocationEntity> allocationEntities,
            List<Map<String, Object>> stockUpdates, List<Map<String, Object>> outputStocks,
            List<Map<String, Object>> flowRows, List<Map<String, Object>> inputRows,
            List<Map<String, Object>> outputRows, List<Map<String, Object>> allocationRows,
            List<Map<String, Object>> positionRows) {
    }
}
