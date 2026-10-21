package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.plan.dto.PlnSchedulingLifecycleCommand;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 取消仅终止未发布候选；归档只是管理标签。均不删除排程、解锁资源或撤回MES。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
public class PlnSchedulingLifecycleService {
    private final PlnSchedulingTaskMapper tasks;
    private final PlnSchedulingScenarioMapper scenarios;
    private final PlnSchedulingLifecycleOperationMapper operations;
    private final PlnSchedulingLifecycleEvidenceMapper evidenceMapper;
    private final PlnShortageReplanCaseMapper shortages;
    private final PlnQualityReplanCaseMapper qualities;
    private final PlnResourceReplanCaseMapper resources;
    private final PlnInsertionReplanCaseMapper insertions;
    private final RemoteMdmFactoryAccessService factories;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public Map<String, Object> preview(Long taskId, long page) {
        var bundle = bundle(taskId, false, "read");
        String snapshot = snapshot(bundle);
        var history = operations.selectPage(new Page<>(Math.max(1, page), 20), new LambdaQueryWrapper<PlnSchedulingLifecycleOperation>()
                .select(PlnSchedulingLifecycleOperation.class, f -> !Set.of("before_snapshot", "after_snapshot").contains(f.getColumn()))
                .eq(PlnSchedulingLifecycleOperation::getScopeKey, bundle.scopeKey()).orderByDesc(PlnSchedulingLifecycleOperation::getId));
        Map<String, String> allowed = new LinkedHashMap<>();
        for (String action : List.of("CANCEL", "ARCHIVE", "RESTORE")) allowed.put(action, blocked(bundle, action));
        return Map.of("scopeKey", bundle.scopeKey(), "tasks", bundle.tasks(), "snapshot", snapshot,
                "fingerprint", sha256(snapshot), "actions", allowed, "operations", history);
    }

    public PlnSchedulingLifecycleOperation operation(Long id) {
        tenant();
        var row = operations.selectById(id);
        require(row != null, "生命周期操作不存在");
        access(row.getFactoryCode(), "read");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') && @pms.hasPermission('plan_plnSchedulingLifecycle_manage')")
    public Map<String, Object> apply(Long taskId, PlnSchedulingLifecycleCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().length() <= 64
                && StrUtil.isNotBlank(command.getReason()) && command.getReason().length() <= 1000
                && command.getExpectedFingerprint() != null && command.getExpectedFingerprint().length() == 64
                && Set.of("CANCEL", "ARCHIVE", "RESTORE").contains(StrUtil.blankToDefault(command.getAction(), "")), "缺少有效操作、永久请求号、预览指纹或原因");
        var bundle = bundle(taskId, true, "admin");
        Long actor = SecurityUtils.getUser().getId();
        String commandHash = sha256(JSONUtil.toJsonStr(Arrays.asList(taskId, actor, command)));
        var old = operations.selectOne(new LambdaQueryWrapper<PlnSchedulingLifecycleOperation>()
                .eq(PlnSchedulingLifecycleOperation::getRequestId, command.getRequestId()));
        if (old != null) {
            require(Objects.equals(old.getCommandFingerprint(), commandHash) && Objects.equals(old.getScopeKey(), bundle.scopeKey()), "永久请求号已被不同操作、原因、预览或操作者使用");
            return Map.of("replayed", true, "operation", old);
        }
        String before = snapshot(bundle);
        require(Objects.equals(sha256(before), command.getExpectedFingerprint()), "任务、候选或资源证据已变化，请重新预览");
        String block = blocked(bundle, command.getAction());
        require(block.isEmpty(), block);
        if ("CANCEL".equals(command.getAction()) && bundle.scenario() != null) {
            bundle.scenario().setScenarioStatus("CANCELLED");
            require(scenarios.updateById(bundle.scenario()) == 1, "情景取消失败");
        }
        for (var task : bundle.tasks()) {
            if ("CANCEL".equals(command.getAction())) task.setTaskStatus("CANCELLED");
            else task.setResultArchived("ARCHIVE".equals(command.getAction()));
            task.setLifecycleVersion(Objects.requireNonNullElse(task.getLifecycleVersion(), 0) + 1);
            require(tasks.updateById(task) == 1, "任务生命周期更新失败");
        }
        var op = new PlnSchedulingLifecycleOperation();
        op.setTenantId(tenant());
        op.setFactoryCode(bundle.factory());
        op.setTaskId(taskId);
        op.setScopeKey(bundle.scopeKey());
        op.setRequestId(command.getRequestId());
        op.setCommandFingerprint(commandHash);
        op.setAction(command.getAction());
        op.setReason(command.getReason());
        op.setOperatorId(actor);
        op.setOperationTime(LocalDateTime.now());
        op.setBeforeSnapshot(before);
        op.setAfterSnapshot(snapshot(bundle(taskId, false, "admin")));
        require(operations.insert(op) == 1, "生命周期证据保存失败");
        return Map.of("replayed", false, "operation", op);
    }

    private Bundle bundle(Long id, boolean lock, String permission) {
        tenant();
        require(id != null, "请选择排产任务");
        var first = tasks.selectById(id);
        require(first != null, "排产任务不存在");
        access(first.getFactoryCode(), permission);
        PlnSchedulingScenarioEntity scenario = null;
        if (first.getScenarioId() != null) {
            var query = new LambdaQueryWrapper<PlnSchedulingScenarioEntity>().eq(PlnSchedulingScenarioEntity::getId, first.getScenarioId());
            if (lock) query.last("FOR UPDATE");
            scenario = scenarios.selectOne(query);
            require(scenario != null && Objects.equals(first.getFactoryCode(), scenario.getFactoryCode()), "任务情景或工厂归属不完整");
        }
        var query = new LambdaQueryWrapper<PlnSchedulingTaskEntity>();
        if (scenario == null) query.eq(PlnSchedulingTaskEntity::getId, id);
        else query.eq(PlnSchedulingTaskEntity::getScenarioId, scenario.getId());
        query.orderByAsc(PlnSchedulingTaskEntity::getId);
        if (lock) query.last("FOR UPDATE");
        var group = tasks.selectList(query);
        require(!group.isEmpty() && group.size() <= 5 && group.stream().anyMatch(t -> Objects.equals(t.getId(), id)), "候选任务集合不完整或超出情景定义");
        if (scenario != null)
            require(Objects.equals(group.size(), scenario.getCandidateCount()), "情景候选数量不完整，禁止部分操作");
        for (var task : group)
            require(Objects.equals(task.getTenantId(), tenant()) && Objects.equals(task.getFactoryCode(), first.getFactoryCode())
                    && Objects.equals(task.getScenarioId(), first.getScenarioId()), "候选集合跨租户、工厂或情景");
        Map<String, Object> stats = new LinkedHashMap<>();
        for (var task : group)
            stats.put(task.getId().toString(), evidenceMapper.evidence(task.getId(), tenant(), first.getFactoryCode()));
        return new Bundle(group, scenario, first.getFactoryCode(), scenario == null ? "TASK:" + id : "SCENARIO:" + scenario.getId(), stats);
    }

    private String snapshot(Bundle bundle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeKey", bundle.scopeKey());
        body.put("factory", bundle.factory());
        body.put("tasks", bundle.tasks());
        body.put("scenario", bundle.scenario());
        body.put("structure", bundle.stats());
        String result = JSONUtil.toJsonStr(body);
        require(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "生命周期证据超过2MiB，请由专项治理处理，不截断证据");
        return result;
    }

    private String blocked(Bundle bundle, String action) {
        boolean allArchived = bundle.tasks().stream().allMatch(t -> Boolean.TRUE.equals(t.getResultArchived()));
        boolean anyArchived = bundle.tasks().stream().anyMatch(t -> Boolean.TRUE.equals(t.getResultArchived()));
        if (anyArchived != allArchived) return "同情景归档状态不一致，需要治理，不能只修改一个候选";
        if ("RESTORE".equals(action)) return allArchived ? "" : "任务尚未归档";
        if (anyArchived) return "已归档；请先恢复展示，不会恢复已取消的业务状态";
        for (var task : bundle.tasks()) {
            String status = StrUtil.blankToDefault(task.getTaskStatus(), "");
            boolean oldSimulation = "simulate".equalsIgnoreCase(task.getMode()) && "PENDING".equals(status);
            if ("RUNNING".equals(status) || ("PENDING".equals(status) && !oldSimulation))
                return "运行中或未形成结果，不能以取消/归档伪装已终止求解";
            if ("CANCEL".equals(action) && !Set.of("SIMULATED", "WAIT_CONFIRM", "FAILED", "SUPERSEDED").contains(status) && !oldSimulation)
                return "仅未发布结果可取消；成功、部分锁定或已取消任务须按原执行流程处理";
            if ("ARCHIVE".equals(action) && !Set.of("SIMULATED", "SUCCESS", "PARTIAL", "FAILED", "SUPERSEDED", "CANCELLED").contains(status) && !oldSimulation)
                return "仍可发布的任务不能直接归档，请先受控取消或完成发布";
        }
        if ("CANCEL".equals(action)) {
            if (bundle.scenario() != null && !Set.of("EVALUATED", "SELECTED", "FAILED", "EXPIRED").contains(bundle.scenario().getScenarioStatus()))
                return "仅未发布情景可以整组取消";
            for (var task : bundle.tasks()) {
                if (hasSpecializedOwner(task))
                    return "任务属于缺料/质量/资源/插单修复案件，须在原案件取消/回退，不能跳过业务编排";
                for (var stat : evidenceMapper.evidence(task.getId(), tenant(), bundle.factory())) {
                    if (((Number) stat.get("locked")).longValue() > 0)
                        return "任务仍有已锁定计划、工序或批次，禁止用取消代替资源释放/生产撤回";
                    if (((Number) stat.get("invalid")).longValue() > 0) return "任务明细存在归属异常，禁止跨归属操作";
                }
            }
        }
        return "";
    }

    private boolean hasSpecializedOwner(PlnSchedulingTaskEntity task) {
        Long id = task.getId(), scenarioId = task.getScenarioId();
        return shortages.selectCount(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .and(q -> q.eq(PlnShortageReplanCaseEntity::getProposalTaskId, id).or().eq(PlnShortageReplanCaseEntity::getOriginalTaskId, id)
                        .or(scenarioId != null).eq(scenarioId != null, PlnShortageReplanCaseEntity::getScenarioId, scenarioId))) > 0
                || qualities.selectCount(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .and(q -> q.eq(PlnQualityReplanCaseEntity::getProposalTaskId, id).or().eq(PlnQualityReplanCaseEntity::getOriginalTaskId, id)
                        .or(scenarioId != null).eq(scenarioId != null, PlnQualityReplanCaseEntity::getScenarioId, scenarioId))) > 0
                || resources.selectCount(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .and(q -> q.eq(PlnResourceReplanCaseEntity::getProposalTaskId, id).or().eq(PlnResourceReplanCaseEntity::getOriginalTaskId, id)
                        .or(scenarioId != null).eq(scenarioId != null, PlnResourceReplanCaseEntity::getScenarioId, scenarioId))) > 0
                || insertions.selectCount(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .and(q -> q.eq(PlnInsertionReplanCaseEntity::getProposalTaskId, id).or().eq(PlnInsertionReplanCaseEntity::getPublishedTaskId, id)
                        .or(scenarioId != null).eq(scenarioId != null, PlnInsertionReplanCaseEntity::getScenarioId, scenarioId))) > 0;
    }

    private Long tenant() {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && SecurityUtils.getUser() != null
                && SecurityUtils.getUser().getId() != null, "任务治理须登录且不得跳过租户/工厂隔离");
        return TenantContextHolder.getTenantId();
    }

    private void access(String factory, String permission) {
        tenant();
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(current) || Objects.equals(current, factory)), "请选择任务实际工厂，历史空归属任务须先治理");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(SecurityUtils.getUser().getId()).setFactoryCode(factory).setRequiredPermission(permission));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "缺少任务实际工厂权限或MDM无法核验");
    }

    private record Bundle(List<PlnSchedulingTaskEntity> tasks, PlnSchedulingScenarioEntity scenario, String factory,
                          String scopeKey, Map<String, Object> stats) {
    }
}
