package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesTimingExceptionEntity;
import com.kitex.mom.mes.mapper.MesTimingExceptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 回滚后保存真实超时观察。独立事务只写观察，不重试报工、不持原生产行锁、不制造报工事实。
 */
@Service
@RequiredArgsConstructor
public class MesTimingExceptionRecorder {
    private final MesTimingExceptionMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void record(MesTimingExceptionEntity observation) {
        var old = mapper.selectOne(new LambdaQueryWrapper<MesTimingExceptionEntity>()
                .eq(MesTimingExceptionEntity::getTenantId, observation.getTenantId())
                .eq(MesTimingExceptionEntity::getFactoryCode, observation.getFactoryCode())
                .eq(MesTimingExceptionEntity::getReportKind, observation.getReportKind())
                .eq(MesTimingExceptionEntity::getReportRequestId, observation.getReportRequestId()));
        if (old != null) {
            if (!Objects.equals(old.getContextJson(), observation.getContextJson())) {
                throw new CheckedException("原超时请求已绑定不同输入，观察不覆盖");
            }
            return; // 原来源与第一次观察永久保留，后续漂移由审批/消费明确拒绝。
        }
        observation.setId(IdWorker.getId());
        observation.setDelFlag("0");
        observation.setCreateBy(observation.getActorCode());
        observation.setCreateTime(observation.getObservedAt());
        if (mapper.insert(observation) != 1) {
            throw new CheckedException("超时原观察登记未成功");
        }
    }
}
