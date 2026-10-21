package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesIdentifierRouteReturnEntity;
import com.kitex.mom.mes.mapper.MesIdentifierRouteReturnMapper;
import com.kitex.mom.mes.service.MesIdentifierRouteReturnService;
import org.springframework.stereotype.Service;

/** 逐标识退回上序申请标准数据服务实现。 */
@Service
public class MesIdentifierRouteReturnServiceImpl
        extends ServiceImpl<MesIdentifierRouteReturnMapper, MesIdentifierRouteReturnEntity>
        implements MesIdentifierRouteReturnService {
}
