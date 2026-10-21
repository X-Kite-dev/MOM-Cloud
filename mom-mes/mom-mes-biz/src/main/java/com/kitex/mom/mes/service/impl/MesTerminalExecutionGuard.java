package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTerminalExecutionCommandEntity;
import com.kitex.mom.mes.mapper.MesTerminalExecutionCommandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 跨端未决占用保护，只依赖永久账持久层，避免领域服务循环引用。
 * 仅服务端同一执行词法作用域可通过自己的EXECUTING；没有客户端开启标记。
 */
@Component
@RequiredArgsConstructor
public class MesTerminalExecutionGuard {
    private final MesTerminalExecutionCommandMapper mapper;
    private final ThreadLocal<Permit> current = new ThreadLocal<>();

    /** 在原入口已经取得稳定工单锁/派工行锁之后调用。安全控制暂停不调用此门。 */
    public void assertUnoccupied(MesTaskArrangeEntity arrange) {
        var rows = mapper.pending(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getId());
        if (rows.isEmpty()) return;
        Permit permit = current.get();
        if (rows.size() == 1 && permit != null && "EXECUTING".equals(rows.get(0).getStatus())
                && Objects.equals(rows.get(0).getId(), permit.command().getId())
                && Objects.equals(rows.get(0).getActorId(), permit.command().getActorId())
                && Objects.equals(rows.get(0).getFactoryCode(), permit.command().getFactoryCode())
                && Objects.equals(rows.get(0).getTenantId(), permit.command().getTenantId())
                && Objects.equals(rows.get(0).getArrangeId(), permit.command().getArrangeId())) return;
        throw new CheckedException("该派工存在移动执行未决原号，请查询原回执；不得另号执行或用当前状态销账");
    }

    /** 仅移动执行服务在锁内建立，退出必清理；禁止嵌套或异步传递。 */
    public <T> T execute(MesTerminalExecutionCommandEntity command, AtomicBoolean remoteRisk, Supplier<T> action) {
        if (current.get() != null) throw new CheckedException("移动执行上下文禁止嵌套");
        current.set(new Permit(command, remoteRisk));
        try {
            return action.get();
        } finally {
            current.remove();
        }
    }

    /** 在生产远端写之前标记，即使远端异常/空回执被旧逻辑吞掉也不能判定安全回滚。 */
    public void beforeProductionRemoteWrite() {
        Permit permit = current.get();
        if (permit != null) permit.remoteRisk().set(true);
    }

    /** 用于收紧旧返工回传吞异常行为，仅影响本移动执行事务。 */
    public boolean isMobileExecution() {
        return current.get() != null;
    }

    private record Permit(MesTerminalExecutionCommandEntity command, AtomicBoolean remoteRisk) {
    }
}
