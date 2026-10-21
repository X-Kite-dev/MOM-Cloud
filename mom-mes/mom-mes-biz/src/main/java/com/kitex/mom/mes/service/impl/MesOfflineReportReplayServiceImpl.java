package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesOfflineReportReplayEntity;
import com.kitex.mom.mes.mapper.MesOfflineReportReplayMapper;
import com.kitex.mom.mes.service.MesOfflineReportReplayService;
import org.springframework.stereotype.Service;

/** 弱网报工重放审计账标准服务实现。 */
@Service
public class MesOfflineReportReplayServiceImpl
        extends ServiceImpl<MesOfflineReportReplayMapper, MesOfflineReportReplayEntity>
        implements MesOfflineReportReplayService {
}
