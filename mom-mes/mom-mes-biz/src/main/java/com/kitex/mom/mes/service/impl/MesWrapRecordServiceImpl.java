package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.mapper.MesWrapRecordMapper;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesWrapRecordService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 包装装箱扫码记录
 *
 * @author kitex
 * @since 2025-04-01
 */
@Service
@AllArgsConstructor
public class MesWrapRecordServiceImpl extends ServiceImpl<MesWrapRecordMapper, MesWrapRecordEntity> implements MesWrapRecordService {

    private final MesTaskArrangeService arrangeService;

    /**
     * 删除
     *
     * @param id
     */
    @Override
    public void removeData(Long id) {
        MesWrapRecordEntity record = this.getById(id);
        this.removeById(record);

        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, record.getArrangeCode()));
        arrange.setCompleteNum(arrange.getCompleteNum().subtract(BigDecimal.ONE));
        arrangeService.updateById(arrange);
        // TODO 删除记录后 退料问题
    }
} 