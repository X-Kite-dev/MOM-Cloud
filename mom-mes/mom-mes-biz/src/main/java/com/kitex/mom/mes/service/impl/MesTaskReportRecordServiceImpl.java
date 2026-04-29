package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesTaskReportRecordEntity;
import com.kitex.mom.mes.mapper.MesTaskReportRecordMapper;
import com.kitex.mom.mes.service.MesTaskReportRecordService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 生产报工-操作明细
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskReportRecordServiceImpl extends ServiceImpl<MesTaskReportRecordMapper, MesTaskReportRecordEntity> implements MesTaskReportRecordService {
}
