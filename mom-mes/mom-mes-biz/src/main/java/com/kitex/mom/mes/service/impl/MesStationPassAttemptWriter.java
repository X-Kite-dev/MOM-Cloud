package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.mom.mes.entity.MesStationPassRecordEntity;
import com.kitex.mom.mes.mapper.MesStationPassRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把防错拒绝事实留在独立事务中，业务事务回滚也不会抹掉现场异常。
 */
@Service
@RequiredArgsConstructor
public class MesStationPassAttemptWriter {
    private final MesStationPassRecordMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveRejected(MesStationPassRecordEntity record) {
        MesStationPassRecordEntity existing = mapper.selectOne(new LambdaQueryWrapper<MesStationPassRecordEntity>()
                .eq(MesStationPassRecordEntity::getRequestId, record.getRequestId()));
        if (existing != null) {
            return;
        }
        record.setId(IdWorker.getId());
        mapper.insert(record);
    }
}
