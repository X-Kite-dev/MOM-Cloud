package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesMaterialStartCheckEntity;
import com.kitex.mom.mes.mapper.MesMaterialStartCheckMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MesMaterialStartCheckWriter {
    private final MesMaterialStartCheckMapper records;

    /**
     * 阻断尝试独立保存；观察表不持有派工外键，避免父开工事务行锁自阻塞。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void rejected(MesMaterialStartCheckEntity record) {
        if (records.insert(record) != 1) throw new CheckedException("开工物料阻断证据保存失败");
    }
}
