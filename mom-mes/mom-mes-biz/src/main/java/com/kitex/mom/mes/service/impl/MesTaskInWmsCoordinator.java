package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesTaskInWmsDTO;
import com.kitex.mom.mes.dto.MesTaskInWmsReceiptDTO;
import com.kitex.mom.mes.service.MesTaskInWmsCommandService;
import com.kitex.mom.mes.service.MesTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 登记提交后才调用原入库事务；事务失败已退出后才能判定是否从未开始远端发送。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mesTask:update')")
public class MesTaskInWmsCoordinator {
    private final MesTaskInWmsCommandService commands;
    private final MesTaskService tasks;

    @Transactional(propagation = Propagation.NEVER)
    public MesTaskInWmsReceiptDTO execute(MesTaskInWmsDTO command) {
        var original = commands.register(command);
        if (!"CANCELLED_BEFORE_DISPATCH".equals(original.getStatus())) {
            try {
                tasks.inWms(command);
            } catch (RuntimeException failure) {
                try {
                    commands.cancelIfUnsent(command);
                } catch (RuntimeException cancellationFailure) {
                    failure.addSuppressed(cancellationFailure);
                    throw failure;
                }
                // 未发送的确定终态可安全回显；曾发送则保留原异常和UNKNOWN，不伪造回滚成功。
                var result = commands.original(command);
                if (result == null || !"CANCELLED_BEFORE_DISPATCH".equals(result.getStatus())) {
                    throw failure;
                }
            }
        }
        return tasks.resolveInWms(command);
    }

    /** 显式结束原号；登记、同锁取消和只读核账均独立提交，晚到执行不能重开。 */
    @Transactional(propagation = Propagation.NEVER)
    public MesTaskInWmsReceiptDTO cancelUnsent(MesTaskInWmsDTO command) {
        commands.register(command);
        commands.cancelIfUnsent(command);
        return tasks.resolveInWms(command);
    }
}
