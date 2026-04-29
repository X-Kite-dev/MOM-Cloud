package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesTaskReportFeedEntity;
import com.kitex.mom.mes.mapper.MesTaskReportFeedMapper;
import com.kitex.mom.mes.service.MesTaskReportFeedService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 生产报工-投料
 *
 * @author kitex
 * @since 2025-05-06
 */
@Service
@AllArgsConstructor
public class MesTaskReportFeedServiceImpl extends ServiceImpl<MesTaskReportFeedMapper, MesTaskReportFeedEntity> implements MesTaskReportFeedService {
}
