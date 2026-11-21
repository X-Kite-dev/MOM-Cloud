package com.kitex.mom.plan.task;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
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
import java.util.*;

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
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /**
     * 立即处理当前租户工厂内的一条 Outbox。
     */
    public PlnApsMesDispatchDTO.DispatchView process(Long outboxId) {
        PlnApsMesDispatchOutboxEntity row = outboxMapper.selectOne(scopedRow(outboxId));
        require(row != null, "当前组织中没有该MES下达单");
        var current = dispatchService.detail(row.getCaseId());
        require(Objects.equals(current.getOutboxId(), outboxId.toString()), "案件的当前下达单已变化，请刷新");
        require(processCore(outboxId), "下达任务尚未到期、已结束或正由其他工作器处理");
        return dispatchService.detail(row.getCaseId());
    }

    /** 在调度器恢复的组织内处理原单；其他实例已领取或已完成时返回 false。 */
    public boolean processScheduled(Long outboxId) {
        return processCore(outboxId);
    }

    /** 领取时记录是否为中断续办。明确的业务拒绝需计划员重新入队后才能重发。 */
    private record Claim(PlnApsMesDispatchOutboxEntity row, boolean recovered, boolean exhausted) {
    }

    private boolean processCore(Long outboxId) {
        String lease = UUID.randomUUID().toString().replace("-", "");
        Claim claim = newTransaction().execute(status -> claim(outboxId, lease));
        if (claim == null) {
            return false;
        }
        var row = claim.row();
        try {
            var envelope = verifiedEnvelope(row);
            var previous = dispatchService.readReceipts(row);
            Set<String> keys = new HashSet<>();
            for (var item : envelope.getItems()) {
                require(item != null && StrUtil.isNotBlank(item.getDispatchKey()) && keys.add(item.getDispatchKey()),
                        "MES下达载荷包含空单元或重复下达键");
            }
            Set<String> skip = new HashSet<>();
            for (var receipt : previous) {
                require(keys.contains(receipt.getDispatchKey()), "原回执包含本次发布范围以外的下达单元");
                require(Set.of("SUCCEEDED", "REJECTED", "RETRY_WAIT").contains(receipt.getStatus()),
                        "MES下达逐项回执含未知状态");
                if ("SUCCEEDED".equals(receipt.getStatus()) || (claim.recovered() && "REJECTED".equals(receipt.getStatus()))) {
                    skip.add(receipt.getDispatchKey());
                }
            }
            if (!claim.exhausted()) {
                for (var item : envelope.getItems()) {
                    if (!skip.contains(item.getDispatchKey())) {
                        processItem(row, lease, item, keys.size());
                    }
                }
            }
            newTransaction().executeWithoutResult(status -> finish(outboxId, lease, keys.size(), claim.exhausted()));
        } catch (RuntimeException ex) {
            safelyTerminateClaim(row, lease, ex);
            throw ex;
        }
        return true;
    }

    /** 核对数据库原单与发布载荷的组织、情景及冻结身份，再按原单元投递。 */
    private PlnApsMesDispatchDTO.Envelope verifiedEnvelope(PlnApsMesDispatchOutboxEntity row) {
        require(Objects.equals(sha256(row.getPayloadJson()), row.getRequestFingerprint()),
                "MES下达不可变载荷指纹损坏，已停止发送");
        var envelope = parse(row.getPayloadJson(), PlnApsMesDispatchDTO.Envelope.class);
        require(envelope != null && envelope.getItems() != null && !envelope.getItems().isEmpty(),
                "MES下达不可变载荷缺少计划单元");
        require("PLAN_APS_MES_DISPATCH_V1".equals(envelope.getContractVersion())
                        && Objects.equals(envelope.getRequestId(), row.getRequestId())
                        && Objects.equals(envelope.getFactoryCode(), row.getFactoryCode())
                        && Objects.equals(envelope.getCaseId(), row.getCaseId().toString())
                        && Objects.equals(envelope.getScenarioId(), row.getScenarioId().toString())
                        && Objects.equals(envelope.getSelectedTaskId(), row.getSelectedTaskId().toString())
                        && Objects.equals(envelope.getPublicationFingerprint(), row.getPublicationFingerprint()),
                "MES下达原单与发布载荷身份不一致");
        return envelope;
    }

    /** 一次只锁当前组织的一张原单。正在网络调用的持锁实例即使超过租期也不会被并发接管。 */
    private Claim claim(Long outboxId, String lease) {
        var row = outboxMapper.selectOne(scopedRow(outboxId).last("FOR UPDATE SKIP LOCKED"));
        LocalDateTime now = LocalDateTime.now();
        if (row == null || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now)) {
            return null;
        }
        boolean recovered = "PROCESSING".equals(row.getStatus());
        if (!recovered && (!Set.of("PENDING", "PARTIAL", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextRetryTime() != null && row.getNextRetryTime().isAfter(now))) {
            return null;
        }
        int attempts = Objects.requireNonNullElse(row.getAttemptCount(), 0);
        boolean exhausted = recovered && (attempts >= MAX_ATTEMPTS || row.getLeaseUntil() == null
                || StrUtil.isBlank(row.getLeaseToken()));
        row.setStatus("PROCESSING").setLeaseToken(lease).setLeaseUntil(now.plusMinutes(5))
                .setNextRetryTime(null).setLastError(null);
        if (!exhausted) {
            row.setAttemptCount(attempts + 1).setSentTime(now);
        }
        require(outboxMapper.updateById(row) == 1, "领取MES下达任务失败");
        if (!exhausted) {
            caseService.recordDispatchStatus(row.getCaseId(), "DISPATCHING", stateRequest(row, "START"),
                    recovered ? "原发送租约已到期，按逐项回执接续原下达单" : "MES下达工作器已领取任务并开始逐单元发送");
        }
        return new Claim(row, recovered, exhausted);
    }

    private LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity> scopedRow(Long id) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                        && StrUtil.isNotBlank(factory) && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "MES下达需要明确的租户及工厂");
        return new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                .eq(PlnApsMesDispatchOutboxEntity::getId, id)
                .eq(PlnApsMesDispatchOutboxEntity::getTenantId, tenant)
                .eq(PlnApsMesDispatchOutboxEntity::getFactoryCode, factory);
    }

    /** 每次发令及回写都在本地事务内持租约行锁；过期工作器无法修改已被接管的下达单。 */
    private PlnApsMesDispatchOutboxEntity lockOwned(Long id, String lease) {
        var row = outboxMapper.selectOne(scopedRow(id).eq(PlnApsMesDispatchOutboxEntity::getLeaseToken, lease)
                .eq(PlnApsMesDispatchOutboxEntity::getStatus, "PROCESSING").last("FOR UPDATE"));
        require(row != null, "MES下达工作器租约已失效");
        return row;
    }

    /** 本地计划接收信息与该单元的成功回执一起提交。跨库中断时，MES 用原永久请求号判重。 */
    private void processItem(PlnApsMesDispatchOutboxEntity claimed, String lease,
                             PlnApsMesDispatchDTO.PlanItem item, int total) {
        try {
            newTransaction().executeWithoutResult(status -> {
                var row = lockOwned(claimed.getId(), lease);
                var orders = send(row.getSelectedTaskId(), item);
                var confirmed = receipt(item, "SUCCEEDED", "MES已幂等接收生产计划和锁定工序快照");
                checkpoint(row, confirmed.setMesOrders(orders), total);
            });
        } catch (RuntimeException ex) {
            // 发送事务已经结束；若提交实际成功但连接丢失，以库内成功回执为准。
            newTransaction().executeWithoutResult(status -> {
                var row = lockOwned(claimed.getId(), lease);
                String outcome = ex instanceof CheckedException ? "REJECTED" : "RETRY_WAIT";
                checkpoint(row, receipt(item, outcome, message(ex)), total);
            });
        }
    }

    /** 合并已落库的回执并续租。失败结果不得覆盖提交成功但响应丢失的单元。 */
    private void checkpoint(PlnApsMesDispatchOutboxEntity row, PlnApsMesDispatchDTO.ItemReceipt next, int total) {
        var receipts = new ArrayList<>(dispatchService.readReceipts(row));
        if (receipts.stream().anyMatch(item -> Objects.equals(item.getDispatchKey(), next.getDispatchKey())
                && "SUCCEEDED".equals(item.getStatus()))) {
            return;
        }
        replace(receipts, next);
        if (next.getMesOrders() != null) {
            // 单元可含多个批次，多个来源也可合成一张工单。各工单和请求仅可计入一个单元一次。
            Set<String> taskIds = new HashSet<>();
            Set<String> requestIds = new HashSet<>();
            for (var item : receipts) {
                if (item.getMesOrders() != null) {
                    for (var order : item.getMesOrders()) {
                        require(taskIds.add(order.getTaskId()) && requestIds.add(order.getRequestId()),
                                "同一MES工单或接单请求被重复计入下达回执，请核对原单");
                    }
                }
            }
        }
        int succeeded = (int) receipts.stream().filter(item -> "SUCCEEDED".equals(item.getStatus())).count();
        String snapshot = json(receipts);
        row.setResponseSnapshot(snapshot).setResponseFingerprint(sha256(snapshot))
                .setDispatchedCount(succeeded).setFailedCount(total - succeeded)
                .setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        require(outboxMapper.updateById(row) == 1, "保存MES下达单元回执失败");
    }

    /** 核验发布时冻结的下达单元，取得该单元对应的全部 MES 原工单。 */
    private List<PlnApsMesDispatchDTO.MesOrderReceipt> send(Long selectedTaskId, PlnApsMesDispatchDTO.PlanItem item) {
        dispatchService.verifyFrozenItem(selectedTaskId, item);
        List<Long> issueIds = item.getIssueIds() == null || item.getIssueIds().isEmpty()
                ? List.of(parseId(item.getIssueId())) : item.getIssueIds().stream().map(PlnApsMesDispatchWorker::parseId).toList();
        PlnIssueModuleDTO command = new PlnIssueModuleDTO();
        command.setIds(issueIds.toArray(Long[]::new));
        command.setWorkshopCode(item.getWorkshopCode());
        command.setWorkshopName(item.getWorkshopName());
        var orders = schedulingEngineService.issueToMesWithReceipts(command);
        require(orders != null && !orders.isEmpty(), "MES未返回任何已确认的生产工单");
        return orders;
    }

    /** 从已提交的逐项回执结算整单，最后一项提交后中断也可直接恢复终态。 */
    private void finish(Long id, String lease, int total, boolean exhausted) {
        var row = lockOwned(id, lease);
        var receipts = dispatchService.readReceipts(row);
        var rejected = receipts.stream().filter(item -> "REJECTED".equals(item.getStatus())).findFirst();
        var pending = receipts.stream().filter(item -> "RETRY_WAIT".equals(item.getStatus())).findFirst();
        int succeeded = row.getDispatchedCount();
        int failed = total - succeeded;
        require(failed >= 0 && (exhausted || receipts.size() == total), "MES下达逐项回执未齐");
        String error = rejected.or(() -> pending).map(PlnApsMesDispatchDTO.ItemReceipt::getMessage).orElse(null);
        row.setFailedCount(failed).setLeaseToken(null).setLeaseUntil(null).setLastError(abbreviate(error));
        if (failed == 0) {
            row.setStatus("SUCCEEDED").setNextRetryTime(null).setCompletedTime(LocalDateTime.now());
        } else if (exhausted || rejected.isPresent()) {
            String fallback = exhausted ? "原发送租约缺失或发送次数已达上限，请核对未确认项" : "MES拒绝下达，请核对原计划";
            row.setLastError(StrUtil.blankToDefault(error, fallback));
            row.setStatus("MANUAL_REVIEW").setNextRetryTime(null);
        } else if (pending.isPresent() && row.getAttemptCount() < MAX_ATTEMPTS) {
            row.setStatus(succeeded > 0 ? "PARTIAL" : "RETRY_WAIT")
                    .setNextRetryTime(LocalDateTime.now().plusMinutes(backoffMinutes(row.getAttemptCount())));
        } else {
            row.setStatus("DEAD_LETTER").setNextRetryTime(null);
            if (StrUtil.isBlank(row.getLastError())) {
                row.setLastError("MES下达超过最大重试次数");
            }
        }
        require(outboxMapper.updateById(row) == 1, "保存MES下达逐项回执失败");
        caseService.recordDispatchStatus(row.getCaseId(), caseDispatchStatus(row.getStatus()),
                stateRequest(row, "FINISH"), abbreviate(dispatchReason(row)));
    }

    /**
     * 解析、指纹或历史回执损坏时必须释放租约并留下人工可处理事实。
     */
    private void safelyTerminateClaim(PlnApsMesDispatchOutboxEntity claimed, String lease, RuntimeException error) {
        try {
            newTransaction().executeWithoutResult(status -> {
                PlnApsMesDispatchOutboxEntity row = outboxMapper.selectOne(
                        scopedRow(claimed.getId())
                                .eq(PlnApsMesDispatchOutboxEntity::getLeaseToken, lease).last("FOR UPDATE"));
                if (row == null || !"PROCESSING".equals(row.getStatus())) {
                    return;
                }
                row.setStatus("MANUAL_REVIEW").setLeaseToken(null).setLeaseUntil(null).setNextRetryTime(null)
                        .setLastError(abbreviate("MES原下达单接续失败：" + message(error)));
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
            case "MANUAL_REVIEW" -> "MES下达需要计划员核对原单：" + row.getLastError();
            case "DEAD_LETTER" -> "MES下达超过最大自动重试次数：" + row.getLastError();
            default -> "MES下达状态已更新为" + row.getStatus();
        };
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private void replace(List<PlnApsMesDispatchDTO.ItemReceipt> receipts,
                         PlnApsMesDispatchDTO.ItemReceipt next) {
        receipts.removeIf(item -> Objects.equals(item.getDispatchKey(), next.getDispatchKey()));
        receipts.add(next);
        receipts.sort(Comparator.comparing(PlnApsMesDispatchDTO.ItemReceipt::getDispatchKey));
    }

    private PlnApsMesDispatchDTO.ItemReceipt receipt(PlnApsMesDispatchDTO.PlanItem item,
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
}
