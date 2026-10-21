package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesCarrierOperationEntity;
import com.kitex.mom.mes.mapper.MesCarrierOperationMapper;
import com.kitex.mom.mes.service.MesCarrierOperationService;
import org.springframework.stereotype.Service;

@Service
public class MesCarrierOperationServiceImpl
        extends ServiceImpl<MesCarrierOperationMapper, MesCarrierOperationEntity>
        implements MesCarrierOperationService {
}
