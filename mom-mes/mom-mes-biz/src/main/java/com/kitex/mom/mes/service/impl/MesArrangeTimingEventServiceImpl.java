package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesArrangeTimingEventEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesArrangeTimingEventMapper;
import com.kitex.mom.mes.service.MesArrangeTimingEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.Objects;

/** 同事务原事件只读查询；不能补造历史暂停，也不根据当前工序状态猜测整件停留。 */
@Service
public class MesArrangeTimingEventServiceImpl extends ServiceImpl<MesArrangeTimingEventMapper, MesArrangeTimingEventEntity>
        implements MesArrangeTimingEventService {
    @Override
    public String pauseContext(MesTaskArrangeEntity arrange, LocalDateTime from, LocalDateTime until) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TenantContextHolder.getTenantId(), arrange.getTenantId())) {
            throw new CheckedException("暂停扣除须在实际租户和原报工/复核事务内执行");
        }
        String context = baseMapper.pauseContext(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getId(), from, until);
        if (context == null || context.isBlank()) {
            throw new CheckedException("本派工缺少可证明的暂停计时依据");
        }
        return context;
    }
}
