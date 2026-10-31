package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mes.dto.MesPlanCompletionDTO;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.mapper.MesPlanCompletionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 当前完整来源的只读投影；不把单张完工、关闭或入库当作整计划完成。
 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionProgressService {
    private final MesPlanCompletionMapper facts;
    private final MesPlanCompletionOutboxService outboxes;
    private final MesProductionOrderCapacityGuard withdrawals;
    private final ObjectMapper json;

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public MesPlanCompletionDTO.Progress progress(
            Long outboxId, Long issueId, String issueNo, String factory) {
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        require(
                issueId != null && issueId > 0 && issueNo != null && !issueNo.isBlank(),
                "完整来源进度缺少实际计划身份");
        var evidence = outboxes.evidence(outboxId, factory);
        var event = evidence.getEvent();
        require(
                event.getSources().stream()
                        .anyMatch(
                                line ->
                                        Objects.equals(line.getIssueNo(), issueNo)
                                                && (line.getIssueId() == null
                                                || Objects.equals(
                                                line.getIssueId(), issueId))),
                "所查计划不属于本次完工原件");
        var tasks = facts.sourceTasks(tenant, factory, issueId, issueNo, event.getProductCode());
        require(tasks != null && !tasks.isEmpty(), "完整来源工单事实缺失");
        var released = withdrawals.releasedPlanTasks(issueId, issueNo, tenant, factory);
        Set<Long> ids = new HashSet<>();
        Set<String> codes = new HashSet<>();
        BigDecimal allocated = BigDecimal.ZERO, completed = BigDecimal.ZERO;
        boolean finished = true;
        int count = 0;
        var digest = new ArrayList<Object>();
        LocalDateTime completedAt = null;
        for (var task : tasks) {
            require(
                    task != null
                            && task.getId() != null
                            && task.getId() > 0
                            && ids.add(task.getId())
                            && task.getCode() != null
                            && !task.getCode().isBlank()
                            && codes.add(task.getCode())
                            && Objects.equals(tenant, task.getTenantId())
                            && Objects.equals(factory, task.getFactoryCode())
                            && Objects.equals(event.getProductCode(), task.getMatCode()),
                    "完整来源工单身份或实际组织/产品不一致");
            if (MesPlanCompletionOutboxService.rework(task)) continue;
            require(
                    Arrays.stream(MesTaskStatusEnum.values())
                            .anyMatch(value -> value.name().equals(task.getStatus())),
                    "完整来源存在未知工单状态");
            var lines = outboxes.sourceLines(task, false);
            var matching =
                    lines.stream()
                            .filter(
                                    line ->
                                            Objects.equals(line.getIssueNo(), issueNo)
                                                    && (line.getIssueId() == null
                                                    || Objects.equals(
                                                    line.getIssueId(), issueId)))
                            .toList();
            require(matching.size() == 1, "完整来源未唯一对应原计划");
            if (released.contains(task.getId())) {
                digest.add(List.of("WITHDRAWN", task.getId()));
                continue;
            }
            var line = matching.get(0);
            var quantity = line.getCompletedQuantity();
            if (quantity == null && !"FINISH".equals(task.getStatus())) quantity = BigDecimal.ZERO;
            require(
                    quantity != null
                            && quantity.signum() >= 0
                            && quantity.compareTo(line.getAllocatedQuantity()) <= 0,
                    "完整来源完成量缺失或超过分配量");
            if ("FINISH".equals(task.getStatus())) {
                require(
                        task.getFinishTime() != null
                                && task.getCompleteNum() != null
                                && task.getCompleteNum().signum() >= 0
                                && task.getCompleteNum().compareTo(task.getNoticeNum()) <= 0
                                && MesPlanCompletionOutboxService.same(
                                lines.stream()
                                        .map(
                                                MesPlanCompletionDTO.Line
                                                        ::getCompletedQuantity)
                                        .filter(Objects::nonNull)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                                task.getCompleteNum()),
                        "完整来源已完成工单与逐计划实际量不守恒");
                if (completedAt == null || task.getFinishTime().isAfter(completedAt))
                    completedAt = task.getFinishTime();
            } else finished = false;
            count++;
            allocated = allocated.add(line.getAllocatedQuantity());
            completed = completed.add(quantity);
            digest.add(
                    List.of(
                            task.getId(),
                            task.getCode(),
                            task.getStatus(),
                            Objects.requireNonNullElse(task.getCompletionVersion(), 0),
                            line.getAllocatedQuantity().stripTrailingZeros().toPlainString(),
                            quantity.stripTrailingZeros().toPlainString(),
                            Objects.toString(task.getFinishTime(), "")));
        }
        return new MesPlanCompletionDTO.Progress()
                .setTenantId(tenant)
                .setFactoryCode(factory)
                .setOutboxId(outboxId)
                .setPayloadFingerprint(evidence.getPayloadFingerprint())
                .setIssueId(issueId)
                .setIssueNo(issueNo)
                .setProductCode(event.getProductCode())
                .setAllocatedQuantity(allocated)
                .setCompletedQuantity(completed)
                .setAllFinished(count > 0 && finished)
                .setTaskCount(count)
                .setCompletedAt(completedAt)
                .setSourceFingerprint(
                        MesPlanCompletionCodec.hash(MesPlanCompletionCodec.encode(json, digest)));
    }
}
