package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesReportReverseScopeEntity;
import com.kitex.mom.mes.mapper.MesReportReverseScopeMapper;
import com.kitex.mom.mes.service.MesReportReverseScopeService;
import org.springframework.stereotype.Service;

/**
 * 报工冲销逐报工冻结范围服务实现。
 */
@Service
public class MesReportReverseScopeServiceImpl
        extends ServiceImpl<MesReportReverseScopeMapper, MesReportReverseScopeEntity>
        implements MesReportReverseScopeService {
}
