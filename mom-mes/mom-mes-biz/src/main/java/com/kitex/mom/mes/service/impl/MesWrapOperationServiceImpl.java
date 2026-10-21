package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesWrapOperationEntity;
import com.kitex.mom.mes.mapper.MesWrapOperationMapper;
import com.kitex.mom.mes.service.MesWrapOperationService;
import org.springframework.stereotype.Service;

@Service
public class MesWrapOperationServiceImpl
        extends ServiceImpl<MesWrapOperationMapper, MesWrapOperationEntity>
        implements MesWrapOperationService {
}
