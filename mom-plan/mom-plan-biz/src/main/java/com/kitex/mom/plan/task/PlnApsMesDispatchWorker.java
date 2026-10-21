package com.kitex.mom.plan.task;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnApsMesDispatchDTO;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.entity.PlnApsMesDispatchOutboxEntity;
import com.kitex.mom.plan.mapper.PlnApsMesDispatchOutboxMapper;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnApsDispatchService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * APS 正式排程到 MES 的逐计划发送器。
 *
 * <p>每个计划使用独立新事务调用既有 MES 初始工单接收链路；某项失败不会回滚已经成功的计划。
 * 重试时成功项从回执中跳过，MES 端永久请求号仍保证重复调用不重复建单。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlnApsMesDispatchWorker {

    private static final int MAX_ATTEMPTS = 8;

    private final PlnApsMesDispatchOutboxMapper outboxMapper;
    private final PlnSchedulingEngineService schedulingEngineService;
    private final PlnApsDispatchService dispatchService;
    private final PlnApsCaseService caseService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    /** 立即处理当前租户工厂内的一条 Outbox。 */
    public PlnApsMesDispatchDTO.DispatchView process(Long outboxId) {
        processCore(outboxId);
        PlnApsMesDispatchOutboxEntity row = outboxMapper.selectById(outboxId);
        require(row != null, "MES下达任务不存在");
        return dispatchService.detail(row.getCaseId());
    }

    /**
     * 调度器在已建立的租户和工厂上下文中处理一条任务，不依赖浏览器登录态。
     *
     * @return 是否完成了一次领取和处理
     */
    public boolean processScheduled(Long outboxId) {
        processCore(outboxId);
        return true;
    }

    private void processCore(Long outboxId) {
        String lease = UUID.randomUUID().toString().replace("-", "");
        PlnApsMesDispatchOutboxEntity row = transaction(false).execute(status -> claim(outboxId, lease));
        require(row != null, "MES下达任务不存在、尚未到重试时间或已被其他工作器领取");
        try {
            PlnApsMesDispatchDTO.Envelope envelope = parse(row.getPayloadJson(), PlnApsMesDispatchDTO.Envelope.class);
            require(envelope.getItems() != null && !envelope.getItems().isEmpty(),
                    "MES下达不可变载荷缺少计划单元");
            require(Objects.equals(sha256(row.getPayloadJson()), row.getRequestFingerprint()),
                    "MES下达不可变载荷指纹损坏，已停止发送");
            List<PlnApsMesDispatchDTO.ItemReceipt> receipts = previousReceipts(row.getResponseSnapshot());
            boolean transientFailure = false;
            boolean businessFailure = false;
            String lastError = null;
            for (PlnApsMesDispatchDTO.PlanItem item : envelope.getItems()) {
                if (receipts.stream().anyMatch(receipt -> Objects.equals(receipt.getDispatchKey(), item.getDispatchKey())
                        && "SUCCEEDED".equals(receipt.getStatus()))) continue;
                try {
                    Long selectedTaskId = parseId(envelope.getSelectedTaskId());
                    transaction(true).executeWithoutResult(status -> send(selectedTaskId, item));
                    replace(receipts, new PlnApsMesDispatchDTO.ItemReceipt().setDispatchKey(item.getDispatchKey())
                            .setIssueId(item.getIssueId()).setIssueNo(item.getIssueNo())
                            .setIssueIds(item.getIssueIds()).setIssueNos(item.getIssueNos()).setStatus("SUCCEEDED")
                            .setMessage("MES已幂等接收生产计划和锁定工序快照")
                            .setProcessedTime(LocalDateTime.now()));
                } catch (CheckedException ex) {
                    businessFailure = true;
                    lastError = message(ex);
                    replace(receipts, failed(item, "REJECTED", lastError));
                } catch (RuntimeException ex) {
                    transientFailure = true;
                    lastError = message(ex);
                    replace(receipts, failed(item, "RETRY_WAIT", lastError));
                }
            }
            int succeeded = (int) receipts.stream().filter(item -> "SUCCEEDED".equals(item.getStatus())).count();
            int failed = Math.max(0, envelope.getItems().size() - succeeded);
            String finalError = lastError;
            boolean finalTransientFailure = transientFailure;
            boolean finalBusinessFailure = businessFailure;
            transaction(false).executeWithoutResult(status -> finish(row.getId(), lease, receipts, succeeded, failed,
                    finalBusinessFailure, finalTransientFailure, finalError));
        } catch (RuntimeException ex) {
            safelyTerminateClaim(row, lease, ex);
            throw ex;
        }
    }

    private PlnApsMesDispatchOutboxEntity claim(Long outboxId, String lease) {
        PlnApsMesDispatchOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                        .eq(PlnApsMesDispatchOutboxEntity::getId, outboxId).last("FOR UPDATE"));
        if (row == null || !List.of("PENDING", "PARTIAL", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextRetryTime() != null && row.getNextRetryTime().isAfter(LocalDateTime.now())
                || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(LocalDateTime.now())) return null;
        row.setStatus("PROCESSING").setAttemptCount(Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1)
                .setLeaseToken(lease).setLeaseUntil(LocalDateTime.now().plusMinutes(5))
                .setSentTime(LocalDateTime.now()).setLastError(null);
        require(outboxMapper.updateById(row) == 1, "领取MES下达任务失败");
        caseService.recordDispatchStatus(row.getCaseId(), "DISPATCHING",
                stateRequest(row, "START"), "MES下达工作器已领取任务并开始逐单元发送");
        return row;
    }

    private void send(Long selectedTaskId, PlnApsMesDispatchDTO.PlanItem item) {
        dispatchService.verifyFrozenItem(selectedTaskId, item);
        List<Long> issueIds = item.getIssueIds() == null || item.getIssueIds().isEmpty()
                ? List.of(parseId(item.getIssueId())) : item.getIssueIds().stream().map(PlnApsMesDispatchWorker::parseId).toList();
        PlnIssueModuleDTO command = new PlnIssueModuleDTO();
        command.setIds(issueIds.toArray(Long[]::new));
        command.setWorkshopCode(item.getWorkshopCode());
        command.setWorkshopName(item.getWorkshopName());
        require(Boolean.TRUE.equals(schedulingEngineService.issueToModule(command)),
                "PLAN未确认MES初始排程下达成功");
    }

    private void finish(Long id, String lease, List<PlnApsMesDispatchDTO.ItemReceipt> receipts,
                        int succeeded, int failed, boolean businessFailure,
                        boolean transientFailure, String error) {
        PlnApsMesDispatchOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                        .eq(PlnApsMesDispatchOutboxEntity::getId, id)
                        .eq(PlnApsMesDispatchOutboxEntity::getLeaseToken, lease).last("FOR UPDATE"));
        require(row != null && "PROCESSING".equals(row.getStatus()), "MES下达工作器租约已失效");
        String response = json(receipts);
        row.setResponseSnapshot(response).setResponseFingerprint(sha256(response))
                .setDispatchedCount(succeeded).setFailedCount(failed).setLeaseToken(null).setLeaseUntil(null)
                .setLastError(StrUtil.isBlank(error) ? null : abbreviate(error));
        if (failed == 0) {
            row.setStatus("SUCCEEDED").setNextRetryTime(null).setCompletedTime(LocalDateTime.now());
        } else if (businessFailure) {
            row.setStatus("MANUAL_REVIEW").setNextRetryTime(null);
        } else if (transientFailure && row.getAttemptCount() < MAX_ATTEMPTS) {
            row.setStatus(succeeded > 0 ? "PARTIAL" : "RETRY_WAIT")
                    .setNextRetryTime(LocalDateTime.now().plusMinutes(backoffMinutes(row.getAttemptCount())));
        } else {
            row.setStatus("DEAD_LETTER").setNextRetryTime(null);
            if (StrUtil.isBlank(row.getLastError())) row.setLastError("MES下达超过最大重试次数");
        }
        require(outboxMapper.updateById(row) == 1, "保存MES下达逐项回执失败");
        caseService.recordDispatchStatus(row.getCaseId(), caseDispatchStatus(row.getStatus()),
                stateRequest(row, "FINISH"), abbreviate(dispatchReason(row)));
    }

    /** 解析、指纹或历史回执损坏时必须释放租约并留下人工可处理事实。 */
    private void safelyTerminateClaim(PlnApsMesDispatchOutboxEntity claimed, String lease, RuntimeException error) {
        try {
            transaction(false).executeWithoutResult(status -> {
                PlnApsMesDispatchOutboxEntity row = outboxMapper.selectOne(
                        new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                                .eq(PlnApsMesDispatchOutboxEntity::getId, claimed.getId())
                                .eq(PlnApsMesDispatchOutboxEntity::getLeaseToken, lease).last("FOR UPDATE"));
                if (row == null || !"PROCESSING".equals(row.getStatus())) return;
                row.setStatus("MANUAL_REVIEW").setLeaseToken(null).setLeaseUntil(null).setNextRetryTime(null)
                        .setLastError(abbreviate("MES下达载荷或回执校验失败：" + message(error)));
                require(outboxMapper.updateById(row) == 1, "保存MES下达异常终态失败");
                caseService.recordDispatchStatus(row.getCaseId(), "MANUAL_REVIEW",
                        stateRequest(row, "INVALID"), row.getLastError());
            });
        } catch (RuntimeException persistError) {
            log.error("MES下达异常终态保存失败，outboxId={}，原异常={}",
                    claimed.getId(), message(error), persistError);
        }
    }

    private String stateRequest(PlnApsMesDispatchOutboxEntity row, String phase) {
        return "APS-MES-STATE:" + row.getId() + ':' + row.getAttemptCount() + ':' + phase;
    }

    private String caseDispatchStatus(String outboxStatus) {
        return switch (outboxStatus) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "PARTIAL" -> "PARTIAL";
            case "RETRY_WAIT" -> "RETRY_WAIT";
            case "MANUAL_REVIEW" -> "MANUAL_REVIEW";
            case "DEAD_LETTER" -> "FAILED";
            default -> "DISPATCHING";
        };
    }

    private String dispatchReason(PlnApsMesDispatchOutboxEntity row) {
        return switch (row.getStatus()) {
            case "SUCCEEDED" -> "MES已幂等接收全部初始排程单元，案件进入执行跟踪";
            case "PARTIAL" -> "MES已接收部分初始排程单元，其余单元等待自动重试";
            case "RETRY_WAIT" -> "MES本轮未确认接收，已按退避策略等待自动重试";
            case "MANUAL_REVIEW" -> "MES明确拒绝部分下达单元，需要计划员核对后重试：" + row.getLastError();
            case "DEAD_LETTER" -> "MES下达超过最大自动重试次数：" + row.getLastError();
            default -> "MES下达状态已更新为" + row.getStatus();
        };
    }

    private TransactionTemplate transaction(boolean requiresNew) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        if (requiresNew) template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private List<PlnApsMesDispatchDTO.ItemReceipt> previousReceipts(String json) {
        if (StrUtil.isBlank(json)) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<PlnApsMesDispatchDTO.ItemReceipt>>() { }));
        } catch (Exception ex) {
            throw new CheckedException("MES下达历史回执损坏，禁止在未知成功范围上重试");
        }
    }

    private void replace(List<PlnApsMesDispatchDTO.ItemReceipt> receipts,
                         PlnApsMesDispatchDTO.ItemReceipt next) {
        receipts.removeIf(item -> Objects.equals(item.getDispatchKey(), next.getDispatchKey()));
        receipts.add(next);
        receipts.sort(java.util.Comparator.comparing(PlnApsMesDispatchDTO.ItemReceipt::getDispatchKey));
    }

    private PlnApsMesDispatchDTO.ItemReceipt failed(PlnApsMesDispatchDTO.PlanItem item,
                                                     String status, String message) {
        return new PlnApsMesDispatchDTO.ItemReceipt().setDispatchKey(item.getDispatchKey())
                .setIssueId(item.getIssueId()).setIssueNo(item.getIssueNo())
                .setIssueIds(item.getIssueIds()).setIssueNos(item.getIssueNos())
                .setStatus(status).setMessage(abbreviate(message)).setProcessedTime(LocalDateTime.now());
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new CheckedException("MES下达不可变载荷无法解析");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("MES下达回执无法序列化");
        }
    }

    private static int backoffMinutes(int attempts) {
        return Math.min(240, 1 << Math.min(7, Math.max(0, attempts - 1)));
    }

    private static Long parseId(String value) {
        try {
            Long id = Long.valueOf(value);
            require(id > 0, "生产计划主键必须为正整数");
            return id;
        } catch (NumberFormatException ex) {
            throw new CheckedException("生产计划主键格式不正确");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String message(Throwable error) {
        return StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
