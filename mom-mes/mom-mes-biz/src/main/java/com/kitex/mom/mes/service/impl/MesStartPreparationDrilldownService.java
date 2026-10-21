package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 开工准备跳转原业务列表时的精确身份核验。
 *
 * <p>路由参数不是授权。先在当前真实租户、工厂中重新读取派工，再核对其编号和父工单；
 * 目标列表只能用该真实身份等值过滤，不能把 PG001 模糊扩成 PG0011。</p>
 */
@Service
@RequiredArgsConstructor
public class MesStartPreparationDrilldownService {

    private final MesTaskArrangeMapper arrangeMapper;
    private final MesActualFactoryAccessService factoryAccess;

    /** 只读取身份，不获取生产锁、不改变派工或形成开工事实。 */
    public MesTaskArrangeEntity require(Long arrangeId, String arrangeCode, String taskCode) {
        String factory = factoryAccess.require("read");
        if (arrangeId == null || arrangeId <= 0) {
            throw new CheckedException("开工准备下钻缺少真实派工ID");
        }
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, arrangeId)
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory));
        if (arrange == null || !Objects.equals(arrange.getCode(), arrangeCode)
                || !Objects.equals(arrange.getTaskCode(), taskCode)) {
            throw new CheckedException("开工准备派工身份或当前租户/工厂不匹配，请从原派工重新进入");
        }
        return arrange;
    }
}
