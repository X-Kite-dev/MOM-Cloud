package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.SchedulingContext;
import com.kitex.mom.plan.service.SchedulingExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
public class PlnSchedulingExecutionService {
    private final PlnSchedulingExecutionMapper executions;
    private final PlnSchedulingExecutionOperationMapper operations;
    private final PlnSchedulingTaskMapper tasks;
    private final PlnIssueMapper issues;
    private final PlnSchedulingRuleSetMapper rules;
    private final PlnSchedulingEngineService engine;
    private final com.kitex.mom.plan.service.selector.RuleSetSelector ruleSelector;
    private final RemoteMdmFactoryAccessService factories;
    private final PlatformTransactionManager transactionManager;
    private final com.kitex.mom.plan.service.solver.SequenceSolverRegistry sequenceSolvers;
    private final PlnSchedulingSequenceProofMapper sequenceProofs;
    private final PlnSchedulingScenarioMapper scenarios;
    private final java.util.concurrent.Semaphore runSlots = new java.util.concurrent.Semaphore(2);

    private static String sha(String input) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    public Map<String, Object> preview(PlnSchedulingExecutionCommand command) {
        require(command != null, "请选择运行参数");
        String factory = factory("read");
        var input = engine.captureRun(command.getRun());
        validateScope(input, factory);
        if (command.getParentExecutionId() != null) validateParent(find(command.getParentExecutionId(), "read"), input);
        String json = json(input);
        return Map.of("input", input, "fingerprint", sha(json));
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') && @pms.hasPermission('plan_plnSchedulingExecution_run')")
    public PlnSchedulingExecution submit(PlnSchedulingExecutionCommand command) {
        validateAction(command);
        String factory = factory("write");
        String commandHash = sha(JSONUtil.toJsonStr(Arrays.asList(actor(), command)));
        var old = executions.selectOne(new LambdaQueryWrapper<PlnSchedulingExecution>().eq(PlnSchedulingExecution::getRequestId, command.getRequestId()));
        if (old != null) {
            require(Objects.equals(old.getCommandFingerprint(), commandHash), "请求号已被不同运行命令使用");
            access(old.getFactoryCode(), "write");
            return old;
        }
        require(command.getTimeoutSeconds() != null && command.getTimeoutSeconds() >= 30 && command.getTimeoutSeconds() <= 1800, "运行时限须为30至1800秒");
        var input = engine.captureRun(command.getRun());
        validateScope(input, factory);
        String snapshot = json(input);
        require(Objects.equals(sha(snapshot), command.getExpectedFingerprint()), "来源、规则、日期或目标已变化，请重新预览");
        PlnSchedulingExecution parent = null;
        if (command.getParentExecutionId() != null) {
            parent = lock(command.getParentExecutionId());
            access(parent.getFactoryCode(), "write");
            validateParent(parent, input);
        }
        var row = new PlnSchedulingExecution();
        row.setId(IdWorker.getId());
        row.setTenantId(tenant());
        row.setFactoryCode(factory);
        row.setRequestId(command.getRequestId());
        row.setCommandFingerprint(commandHash);
        row.setReason(command.getReason());
        row.setApplicantId(actor());
        row.setRootExecutionId(parent == null ? row.getId() : parent.getRootExecutionId());
        row.setParentExecutionId(parent == null ? null : parent.getId());
        row.setVersionNo(parent == null ? 1 : parent.getVersionNo() + 1);
        row.setInputJson(snapshot);
        row.setInputFingerprint(sha(snapshot));
        row.setStatus("PENDING");
        row.setTimeoutSeconds(command.getTimeoutSeconds());
        row.setAttemptCount(0);
        row.setCancelRequested(false);
        require(executions.insert(row) == 1, "运行请求保存失败");
        audit(row, "SUBMIT:" + row.getId(), "SUBMIT", command.getReason(), commandHash, actor());
        return row;
    }

    public Map<String, Object> detail(Long id, long current) {
        var row = find(id, "read");
        var history = operations.selectPage(new Page<>(Math.max(1, current), 20), new LambdaQueryWrapper<PlnSchedulingExecutionOperation>()
                .eq(PlnSchedulingExecutionOperation::getExecutionId, id).orderByDesc(PlnSchedulingExecutionOperation::getId));
        var versions = executions.selectPage(new Page<>(Math.max(1, current), 20), new LambdaQueryWrapper<PlnSchedulingExecution>()
                .select(PlnSchedulingExecution.class, f -> !"input_json".equals(f.getColumn()))
                .eq(PlnSchedulingExecution::getRootExecutionId, row.getRootExecutionId()).orderByDesc(PlnSchedulingExecution::getVersionNo));
        return Map.of("execution", row, "operations", history, "versions", versions, "sequenceProof", row.getResultTaskId() == null ? Map.of("state", "NO_RESULT") : sequenceProof(row.getResultTaskId()),
                "frozenRun", JSONUtil.toBean(row.getInputJson(), PlnSchedulingRunInput.class).getRun());
    }

    public Object solvers() {
        factory("read");
        return sequenceSolvers.catalog();
    }

    public Map<String, Object> previewScenario(PlnSchedulingScenarioRunDTO command) {
        factory("read");
        var input = engine.captureScenario(command);
        return Map.of("input", input, "fingerprint", sha(com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(input)),
                "boundary", "仅冻结明确来源、计划事实、规则及排序器描述；外部工艺/资源状态仍在求解及发布时核验");
    }

    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') && @pms.hasPermission('plan_plnSchedulingExecution_run')")
    public PlnSchedulingScenarioDTO runScenario(PlnSchedulingScenarioRunDTO command) {
        String factory = factory("write");
        require(command != null && (StrUtil.isBlank(command.getFactoryCode()) || factory.equals(command.getFactoryCode())), "多候选运行工厂与当前授权工厂不一致");
        if (StrUtil.isBlank(command.getExpectedFingerprint())) {
            var old = scenarios.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                    .eq(PlnSchedulingScenarioEntity::getTenantId, tenant()).eq(PlnSchedulingScenarioEntity::getFactoryCode, factory)
                    .eq(PlnSchedulingScenarioEntity::getRequestId, StrUtil.trim(command.getRequestId())));
            require(old != null, "新建多候选情景须先预览并提交来源指纹；不能直接扩大周期范围求解");
        } else require(command.getExpectedFingerprint().matches("[a-f0-9]{64}"), "情景来源指纹格式无效");
        return engine.runScenario(command);
    }

    public Map<String, Object> lookupScenario(String requestId) {
        String factory = factory("read");
        require(StrUtil.isNotBlank(requestId) && requestId.trim().length() <= 64, "须提供原情景请求号");
        var row = scenarios.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>().eq(PlnSchedulingScenarioEntity::getTenantId, tenant())
                .eq(PlnSchedulingScenarioEntity::getFactoryCode, factory).eq(PlnSchedulingScenarioEntity::getRequestId, requestId.trim()));
        return row == null ? Map.of("state", "NOT_OBSERVED", "notice", "当前尚未观察到已提交情景，可能仍在运行或已回滚；不能据此认定失败，请保留原命令")
                : Map.of("state", "COMMITTED", "result", engine.getScenarioById(row.getId()));
    }

    public Map<String, Object> scenarioInput(Long id) {
        String factory = factory("read");
        var row = scenarios.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getId, id).eq(PlnSchedulingScenarioEntity::getTenantId, tenant())
                .eq(PlnSchedulingScenarioEntity::getFactoryCode, factory));
        require(row != null, "当前工厂情景不存在");
        if (row.getInputSnapshot() == null)
            return Map.of("state", "NOT_CAPTURED", "notice", "历史或专项情景未留存通用来源快照，请查询原专项证据");
        require(Objects.equals(row.getInputFingerprint(), sha(row.getInputSnapshot())), "情景快照指纹不一致，不能按有效证据展示");
        try {
            var input = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(row.getInputSnapshot(), PlnSchedulingScenarioInput.class);
            return Map.of("state", "RECORDED", "input", input, "fingerprint", row.getInputFingerprint());
        } catch (java.io.IOException ex) {
            throw new CheckedException("情景快照格式不可读取，不能按无证据处理");
        }
    }

    public Map<String, Object> sequenceProof(Long taskId) {
        String factory = factory("read");
        var task = tasks.selectOne(new LambdaQueryWrapper<PlnSchedulingTaskEntity>().eq(PlnSchedulingTaskEntity::getId, taskId)
                .eq(PlnSchedulingTaskEntity::getTenantId, tenant()).eq(PlnSchedulingTaskEntity::getFactoryCode, factory));
        require(task != null, "当前工厂排产结果不存在");
        var proof = sequenceProofs.selectOne(new LambdaQueryWrapper<PlnSchedulingSequenceProof>().eq(PlnSchedulingSequenceProof::getTaskId, taskId)
                .eq(PlnSchedulingSequenceProof::getTenantId, tenant()).eq(PlnSchedulingSequenceProof::getFactoryCode, factory));
        return proof == null ? Map.of("state", "HISTORICAL_NOT_CAPTURED") : Map.of("state", "RECORDED", "proof", proof);
    }

    public Page<PlnSchedulingExecution> page(long current, String status) {
        String factory = factory("read");
        return executions.selectPage(new Page<>(Math.max(1, current), 20), new LambdaQueryWrapper<PlnSchedulingExecution>()
                .select(PlnSchedulingExecution.class, f -> !"input_json".equals(f.getColumn()))
                .eq(PlnSchedulingExecution::getFactoryCode, factory).eq(StrUtil.isNotBlank(status), PlnSchedulingExecution::getStatus, status)
                .orderByDesc(PlnSchedulingExecution::getId));
    }

    /**
     * 请求领取单独提交；结果任务和SUCCEEDED回执在同一求解事务提交，失败尝试另留账。
     */
    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') && @pms.hasPermission('plan_plnSchedulingExecution_run')")
    public PlnSchedulingExecution execute(Long id, PlnSchedulingExecutionCommand command) {
        validateAction(command);
        find(id, "write");
        require(runSlots.tryAcquire(), "当前实例已有两项求解运行，请稍后执行；请求仍保留且未增加尝试次数");
        try {
            return executeWithinSlot(id, command);
        } finally {
            runSlots.release();
        }
    }

    private PlnSchedulingExecution executeWithinSlot(Long id, PlnSchedulingExecutionCommand command) {
        Long operator = actor();
        String hash = actionHash(id, "EXECUTE", command, operator);
        var claim = tx().execute(status -> {
            var row = lock(id);
            if (replay(row, command, hash)) return null;
            require(!Set.of("SUCCEEDED", "CANCELLED").contains(row.getStatus()) && !Boolean.TRUE.equals(row.getCancelRequested()), "运行已完成或取消，不能重写结果");
            LocalDateTime now = LocalDateTime.now();
            require(!"RUNNING".equals(row.getStatus()) || !row.getLeaseUntil().isAfter(now), "已有运行持有效租约，请查询进度，不要重复执行");
            require(row.getAttemptCount() < 5, "同一冻结版本最多5次尝试；请核实后预览新版本，不覆盖旧证据");
            if ("RUNNING".equals(row.getStatus()))
                audit(row, "RECLAIM:" + row.getLeaseToken(), "RECLAIM", "旧租约已过期；旧执行者将被提交令牌校验拒绝", hash, operator);
            row.setStatus("RUNNING");
            row.setAttemptCount(row.getAttemptCount() + 1);
            row.setLeaseToken(UUID.randomUUID().toString());
            row.setStartedTime(now);
            row.setDeadline(now.plusSeconds(row.getTimeoutSeconds()));
            row.setLeaseUntil(now.plusMinutes(2));
            row.setFinishedTime(null);
            row.setLastError(null);
            save(row);
            audit(row, command.getRequestId(), "CLAIM", command.getReason(), hash, operator);
            return row;
        });
        if (claim == null) return find(id, "read");
        try {
            tx().executeWithoutResult(status -> {
                require(Objects.equals(sha(claim.getInputJson()), claim.getInputFingerprint()), "冻结输入已损坏");
                var input = JSONUtil.toBean(claim.getInputJson(), PlnSchedulingRunInput.class);
                require(StrUtil.isNotBlank(input.getSequenceSolverSpec()) && input.getSequenceSolverSpec().equals(sequenceSolvers.specificationJson(input.getRun().getSequenceSolverCode())),
                        "原运行缺少排序求解器版本或实现已变化，请预览新版本；不能沿旧版本静默换算法");
                require(Objects.equals(input.getCapturedDate(), LocalDate.now()), "已经跨过原求解基线日期，请重新预览新版本");
                issues.selectList(new LambdaQueryWrapper<PlnIssueEntity>().in(PlnIssueEntity::getId, input.getRun().getTargetIds())
                        .orderByAsc(PlnIssueEntity::getId).last("FOR UPDATE"));
                require(rules.selectOne(new LambdaQueryWrapper<PlnSchedulingRuleSetEntity>().eq(PlnSchedulingRuleSetEntity::getId, input.getRule().getId()).last("FOR UPDATE")) != null, "原规则不存在");
                var actual = engine.captureRun(input.getRun());
                validateScope(actual, claim.getFactoryCode());
                require(Objects.equals(sha(json(actual)), claim.getInputFingerprint()), "计划事实或选中规则已变化，不能用新事实覆盖原运行版本");
                long[] nextCheck = {0};
                Runnable checkpoint = () -> {
                    long now = System.nanoTime();
                    if (now < nextCheck[0]) return;
                    nextCheck[0] = now + 500_000_000L;
                    heartbeat(claim);
                };
                try {
                    SchedulingExecutionContext.install(id, actual.getRule(), checkpoint);
                    SchedulingExecutionContext.check();
                    require(Boolean.TRUE.equals(engine.run(actual.getRun())), "排产引擎未生成结果");
                    require(actual.getSequenceSolverSpec().equals(sequenceSolvers.specificationJson(actual.getRun().getSequenceSolverCode())), "运行期间排序求解器描述变化，不能提交混合版本结果");
                    heartbeat(claim);
                    require(Objects.equals(sha(JSONUtil.toJsonStr(actual.getRule())), sha(JSONUtil.toJsonStr(ruleSelector.selectActiveRule()))), "求解期间选中规则已变化，禁止发布旧输入结果");
                    var result = tasks.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>().eq(PlnSchedulingTaskEntity::getExecutionId, id));
                    require(result.size() == 1, "受控运行必须原子绑定且只绑定一个结果任务");
                    require(Objects.equals(result.get(0).getTenantId(), claim.getTenantId()) && Objects.equals(result.get(0).getFactoryCode(), claim.getFactoryCode())
                            && Set.of("SIMULATED", "WAIT_CONFIRM").contains(result.get(0).getTaskStatus()), "求解结果工厂或业务状态不一致");
                    var row = lock(id);
                    verifyLease(row, claim);
                    require(!Boolean.TRUE.equals(row.getCancelRequested()), "运行已请求取消");
                    require(LocalDateTime.now().isBefore(row.getDeadline()), "运行已超时，禁止提交晚到结果");
                    row.setStatus("SUCCEEDED");
                    row.setResultTaskId(result.get(0).getId());
                    row.setWarning(SchedulingContext.getAndClearWarning());
                    row.setFinishedTime(LocalDateTime.now());
                    row.setLeaseUntil(null);
                    row.setLeaseToken(null);
                    save(row);
                    audit(row, "RESULT:" + claim.getLeaseToken(), "RESULT", "求解结果及运行回执原子提交；正式方案仍须另行确认锁定", hash, operator);
                } finally {
                    SchedulingExecutionContext.clear();
                    SchedulingContext.clear();
                }
            });
        } catch (Exception ex) {
            tx().executeWithoutResult(status -> {
                var row = lock(id);
                if (!"RUNNING".equals(row.getStatus()) || !Objects.equals(row.getLeaseToken(), claim.getLeaseToken()))
                    return;
                String state = Boolean.TRUE.equals(row.getCancelRequested()) ? "CANCELLED" : !LocalDateTime.now().isBefore(row.getDeadline()) ? "TIMED_OUT" : "FAILED";
                row.setStatus(state);
                row.setLastError(error(ex));
                row.setFinishedTime(LocalDateTime.now());
                row.setLeaseUntil(null);
                row.setLeaseToken(null);
                save(row);
                audit(row, "FAIL:" + claim.getLeaseToken(), state, row.getLastError(), hash, operator);
            });
        }
        return find(id, "read");
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') && @pms.hasPermission('plan_plnSchedulingExecution_run')")
    public PlnSchedulingExecution cancel(Long id, PlnSchedulingExecutionCommand command) {
        validateAction(command);
        var first = find(id, "write");
        if (!Objects.equals(first.getApplicantId(), actor())) access(first.getFactoryCode(), "admin");
        var row = lock(id);
        String hash = actionHash(id, "CANCEL", command, actor());
        if (replay(row, command, hash)) return row;
        require(!"SUCCEEDED".equals(row.getStatus()), "求解结果已经提交，请通过结果生命周期取消，不撤销已生成任务");
        require(!"CANCELLED".equals(row.getStatus()), "运行已取消");
        row.setCancelRequested(true);
        if (!"RUNNING".equals(row.getStatus()) || !row.getLeaseUntil().isAfter(LocalDateTime.now())) {
            row.setStatus("CANCELLED");
            row.setFinishedTime(LocalDateTime.now());
            row.setLeaseUntil(null);
            row.setLeaseToken(null);
        }
        save(row);
        audit(row, command.getRequestId(), "CANCEL_REQUEST", command.getReason(), hash, actor());
        return row;
    }

    private void heartbeat(PlnSchedulingExecution claim) {
        tx().executeWithoutResult(status -> {
            var row = lock(claim.getId());
            verifyLease(row, claim);
            require(!Boolean.TRUE.equals(row.getCancelRequested()), "运行已请求取消");
            require(LocalDateTime.now().isBefore(row.getDeadline()), "运行时限已到");
            if (row.getLeaseUntil().isBefore(LocalDateTime.now().plusMinutes(1))) {
                row.setLeaseUntil(LocalDateTime.now().plusMinutes(2));
                save(row);
            }
        });
    }

    private void verifyLease(PlnSchedulingExecution row, PlnSchedulingExecution claim) {
        require("RUNNING".equals(row.getStatus()) && Objects.equals(row.getLeaseToken(), claim.getLeaseToken()), "运行租约已被取消或接管，旧工作者不得提交");
    }

    private void validateParent(PlnSchedulingExecution parent, PlnSchedulingRunInput input) {
        require(Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(parent.getStatus()), "父版本仍未终止");
        var old = JSONUtil.toBean(parent.getInputJson(), PlnSchedulingRunInput.class);
        require(Objects.equals(old.getRun().getTargetIds(), input.getRun().getTargetIds()), "后继版本须保持相同来源ID集合；换范围请另建根运行");
        require(executions.selectCount(new LambdaQueryWrapper<PlnSchedulingExecution>().eq(PlnSchedulingExecution::getParentExecutionId, parent.getId())) == 0, "已有后继版本，请从最新版本继续，不覆盖或分叉版本链");
    }

    private void validateScope(PlnSchedulingRunInput input, String factory) {
        require(input.getSources().stream().allMatch(p -> Objects.equals(p.getTenantId(), tenant()) && Objects.equals(p.getFactoryCode(), factory)), "来源计划须全部属于当前实际工厂和租户");
        require(Objects.equals(input.getRule().getTenantId(), tenant()) && (StrUtil.isBlank(input.getRule().getFactoryCode()) || Objects.equals(input.getRule().getFactoryCode(), factory)), "规则不在当前工厂可用范围");
    }

    private boolean replay(PlnSchedulingExecution row, PlnSchedulingExecutionCommand command, String hash) {
        var old = operations.selectOne(new LambdaQueryWrapper<PlnSchedulingExecutionOperation>().eq(PlnSchedulingExecutionOperation::getRequestId, command.getRequestId()));
        if (old == null) return false;
        require(Objects.equals(old.getExecutionId(), row.getId()) && Objects.equals(old.getCommandFingerprint(), hash), "请求号已被不同运行操作占用");
        return true;
    }

    private PlnSchedulingExecution find(Long id, String permission) {
        tenant();
        var row = id == null ? null : executions.selectById(id);
        require(row != null, "运行请求不存在");
        access(row.getFactoryCode(), permission);
        return row;
    }

    private PlnSchedulingExecution lock(Long id) {
        // 非键更新锁与结果任务外键的KEY SHARE兼容，防止检查点独立事务被自身求解事务阻塞。
        var row = executions.selectOne(new LambdaQueryWrapper<PlnSchedulingExecution>().eq(PlnSchedulingExecution::getId, id).last("FOR NO KEY UPDATE"));
        require(row != null, "运行请求不存在");
        return row;
    }

    private void save(PlnSchedulingExecution row) {
        // 只更新非键状态列：全实体update会把tenant/factory唯一键放入SET，导致PG升级排他锁，
        // 被当前求解事务结果任务的外键KEY SHARE阻塞，取消/心跳不能因此自锁。
        var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnSchedulingExecution>()
                .eq(PlnSchedulingExecution::getId, row.getId()).eq(PlnSchedulingExecution::getTenantId, row.getTenantId())
                .set(PlnSchedulingExecution::getStatus, row.getStatus()).set(PlnSchedulingExecution::getAttemptCount, row.getAttemptCount())
                .set(PlnSchedulingExecution::getCancelRequested, row.getCancelRequested()).set(PlnSchedulingExecution::getLeaseToken, row.getLeaseToken())
                .set(PlnSchedulingExecution::getLeaseUntil, row.getLeaseUntil()).set(PlnSchedulingExecution::getDeadline, row.getDeadline())
                .set(PlnSchedulingExecution::getStartedTime, row.getStartedTime()).set(PlnSchedulingExecution::getFinishedTime, row.getFinishedTime())
                .set(PlnSchedulingExecution::getLastError, row.getLastError()).set(PlnSchedulingExecution::getResultTaskId, row.getResultTaskId())
                .set(PlnSchedulingExecution::getWarning, row.getWarning());
        require(executions.update(null, update) == 1, "运行状态保存失败");
    }

    private void audit(PlnSchedulingExecution row, String key, String action, String reason, String hash, Long actor) {
        var op = new PlnSchedulingExecutionOperation();
        op.setTenantId(row.getTenantId());
        op.setFactoryCode(row.getFactoryCode());
        op.setExecutionId(row.getId());
        op.setRequestId(key);
        op.setCommandFingerprint(hash);
        op.setAction(action);
        op.setReason(reason.substring(0, Math.min(reason.length(), 1000)));
        op.setOperatorId(actor);
        op.setAttemptNo(row.getAttemptCount());
        op.setLeaseToken(row.getLeaseToken());
        op.setResultStatus(row.getStatus());
        op.setResultTaskId(row.getResultTaskId());
        op.setOperationTime(LocalDateTime.now());
        require(operations.insert(op) == 1, "运行尝试审计保存失败");
    }

    private TransactionTemplate tx() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private void validateAction(PlnSchedulingExecutionCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 64 && StrUtil.isNotBlank(command.getReason()) && command.getReason().length() <= 1000, "须提供永久请求号及1至1000字原因");
    }

    private String actionHash(Long id, String action, PlnSchedulingExecutionCommand command, Long actor) {
        return sha(JSONUtil.toJsonStr(Arrays.asList(id, action, actor, command)));
    }

    private String json(Object input) {
        String text = JSONUtil.toJsonStr(input);
        require(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "冻结输入超过2MiB，请按明确范围拆分，不截断事实");
        return text;
    }

    private Long tenant() {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null, "受控排产须登录且不能跳过租户/工厂隔离");
        return TenantContextHolder.getTenantId();
    }

    private Long actor() {
        tenant();
        return SecurityUtils.getUser().getId();
    }

    private String factory(String permission) {
        String factory = FactoryContextHolder.getFactoryCode();
        access(factory, permission);
        return factory;
    }

    private void access(String factory, String permission) {
        tenant();
        require(StrUtil.isNotBlank(factory) && Objects.equals(factory, FactoryContextHolder.getFactoryCode()), "请选择运行所属实际工厂");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(actor()).setFactoryCode(factory).setRequiredPermission(permission));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "缺少实际工厂权限或MDM不可核验");
    }

    private String error(Throwable ex) {
        while (ex.getCause() != null && ex.getCause() != ex) ex = ex.getCause();
        String message = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        return message.substring(0, Math.min(message.length(), 1000));
    }
}
