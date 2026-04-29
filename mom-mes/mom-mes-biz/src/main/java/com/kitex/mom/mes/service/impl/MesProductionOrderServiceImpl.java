package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import com.kitex.mom.mes.service.MesProductionOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 生产订单主表 服务实现类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MesProductionOrderServiceImpl extends ServiceImpl<MesProductionOrderMapper, MesProductionOrderEntity> implements MesProductionOrderService {

    @Override
    public Boolean schedule(MesProductionOrderDTO query) {
        return null;
    }
}