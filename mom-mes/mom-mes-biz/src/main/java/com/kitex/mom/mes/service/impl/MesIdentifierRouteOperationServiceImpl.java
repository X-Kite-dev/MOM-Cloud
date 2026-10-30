package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesIdentifierRouteOperationEntity;
import com.kitex.mom.mes.mapper.MesIdentifierRouteOperationMapper;
import com.kitex.mom.mes.service.MesIdentifierRouteOperationService;
import org.springframework.stereotype.Service;

/**
 * 退回上序永久操作回执标准数据服务实现。
 */
@Service
public class MesIdentifierRouteOperationServiceImpl
        extends ServiceImpl<MesIdentifierRouteOperationMapper, MesIdentifierRouteOperationEntity>
        implements MesIdentifierRouteOperationService {
}
