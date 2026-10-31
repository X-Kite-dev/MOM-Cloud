package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesExecutionCommandCancellationEntity;
import com.kitex.mom.mes.mapper.MesExecutionCommandCancellationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * 原域持有同一请求锁后使用的永久取消保护；不另取锁，不开启独立写事务。
 */
@Service
@RequiredArgsConstructor
public class MesExecutionCommandCancellationService {
    private final MesActualFactoryAccessService access;
    private final MesExecutionCommandCancellationMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void assertActive(String kind, String requestId, String requestHash) {
        var scope = context("write");
        identity(kind, requestId);
        hash(requestHash);
        // 墓碑不按actor/hash过滤：同号迟到POST不能通过换人或换内容重新激活。
        require(mapper.findOriginal(scope.tenant(), scope.factory(), kind, requestId) == null,
                "原请求号已取消，禁止再次执行；请核对原回执后发起新操作");
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void cancel(String kind, String requestId, String requestHash) {
        var scope = context("write");
        identity(kind, requestId);
        hash(requestHash);
        var original = mapper.findOriginal(scope.tenant(), scope.factory(), kind, requestId);
        if (original != null) {
            checked(original, scope, kind, requestId);
            require(Objects.equals(original.getActorId(), scope.actor()) && Objects.equals(original.getRequestHash(), requestHash),
                    "原号取消已属于其他操作者或不同原内容，不能覆盖");
            return;
        }
        var row = new MesExecutionCommandCancellationEntity();
        row.setId(IdWorker.getId());
        row.setTenantId(scope.tenant());
        row.setFactoryCode(scope.factory());
        row.setCommandKind(kind);
        row.setRequestId(requestId);
        row.setRequestHash(requestHash);
        row.setActorId(scope.actor());
        row.setCreateBy(scope.actorCode());
        row.setCreateTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        row.setDelFlag("0");
        require(mapper.insert(row) == 1, "原号取消记录未保存，不能释放原请求");
        var persisted = mapper.findOriginal(scope.tenant(), scope.factory(), kind, requestId);
        checked(persisted, scope, kind, requestId);
        require(Objects.equals(persisted.getId(), row.getId()) && Objects.equals(persisted.getActorId(), row.getActorId())
                        && Objects.equals(persisted.getRequestHash(), row.getRequestHash())
                        && Objects.equals(persisted.getCreateBy(), row.getCreateBy()) && Objects.equals(persisted.getCreateTime(), row.getCreateTime()),
                "原号取消原文或真实归属回读不一致，不能释放原请求");
    }

    @Transactional(readOnly = true)
    public boolean cancelled(String kind, String requestId) {
        var scope = context("read");
        identity(kind, requestId);
        var original = mapper.findOriginal(scope.tenant(), scope.factory(), kind, requestId);
        if (original == null) {
            return false;
        }
        checked(original, scope, kind, requestId);
        return Objects.equals(original.getActorId(), scope.actor());
    }

    private Context context(String level) {
        String factory = access.require(level);
        Long tenant = TenantContextHolder.getTenantId();
        var user = SecurityUtils.getUser();
        require(tenant != null && tenant > 0 && user != null && user.getId() != null && user.getId() > 0
                        && factory != null && !factory.isBlank() && factory.length() <= 100,
                "原号取消缺少实际租户、工厂或认证操作者");
        return new Context(tenant, factory, user.getId(), user.getUsername());
    }

    private void checked(MesExecutionCommandCancellationEntity row, Context scope, String kind, String requestId) {
        require(row != null && row.getId() != null && row.getId() > 0 && Objects.equals(row.getTenantId(), scope.tenant())
                        && Objects.equals(row.getFactoryCode(), scope.factory()) && Objects.equals(row.getCommandKind(), kind)
                        && Objects.equals(row.getRequestId(), requestId) && row.getActorId() != null && row.getActorId() > 0
                        && row.getCreateTime() != null && "0".equals(row.getDelFlag()),
                "原号取消记录归属或永久状态不可核验");
        hash(row.getRequestHash());
    }

    private void identity(String kind, String requestId) {
        require(kind != null && Set.of("WORKPIECE_TIME", "PROCESS_COMPOSITION", "DISPATCH_CORRECTION", "DISPATCH_HISTORY_NOTE", "START_MATERIAL_BINDING", "MATERIAL_BINDING_SUPPLEMENT").contains(kind), "不支持的原号取消命令类型");
        require(requestId != null && requestId.matches("[A-Za-z0-9_.:-]{8,100}"), "须提供8至100位稳定原请求号，不得改写或截断");
    }

    private void hash(String requestHash) {
        require(requestHash != null && requestHash.matches("[0-9a-f]{64}"), "原请求内容SHA256缺失或不精确");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private record Context(Long tenant, String factory, Long actor, String actorCode) {
    }
}
