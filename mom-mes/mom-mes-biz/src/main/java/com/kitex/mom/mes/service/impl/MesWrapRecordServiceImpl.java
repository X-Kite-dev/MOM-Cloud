package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.mapper.MesWrapRecordMapper;
import com.kitex.mom.mes.service.MesWrapRecordService;
import org.springframework.stereotype.Service;

/**
 * 包装装箱扫码记录
 *
 * @author kitex
 * @since 2025-04-01
 */
@Service
public class MesWrapRecordServiceImpl extends ServiceImpl<MesWrapRecordMapper, MesWrapRecordEntity> implements MesWrapRecordService {

    /**
     * 删除
     *
     * @param id
     */
    @Override
    public void removeData(Long id) {
        throw new CheckedException("装箱明细不能直接删除，请通过报工冲销恢复库存、物料和生产数量");
    }
}
