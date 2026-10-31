package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesFormalPlanAllocationSnapshot;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 合同事务锁内按全状态工单派生占用，分配本次实际接单量；不维护可漂移的第二套余额。
 */
@Component
@RequiredArgsConstructor
public class MesFormalPlanAllocationService {
    private final MesProductionOrderMapper facts;
    private final MesProductionOrderCapacityGuard capacity;
    private final MesFormalPlanReceiptVerifier verifier;
    private final ObjectMapper json;

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public void allocate(MesTaskReceiveDTO receive, MesTaskEntity current) {
        Long tenantId = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(factory) && receive != null && current != null
                && Objects.equals(factory, receive.getFactoryCode()), "合同分配必须处于实际租户工厂上下文");
        capacity.requireCurrentStatementSnapshot();
        require(receive.getFormalPlanContractNo() != null && receive.getFormalPlanContractVersion() != null,
                "合同分配缺少正式合同身份");
        require(StrUtil.isNotBlank(receive.getSourceRequestId()) && receive.getSourceRequestId().length() <= 100
                        && receive.getSourceRequestFingerprint() != null
                        && receive.getSourceRequestFingerprint().matches("[0-9a-f]{64}"),
                "合同分配缺少永久接单请求号或有效载荷指纹");
        String lockKey = String.join(":", "MES_FORMAL_PLAN_ALLOCATION", tenantId.toString(), factory,
                receive.getFormalPlanContractNo(), receive.getFormalPlanContractVersion().toString());
        require(Objects.equals(1, facts.lockFormalContract(lockKey)), "未能锁定正式合同数量分配");
        // 等锁后重读当前合同/策略，不能使用等待之前的跨服务快照继续接收。
        var verified = verifier.verify(receive);
        var contract = verified.contract();
        require(Objects.equals(contract.getContractNo(), receive.getFormalPlanContractNo())
                        && Objects.equals(contract.getContractVersion(), receive.getFormalPlanContractVersion())
                        && "VERIFIED".equals(contract.getVerificationStatus()) && "PUBLISHED".equals(contract.getContractStatus()),
                "合同分配读取的正式合同身份或状态不一致");
        Map<String, PlnFormalPlanContractDTO.Line> lines = new LinkedHashMap<>();
        for (var line : contract.getLines()) lines.put(line.getId(), line);
        List<MesTaskEntity> tasks = facts.selectContractCapacityTasks(tenantId, factory,
                contract.getContractNo(), contract.getContractVersion());
        require(tasks != null, "正式合同工单占量事实不可读取");
        Set<Long> released = capacity.releasedContractTasks(contract.getContractNo(), contract.getContractVersion(), tenantId, factory);
        Map<String, BigDecimal> occupied = new LinkedHashMap<>();
        Map<Long, AllocationEvidence> evidence = new LinkedHashMap<>();
        Set<Long> taskIds = new HashSet<>();
        Set<String> taskCodes = new HashSet<>();
        MesTaskEntity saved = null;
        for (var task : tasks) {
            require(task != null && task.getId() != null && task.getId() > 0 && taskIds.add(task.getId())
                            && StrUtil.isNotBlank(task.getCode()) && taskCodes.add(task.getCode())
                            && Objects.equals(tenantId, task.getTenantId()) && Objects.equals(factory, task.getFactoryCode()),
                    "合同工单身份、组织或唯一性事实不完整");
            if (Objects.equals(task.getId(), current.getId())) {
                require(Objects.equals(task.getCode(), current.getCode()) && !released.contains(task.getId())
                                && same(task.getNoticeNum(), receive.getNoticeNum())
                                && Objects.equals(task.getProductionOrder(), receive.getProductionOrder())
                                && Objects.equals(task.getFormalPlanContractId(), receive.getFormalPlanContractId())
                                && Objects.equals(task.getSourceRequestId(), receive.getSourceRequestId())
                                && Objects.equals(task.getSourceRequestFingerprint(), receive.getSourceRequestFingerprint()),
                        "合同分配缺少本次已保存且来源数量一致的工单");
                saved = task;
            }
            if (isRework(task)) {
                require(!Objects.equals(task.getId(), current.getId()), "普通接收不能按返工排除合同占量");
                continue;
            }
            require(sameHeader(task, receive) && positive(task.getNoticeNum()) && StrUtil.isNotBlank(task.getProductionOrder()),
                    "合同已有工单的正式来源或数量不一致");
            boolean legacy = task.getFormalPlanAllocationVersion() == null && task.getFormalPlanAllocationSnapshot() == null;
            require(legacy || Objects.equals(1, task.getFormalPlanAllocationVersion())
                    && task.getFormalPlanAllocationSnapshot() != null, "工单合同分配模型与快照不一致");
            if (Objects.equals(task.getId(), current.getId()) && legacy) continue;
            var allocation = readAllocation(task, lines, contract);
            evidence.put(task.getId(), allocation);
            if (released.contains(task.getId())) continue;
            allocation.lines().forEach((id, qty) -> occupied.merge(id, qty, BigDecimal::add));
        }
        require(saved != null, "合同分配找不到本次已保存工单");
        for (var entry : occupied.entrySet()) {
            require(entry.getValue().compareTo(lines.get(entry.getKey()).getPlannedQuantity()) <= 0,
                    "正式合同明细已有累计占量超限，禁止继续接收");
        }
        BigDecimal releasedBaseline = verifyWholeOrderCapacity(receive, current.getId(), verified, tasks, released,
                lines, evidence, tenantId, factory);
        if (saved.getFormalPlanAllocationSnapshot() != null) {
            current.setFormalPlanAllocationSnapshot(saved.getFormalPlanAllocationSnapshot());
            current.setFormalPlanAllocationVersion(saved.getFormalPlanAllocationVersion());
            return;
        }
        BigDecimal remaining = receive.getNoticeNum();
        List<MesFormalPlanAllocationSnapshot.Line> allocation = new ArrayList<>();
        for (var line : verified.coverage().lines()) {
            BigDecimal available = line.quantity().subtract(occupied.getOrDefault(line.id(), BigDecimal.ZERO));
            BigDecimal take = remaining.min(available);
            if (take.signum() > 0) {
                allocation.add(new MesFormalPlanAllocationSnapshot.Line(line.id(), line.lineNo(), line.fingerprint(), take));
                remaining = remaining.subtract(take);
            }
        }
        require(remaining.signum() == 0, "正式合同自制明细剩余数量不足，其他生产订单或工单已占用");
        var snapshot = new MesFormalPlanAllocationSnapshot(1, saved.getId().toString(), saved.getCode(),
                saved.getSourceRequestId(), saved.getSourceRequestFingerprint(), saved.getProductionOrder(),
                contract.getContractNo(), contract.getContractVersion(), contract.getSourceContentFingerprint(),
                verified.coverage().sourceDemandNo(), verified.coverage().productCode(), verified.coverage().unitCode(),
                receive.getProductionOrderQuantity(), releasedBaseline, allocation);
        try {
            String value = json.writeValueAsString(snapshot);
            require(facts.freezeContractAllocation(tenantId, factory, saved.getId(), value) == 1,
                    "正式合同分配快照保存失败或已变化");
            current.setFormalPlanAllocationSnapshot(value);
            current.setFormalPlanAllocationVersion(1);
        } catch (JsonProcessingException exception) {
            throw new CheckedException("正式合同分配快照序列化失败");
        }
    }

    /**
     * 首批接收即占其冻结整单量，防止未接收的后续拆批被另一订单抢占；撤回只释放有证据的实际量。
     */
    private BigDecimal verifyWholeOrderCapacity(MesTaskReceiveDTO receive, Long currentId,
                                                MesFormalPlanReceiptVerifier.VerifiedReceipt verified, List<MesTaskEntity> tasks, Set<Long> released,
                                                Map<String, PlnFormalPlanContractDTO.Line> lines, Map<Long, AllocationEvidence> evidence,
                                                Long tenantId, String factory) {
        var orders = facts.selectContractCapacityOrders(tenantId, factory, receive.getFormalPlanContractNo(),
                receive.getFormalPlanContractVersion());
        require(orders != null, "正式合同生产订单承诺量不可读取");
        Map<String, List<MesTaskEntity>> byOrder = new LinkedHashMap<>();
        for (var task : tasks) {
            if (!isRework(task)) byOrder.computeIfAbsent(task.getProductionOrder(), key -> new ArrayList<>()).add(task);
        }
        Set<String> orderNos = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        BigDecimal reserved = BigDecimal.ZERO;
        BigDecimal currentBaseline = BigDecimal.ZERO;
        for (var order : orders) {
            var representative = order == null ? null : lines.get(Objects.toString(order.getFormalPlanContractId(), ""));
            require(order != null && order.getId() != null && ids.add(order.getId()) && StrUtil.isNotBlank(order.getOrderNo())
                            && orderNos.add(order.getOrderNo()) && Objects.equals(tenantId, order.getTenantId())
                            && Objects.equals(factory, order.getFactoryCode()) && positive(order.getOrderQuantity())
                            && representative != null && "MAKE".equals(representative.getSupplyType())
                            && Objects.equals(representative.getProductCode(), order.getProductCode())
                            && "PLAN".equals(order.getSourceType())
                            && Objects.equals(order.getSourceNo(), receive.getFormalPlanContractNo())
                            && Objects.equals(order.getFormalPlanContractNo(), receive.getFormalPlanContractNo())
                            && Objects.equals(order.getFormalPlanContractVersion(), receive.getFormalPlanContractVersion())
                            && Objects.equals(order.getFormalPlanContractFingerprint(), receive.getFormalPlanContractFingerprint())
                            && Objects.equals(order.getSourceSystem(), receive.getSourceSystem())
                            && Objects.equals(order.getSourcePlanNo(), receive.getSourcePlanNo())
                            && Objects.equals(order.getSourcePlanVersion(), receive.getSourcePlanVersion())
                            && Objects.equals(order.getPlanningPolicyId(), receive.getPlanningPolicyId())
                            && Objects.equals(order.getPlanningPolicyVersion(), receive.getPlanningPolicyVersion())
                            && Objects.equals(order.getPlanningPolicyFingerprint(), receive.getPlanningPolicyFingerprint())
                            && Objects.equals(order.getMrpAuthority(), receive.getMrpAuthority()),
                    "正式合同已有生产订单身份、来源或冻结总量不完整");
            var orderTasks = byOrder.getOrDefault(order.getOrderNo(), List.of());
            for (var task : orderTasks) {
                var taskLine = lines.get(Objects.toString(task.getFormalPlanContractId(), ""));
                require(taskLine != null && Objects.equals(taskLine.getSourceDemandNo(), representative.getSourceDemandNo())
                                && Objects.equals(taskLine.getProductCode(), representative.getProductCode()),
                        "生产订单混入不同来源需求或产品的合同额度");
            }
            BigDecimal baseline = BigDecimal.ZERO;
            boolean newModel = false;
            for (var task : orderTasks) {
                var allocation = evidence.get(task.getId());
                if (allocation != null && allocation.orderQuantity() != null) {
                    require(same(order.getOrderQuantity(), allocation.orderQuantity()), "工单快照与生产订单冻结总量不一致");
                    baseline = baseline.max(allocation.releasedBaseline());
                    newModel = true;
                }
            }
            require(newModel || positive(representative.getPlannedQuantity())
                            && order.getOrderQuantity().compareTo(representative.getPlannedQuantity()) <= 0,
                    "历史生产订单总量超出原代表行，不能自动改用多路径额度");
            BigDecimal freed = orderTasks.stream().filter(task -> released.contains(task.getId()))
                    .map(MesTaskEntity::getNoticeNum).reduce(BigDecimal.ZERO, BigDecimal::add);
            require(freed.compareTo(baseline) >= 0, "生产订单撤回证据少于工单冻结基线");
            if (Objects.equals(order.getOrderNo(), receive.getProductionOrder())) {
                require(same(order.getOrderQuantity(), receive.getProductionOrderQuantity())
                                && verified.coverage().contains(order.getFormalPlanContractId()),
                        "既有生产订单的冻结总量或来源需求不能变化");
                // 同订单在撤回后再次接收，重新取得整单承诺；旧撤回量不能永远抵扣后续新承诺。
                if (!evidence.containsKey(currentId)) baseline = freed;
                currentBaseline = baseline;
            }
            if (sameGroup(representative, verified)) {
                BigDecimal releasedSinceCommitment = freed.subtract(baseline);
                require(releasedSinceCommitment.compareTo(order.getOrderQuantity()) <= 0, "生产订单本次承诺的撤回量超出冻结总量");
                reserved = reserved.add(order.getOrderQuantity().subtract(releasedSinceCommitment));
            }
        }
        // 无订单主表的旧接单只认已观察到的工单量，不补造其未知整单承诺。
        for (var entry : byOrder.entrySet()) {
            if (orderNos.contains(entry.getKey())) continue;
            for (var task : entry.getValue()) {
                var allocation = evidence.get(task.getId());
                require(allocation == null || allocation.orderQuantity() == null, "已冻结合同分配的工单缺少生产订单主记录");
                if (Objects.equals(receive.getProductionOrder(), entry.getKey())) {
                    require(sameGroup(lines.get(Objects.toString(task.getFormalPlanContractId(), "")), verified),
                            "旧订单工单不能合并不同来源需求的承诺");
                    if (released.contains(task.getId())) currentBaseline = currentBaseline.add(task.getNoticeNum());
                    continue;
                }
                if (!Objects.equals(currentId, task.getId()) && !released.contains(task.getId())
                        && sameGroup(lines.get(Objects.toString(task.getFormalPlanContractId(), "")), verified)) {
                    reserved = reserved.add(task.getNoticeNum());
                }
            }
        }
        if (!orderNos.contains(receive.getProductionOrder()))
            reserved = reserved.add(receive.getProductionOrderQuantity());
        require(reserved.compareTo(verified.coverage().quantity()) <= 0,
                "正式合同整单承诺总量不足，后续拆批额度已由其他生产订单占用");
        return currentBaseline;
    }

    private boolean sameGroup(PlnFormalPlanContractDTO.Line line, MesFormalPlanReceiptVerifier.VerifiedReceipt verified) {
        return line != null && Objects.equals(line.getSourceDemandNo(), verified.coverage().sourceDemandNo())
                && Objects.equals(line.getProductCode(), verified.coverage().productCode());
    }

    private AllocationEvidence readAllocation(MesTaskEntity task,
                                              Map<String, PlnFormalPlanContractDTO.Line> lines, PlnFormalPlanContractDTO contract) {
        var representative = lines.get(Objects.toString(task.getFormalPlanContractId(), ""));
        require(representative != null && "MAKE".equals(representative.getSupplyType())
                        && Objects.equals(representative.getProductCode(), task.getMatCode()),
                "已有工单不能精确归属正式合同自制行");
        if (task.getFormalPlanAllocationSnapshot() == null) {
            require(positive(representative.getPlannedQuantity())
                            && task.getNoticeNum().compareTo(representative.getPlannedQuantity()) <= 0,
                    "历史工单数量超出原代表行，不能重算到其他合同路径");
            return new AllocationEvidence(Map.of(representative.getId(), task.getNoticeNum()), null, BigDecimal.ZERO);
        }
        MesFormalPlanAllocationSnapshot snapshot;
        try {
            snapshot = json.readerFor(MesFormalPlanAllocationSnapshot.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(task.getFormalPlanAllocationSnapshot());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new CheckedException("已有工单的合同分配快照损坏，禁止猜测占量");
        }
        require(snapshot != null && Objects.equals(1, snapshot.schemaVersion())
                        && Objects.equals(task.getId().toString(), snapshot.taskId()) && Objects.equals(task.getCode(), snapshot.taskCode())
                        && Objects.equals(task.getSourceRequestId(), snapshot.requestId())
                        && Objects.equals(task.getSourceRequestFingerprint(), snapshot.requestFingerprint())
                        && Objects.equals(task.getProductionOrder(), snapshot.orderNo())
                        && Objects.equals(contract.getContractNo(), snapshot.contractNo())
                        && Objects.equals(contract.getContractVersion(), snapshot.contractVersion())
                        && Objects.equals(contract.getSourceContentFingerprint(), snapshot.contractFingerprint())
                        && Objects.equals(representative.getSourceDemandNo(), snapshot.sourceDemandNo())
                        && Objects.equals(task.getMatCode(), snapshot.productCode())
                        && Objects.equals(representative.getUnitCode(), snapshot.unitCode())
                        && positive(snapshot.orderQuantity()) && snapshot.releasedQuantityBaseline() != null
                        && snapshot.releasedQuantityBaseline().signum() >= 0
                        && snapshot.lines() != null && !snapshot.lines().isEmpty(),
                "已有工单的合同分配快照与冻结来源不一致");
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var share : snapshot.lines()) {
            var line = share == null ? null : lines.get(share.lineId());
            require(line != null && "MAKE".equals(line.getSupplyType()) && positive(share.quantity())
                            && positive(line.getPlannedQuantity()) && share.quantity().compareTo(line.getPlannedQuantity()) <= 0
                            && Objects.equals(line.getLineNo(), share.lineNo())
                            && Objects.equals(line.getContentFingerprint(), share.fingerprint())
                            && Objects.equals(line.getSourceDemandNo(), snapshot.sourceDemandNo())
                            && Objects.equals(line.getProductCode(), snapshot.productCode())
                            && Objects.equals(line.getUnitCode(), snapshot.unitCode())
                            && result.putIfAbsent(share.lineId(), share.quantity()) == null,
                    "已有工单的合同分配明细重复、超量或来源不一致");
            total = total.add(share.quantity());
        }
        require(same(total, task.getNoticeNum()), "工单合同分配总量与实际接收量不守恒");
        require(total.compareTo(snapshot.orderQuantity()) <= 0, "工单分配量超过快照冻结的整单总量");
        return new AllocationEvidence(result, snapshot.orderQuantity(), snapshot.releasedQuantityBaseline());
    }

    private boolean sameHeader(MesTaskEntity task, MesTaskReceiveDTO receive) {
        return Objects.equals(task.getFormalPlanContractNo(), receive.getFormalPlanContractNo())
                && Objects.equals(task.getFormalPlanContractVersion(), receive.getFormalPlanContractVersion())
                && Objects.equals(task.getFormalPlanContractFingerprint(), receive.getFormalPlanContractFingerprint())
                && Objects.equals(task.getSourceSystem(), receive.getSourceSystem())
                && Objects.equals(task.getSourcePlanNo(), receive.getSourcePlanNo())
                && Objects.equals(task.getSourcePlanVersion(), receive.getSourcePlanVersion())
                && Objects.equals(task.getPlanningPolicyId(), receive.getPlanningPolicyId())
                && Objects.equals(task.getPlanningPolicyVersion(), receive.getPlanningPolicyVersion())
                && Objects.equals(task.getPlanningPolicyFingerprint(), receive.getPlanningPolicyFingerprint())
                && Objects.equals(task.getMrpAuthority(), receive.getMrpAuthority());
    }

    private boolean isRework(MesTaskEntity task) {
        boolean kind = "REWORK".equals(task.getResourceType());
        boolean request = StrUtil.isNotBlank(task.getReworkRequestId());
        boolean source = StrUtil.isNotBlank(task.getSourceTaskCode());
        require(!(kind || request || source) || kind && request && source, "返工工单来源标记不完整，不能排除合同占量");
        return kind;
    }

    private record AllocationEvidence(Map<String, BigDecimal> lines, BigDecimal orderQuantity,
                                      BigDecimal releasedBaseline) {
    }
}
