package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesPlanWithdrawalReceiptEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 在生产订单事务锁内，以已保存工单和完整撤回回执检查累计接收，不维护第二套数量余额。
 */
@Component
@RequiredArgsConstructor
public class MesProductionOrderCapacityGuard {
    private final MesProductionOrderMapper facts;
    private final DataSource dataSource;

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean nonnegative(Integer value) {
        return value != null && value >= 0;
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean zeroOrMissing(BigDecimal... values) {
        return Arrays.stream(values).allMatch(value -> value == null || value.signum() == 0);
    }

    private static <T> List<T> requireList(List<T> values) {
        require(values != null, "生产订单容量事实不可读取，已停止接收");
        return values;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public void verify(MesTaskReceiveDTO receive, MesTaskEntity current, Long tenantId, String factoryCode) {
        requireCurrentStatementSnapshot();
        List<MesTaskEntity> tasks = requireList(facts.selectOrderCapacityTasks(
                tenantId, factoryCode, receive.getProductionOrder()));
        Map<String, MesTaskEntity> byCode = taskMap(tasks, tenantId, factoryCode);
        MesTaskEntity saved = byCode.get(current.getCode());
        require(saved != null && Objects.equals(saved.getId(), current.getId())
                        && sameNumber(saved.getNoticeNum(), receive.getNoticeNum()) && !isRework(saved),
                "登记订单缺少本次已保存且数量一致的普通工单");
        Set<Long> released = releasedTasks(receive.getProductionOrder(), tenantId, factoryCode);
        require(!released.contains(saved.getId()), "已撤回工单不能作为新接收重新占量");
        BigDecimal occupied = BigDecimal.ZERO;
        for (MesTaskEntity task : tasks) {
            require(Objects.equals(task.getProductionOrder(), receive.getProductionOrder()), "订单容量事实归属不一致");
            if (isRework(task) || released.contains(task.getId())) continue;
            require(sameSource(task, receive), "生产订单已有工单的正式来源或产品不一致，禁止继续接收");
            occupied = occupied.add(task.getNoticeNum());
        }
        require(occupied.compareTo(receive.getProductionOrderQuantity()) <= 0,
                "生产订单累计接收量超出正式订单总量，请核对已有工单和撤回证据");
    }

    /**
     * 固定事务快照会在等待订单锁后继续读取旧占量；检查真实连接，不能仅依赖声明的默认隔离级别。
     */
    void requireCurrentStatementSnapshot() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "订单数量核验必须处于接收事务内");
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            require(connection.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                    "当前接单事务配置不支持累计数量核验，请联系管理员");
        } catch (SQLException exception) {
            throw new CheckedException("无法读取接单事务配置，已停止接收");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private Set<Long> releasedTasks(String orderNo, Long tenantId, String factoryCode) {
        return releasedTasks(facts.selectOrderWithdrawals(tenantId, factoryCode, orderNo), tenantId, factoryCode);
    }

    Set<Long> releasedContractTasks(String contractNo, Integer version, Long tenantId, String factoryCode) {
        return releasedTasks(facts.selectContractWithdrawals(tenantId, factoryCode, contractNo, version), tenantId, factoryCode);
    }

    Set<Long> releasedPlanTasks(Long issueId, String issueNo, Long tenantId, String factoryCode) {
        return releasedTasks(facts.selectPlanWithdrawals(tenantId, factoryCode, issueId, issueNo), tenantId, factoryCode);
    }

    private Set<Long> releasedTasks(List<MesPlanWithdrawalReceiptEntity> values, Long tenantId, String factoryCode) {
        List<MesPlanWithdrawalReceiptEntity> receipts = requireList(values)
                .stream().filter(receipt -> receipt != null && "SUCCEEDED".equals(receipt.getStatus())).toList();
        if (receipts.isEmpty()) return Set.of();
        Map<Long, Set<String>> codesByReceipt = new LinkedHashMap<>();
        Set<String> allCodes = new LinkedHashSet<>();
        for (MesPlanWithdrawalReceiptEntity receipt : receipts) {
            require(Objects.equals(tenantId, receipt.getTenantId()) && Objects.equals(factoryCode, receipt.getFactoryCode())
                            && receipt.getId() != null && receipt.getIssueId() != null && receipt.getIssueId() > 0
                            && StrUtil.isNotBlank(receipt.getIssueNo()) && positive(receipt.getExpectedQuantity())
                            && fingerprint(receipt.getRequestFingerprint()) && fingerprint(receipt.getExpectedEvidenceFingerprint())
                            && Objects.equals(receipt.getExpectedEvidenceFingerprint(), receipt.getActualEvidenceFingerprint())
                            && fingerprint(receipt.getQmsEvidenceFingerprint()) && receipt.getHandledTime() != null
                            && nonnegative(receipt.getClosedProduceTaskCount()) && nonnegative(receipt.getClosedArrangeCount())
                            && nonnegative(receipt.getCancelledQmsTaskCount()) && StrUtil.isNotBlank(receipt.getRequestId())
                            && StrUtil.isNotBlank(receipt.getReason()) && StrUtil.isNotBlank(receipt.getMessage())
                            && StrUtil.isNotBlank(receipt.getClosedTaskCodes()),
                    "生产订单撤回回执缺少完整的成功证据");
            List<String> codes = Arrays.asList(receipt.getClosedTaskCodes().split(",", -1));
            Set<String> unique = new LinkedHashSet<>(codes);
            require(unique.size() == codes.size() && Objects.equals(receipt.getClosedTaskCount(), unique.size())
                            && unique.stream().allMatch(code -> StrUtil.isNotBlank(code) && code.equals(code.trim()))
                            && !codesByReceipt.containsKey(receipt.getId()),
                    "撤回回执的工单集合重复或不完整");
            codesByReceipt.put(receipt.getId(), unique);
            allCodes.addAll(unique);
        }
        Map<String, MesTaskEntity> closed = taskMap(requireList(facts.selectWithdrawalTasks(
                tenantId, factoryCode, List.copyOf(allCodes))), tenantId, factoryCode);
        require(closed.keySet().equals(allCodes), "撤回回执关联的工单事实缺失或不唯一");
        List<Long> ids = closed.values().stream().map(MesTaskEntity::getId).toList();
        List<MesApsBatchAllocationEntity> allocations = requireList(facts.selectWithdrawalAllocations(tenantId, factoryCode, ids));
        require(allocations.stream().allMatch(row -> row != null && ids.contains(row.getMesTaskId())
                        && Objects.equals(tenantId, row.getTenantId()) && Objects.equals(factoryCode, row.getFactoryCode())),
                "撤回来源分配账存在残缺或跨域事实");
        Map<Long, List<MesApsBatchAllocationEntity>> byTask = allocations.stream()
                .collect(Collectors.groupingBy(MesApsBatchAllocationEntity::getMesTaskId));
        Set<Long> released = new HashSet<>();
        for (MesPlanWithdrawalReceiptEntity receipt : receipts) {
            BigDecimal total = BigDecimal.ZERO;
            for (String code : codesByReceipt.get(receipt.getId())) {
                MesTaskEntity task = closed.get(code);
                require("CLOSE".equals(task.getStatus()) && !isRework(task) && task.getStartTime() == null
                                && task.getFinishTime() == null && zeroOrMissing(task.getCompleteNum(), task.getInStockNum(),
                                task.getUnInStockNum(), task.getUnPassNum()) && released.add(task.getId()),
                        "撤回工单已有执行事实、状态不符或被重复释放");
                List<MesApsBatchAllocationEntity> rows = byTask.getOrDefault(task.getId(), List.of());
                require(rows.size() == 1, "撤回工单缺少唯一的PLAN来源分配账");
                MesApsBatchAllocationEntity row = rows.get(0);
                require(Objects.equals(code, row.getMesTaskCode()) && Objects.equals(row.getIssueId(), receipt.getIssueId())
                                && Objects.equals(row.getIssueNo(), receipt.getIssueNo()) && positive(row.getIssueQuantitySnapshot())
                                && sameNumber(row.getAllocatedQuantity(), task.getNoticeNum())
                                && row.getAllocatedQuantity().compareTo(row.getIssueQuantitySnapshot()) <= 0
                                && zeroOrMissing(row.getCompletedQuantity(), row.getInStockQuantity(), row.getUnqualifiedQuantity(),
                                row.getQualityIsolatedQuantity(), row.getQualityReleasedQuantity(), row.getQualityRejectedQuantity(),
                                row.getScrapQuantity(), row.getReworkQuantity(), row.getReworkCompletedQuantity(),
                                row.getReworkQualifiedQuantity(), row.getReworkRejectedQuantity()),
                        "撤回分配账来源、数量或实绩与回执不一致");
                total = total.add(task.getNoticeNum());
            }
            require(sameNumber(total, receipt.getExpectedQuantity()), "撤回回执总量与完整工单集合不守恒");
        }
        return released;
    }

    private Map<String, MesTaskEntity> taskMap(List<MesTaskEntity> tasks, Long tenantId, String factoryCode) {
        Map<String, MesTaskEntity> result = new LinkedHashMap<>();
        Set<Long> ids = new HashSet<>();
        for (MesTaskEntity task : tasks) {
            require(task != null && task.getId() != null && task.getId() > 0 && positive(task.getNoticeNum())
                            && StrUtil.isNotBlank(task.getCode()) && !task.getCode().contains(",")
                            && Objects.equals(tenantId, task.getTenantId()) && Objects.equals(factoryCode, task.getFactoryCode())
                            && ids.add(task.getId()) && !result.containsKey(task.getCode()),
                    "生产订单工单数量、身份或组织事实不完整");
            result.put(task.getCode(), task);
        }
        return result;
    }

    private boolean isRework(MesTaskEntity task) {
        boolean kind = "REWORK".equals(task.getResourceType());
        boolean request = StrUtil.isNotBlank(task.getReworkRequestId());
        boolean source = StrUtil.isNotBlank(task.getSourceTaskCode());
        require(!(kind || request || source) || kind && request && source, "返工工单来源标记不完整，不能排除订单占量");
        return kind;
    }

    private boolean sameSource(MesTaskEntity task, MesTaskReceiveDTO receive) {
        return Objects.equals(task.getMatCode(), receive.getMatCode())
                && Objects.equals(task.getFormalPlanContractNo(), receive.getFormalPlanContractNo())
                && Objects.equals(task.getFormalPlanContractVersion(), receive.getFormalPlanContractVersion())
                && Objects.equals(task.getFormalPlanContractFingerprint(), receive.getFormalPlanContractFingerprint())
                && Objects.equals(task.getSourceSystem(), receive.getSourceSystem())
                && Objects.equals(task.getSourcePlanNo(), receive.getSourcePlanNo())
                && Objects.equals(task.getSourcePlanVersion(), receive.getSourcePlanVersion())
                && Objects.equals(task.getPlanningPolicyId(), receive.getPlanningPolicyId())
                && Objects.equals(task.getPlanningPolicyVersion(), receive.getPlanningPolicyVersion())
                && Objects.equals(task.getPlanningPolicyFingerprint(), receive.getPlanningPolicyFingerprint())
                && Objects.equals(task.getMrpAuthority(), receive.getMrpAuthority())
                && Objects.equals(task.getProductionOrderSyncMode(), receive.getProductionOrderSyncMode())
                && Objects.equals(task.getErpProductionOrderNo(), receive.getErpProductionOrderNo());
    }
}
