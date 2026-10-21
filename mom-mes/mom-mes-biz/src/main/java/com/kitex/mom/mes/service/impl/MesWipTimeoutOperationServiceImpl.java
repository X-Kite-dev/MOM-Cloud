package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesWipTimeoutOperationEntity;
import com.kitex.mom.mes.mapper.MesWipTimeoutOperationMapper;
import com.kitex.mom.mes.service.MesWipTimeoutOperationService;
import org.springframework.stereotype.Service;

@Service
public class MesWipTimeoutOperationServiceImpl
        extends ServiceImpl<MesWipTimeoutOperationMapper, MesWipTimeoutOperationEntity>
        implements MesWipTimeoutOperationService {
}
