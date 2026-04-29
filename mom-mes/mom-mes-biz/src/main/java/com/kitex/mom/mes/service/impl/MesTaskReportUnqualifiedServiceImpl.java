package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.mapper.MesTaskReportUnqualifiedMapper;
import com.kitex.mom.mes.service.MesTaskReportUnqualifiedService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 生产报工-不合格品上报
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskReportUnqualifiedServiceImpl extends ServiceImpl<MesTaskReportUnqualifiedMapper, MesTaskReportUnqualifiedEntity> implements MesTaskReportUnqualifiedService {
}
