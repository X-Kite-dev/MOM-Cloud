package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.mapper.MesAndonOperationMapper;
import com.kitex.mom.mes.service.MesAndonOperationService;
import org.springframework.stereotype.Service;

@Service
public class MesAndonOperationServiceImpl
        extends ServiceImpl<MesAndonOperationMapper, MesAndonOperationEntity>
        implements MesAndonOperationService {
}
