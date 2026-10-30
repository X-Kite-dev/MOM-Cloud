package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批的独立来源核验事务。不写生产事实、不取另一工单锁；调用方已持目标工单和许可锁。
 * SQL来源拒绝会使PG当前事务失败，必须隔离该读取，外层才可永久记录DECLINED而非留下UNKNOWN。
 * 核验通过不是消费资格，实际报工仍在原生产事务重新核全来源。
 */
@Service
@RequiredArgsConstructor
public class MesTimingExceptionSourceReader {
    private final MesProcessTimingGuard timing;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public MesProcessTimingGuard.Decision inspect(MesTaskArrangeEntity arrange, String mode, String identifier,
                                                  String originalDependency) {
        return timing.inspect(arrange, ReportModeEnum.valueOf(mode), identifier, originalDependency);
    }
}
