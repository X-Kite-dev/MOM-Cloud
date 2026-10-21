package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.plan.entity.PlanMrpInventoryPeggingEntity;
import com.kitex.mom.plan.mapper.PlanMrpInventoryPeggingMapper;
import com.kitex.mom.plan.service.PlanMrpInventoryPeggingService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class PlanMrpInventoryPeggingServiceImpl
        extends ServiceImpl<PlanMrpInventoryPeggingMapper, PlanMrpInventoryPeggingEntity>
        implements PlanMrpInventoryPeggingService {

    @Override
    public List<PlanMrpInventoryPeggingEntity> listByMrpId(Long mrpId) {
        if (mrpId == null) return List.of();
        return list(new LambdaQueryWrapper<PlanMrpInventoryPeggingEntity>()
                .eq(PlanMrpInventoryPeggingEntity::getMrpId, mrpId)
                .orderByAsc(PlanMrpInventoryPeggingEntity::getAllocationSequence)
                .orderByAsc(PlanMrpInventoryPeggingEntity::getId));
    }

    @Override
    public void markReserved(Long mrpId, String reservationNo) {
        update(new LambdaUpdateWrapper<PlanMrpInventoryPeggingEntity>()
                .eq(PlanMrpInventoryPeggingEntity::getMrpId, mrpId)
                .eq(PlanMrpInventoryPeggingEntity::getStatus, "PLANNED")
                .set(PlanMrpInventoryPeggingEntity::getStatus, "RESERVED")
                .set(PlanMrpInventoryPeggingEntity::getReservationNo, reservationNo)
                .set(PlanMrpInventoryPeggingEntity::getReservationTime, LocalDateTime.now()));
    }

    @Override
    public void markPlanned(Long mrpId) {
        update(new LambdaUpdateWrapper<PlanMrpInventoryPeggingEntity>()
                .eq(PlanMrpInventoryPeggingEntity::getMrpId, mrpId)
                .eq(PlanMrpInventoryPeggingEntity::getStatus, "RESERVED")
                .set(PlanMrpInventoryPeggingEntity::getStatus, "PLANNED")
                .set(PlanMrpInventoryPeggingEntity::getReservationNo, null)
                .set(PlanMrpInventoryPeggingEntity::getReservationTime, null));
    }

    @Override
    public void synchronizeStatus(Long mrpId, String reservationStatus) {
        if (mrpId == null || StrUtil.isBlank(reservationStatus)) return;
        String target = switch (reservationStatus.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "RESERVED";
            case "CONSUMED" -> "CONSUMED";
            case "RELEASED" -> "RELEASED";
            default -> null;
        };
        if (target == null) return;
        update(new LambdaUpdateWrapper<PlanMrpInventoryPeggingEntity>()
                .eq(PlanMrpInventoryPeggingEntity::getMrpId, mrpId)
                .ne(PlanMrpInventoryPeggingEntity::getStatus, target)
                .set(PlanMrpInventoryPeggingEntity::getStatus, target));
    }
}
