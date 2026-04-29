package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mes.entity.MesTaskDeviceRecordEntity;
import com.kitex.mom.mes.mapper.MesTaskDeviceRecordMapper;
import com.kitex.mom.mes.service.MesTaskDeviceRecordService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 生产报工-设备开机记录
 *
 * @author kitex
 * @since 2025-04-21
 */
@Service
@AllArgsConstructor
public class MesTaskDeviceRecordServiceImpl extends ServiceImpl<MesTaskDeviceRecordMapper, MesTaskDeviceRecordEntity> implements MesTaskDeviceRecordService {
}
