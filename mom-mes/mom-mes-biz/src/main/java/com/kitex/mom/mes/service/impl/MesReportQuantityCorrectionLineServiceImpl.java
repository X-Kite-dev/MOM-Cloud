package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesReportQuantityCorrectionLineEntity;
import com.kitex.mom.mes.mapper.MesReportQuantityCorrectionLineMapper;
import com.kitex.mom.mes.service.MesReportQuantityCorrectionLineService;
import org.springframework.stereotype.Service;

/**
 * 聚合报工部分纠正分配行服务实现。
 */
@Service
public class MesReportQuantityCorrectionLineServiceImpl
        extends ServiceImpl<MesReportQuantityCorrectionLineMapper, MesReportQuantityCorrectionLineEntity>
        implements MesReportQuantityCorrectionLineService {
}
