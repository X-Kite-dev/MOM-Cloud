package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesChangePointOperationEntity;
import com.kitex.mom.mes.mapper.MesChangePointOperationMapper;
import com.kitex.mom.mes.service.MesChangePointOperationService;
import org.springframework.stereotype.Service;

@Service
public class MesChangePointOperationServiceImpl
        extends ServiceImpl<MesChangePointOperationMapper, MesChangePointOperationEntity>
        implements MesChangePointOperationService {
}
