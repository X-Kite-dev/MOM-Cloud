package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmRepairFaultClassEntity;
import com.kitex.mom.tpm.entity.TpmRepairRemindEntity;
import com.kitex.mom.tpm.mapper.TpmRepairFaultClassMapper;
import com.kitex.mom.tpm.mapper.TpmRepairRemindMapper;
import com.kitex.mom.tpm.service.TpmRepairFaultClassService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/**
 * 故障类型表
 *
 * @author kitex
 * @date 2025-02-06 15:25:03
 */
@Service
@AllArgsConstructor
public class TpmRepairFaultClassServiceImpl extends ServiceImpl<TpmRepairFaultClassMapper, TpmRepairFaultClassEntity> implements TpmRepairFaultClassService {

    private final TpmRepairRemindMapper tpmRepairRemindMapper;


    @Override
    public Boolean removeClassById(ArrayList<Long> toList) {
        Long aLong = tpmRepairRemindMapper.selectCount(new LambdaQueryWrapper<TpmRepairRemindEntity>()
                .in(TpmRepairRemindEntity::getFaultClassId, toList));
        if (aLong.compareTo(0L) > 0) {
            throw new RuntimeException("删除数据中已绑定提醒配置，请先删除提醒配置信息！");
        }
        return this.removeBatchByIds(toList);
    }
}