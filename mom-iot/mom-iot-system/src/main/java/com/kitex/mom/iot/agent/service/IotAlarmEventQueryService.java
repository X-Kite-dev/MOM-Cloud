package com.kitex.mom.iot.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.iot.agent.dto.IotAlarmEventDTO;
import com.kitex.mom.iot.entity.IotAlarmEpisode;
import com.kitex.mom.iot.mapper.IotAlarmEpisodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 事件订阅从 IoT 原告警表按主键顺序追赶，不接受调用方伪造的告警内容。 */
@Service
@RequiredArgsConstructor
public class IotAlarmEventQueryService {

    private final IotActualFactoryAccessService factoryAccess;
    private final IotAlarmEpisodeMapper alarmMapper;

    @Transactional(readOnly = true)
    public IotAlarmEventDTO.Batch after(IotAlarmEventDTO.Query query) {
        String factory = factoryAccess.require("read");
        Long tenant = factoryAccess.tenantId();
        if (query == null || !factory.equals(query.getFactoryCode()) || query.getNotBefore() == null
                || query.getNotBefore().isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new CheckedException("告警事件查询必须指定当前实名工厂和有效订阅时间");
        }
        long afterId = query.getAfterAlarmId() == null ? 0L : query.getAfterAlarmId();
        int limit = query.getLimit() == null ? 20 : query.getLimit();
        if (afterId < 0 || limit < 1 || limit > 50) {
            throw new CheckedException("告警事件游标或批量上限不合法");
        }
        var rows = alarmMapper.selectPage(new Page<IotAlarmEpisode>(1, limit, false),
                new LambdaQueryWrapper<IotAlarmEpisode>()
                        .select(IotAlarmEpisode::getId, IotAlarmEpisode::getTriggeredTime)
                        .eq(IotAlarmEpisode::getTenantId, tenant)
                        .eq(IotAlarmEpisode::getFactoryCode, factory)
                        .eq(IotAlarmEpisode::getDelFlag, "0")
                        .gt(IotAlarmEpisode::getId, afterId)
                        .ge(IotAlarmEpisode::getCreateTime, query.getNotBefore())
                        .orderByAsc(IotAlarmEpisode::getId)).getRecords();
        return new IotAlarmEventDTO.Batch().setTenantId(String.valueOf(tenant)).setFactoryCode(factory)
                .setRecords(rows.stream().map(row -> new IotAlarmEventDTO.AlarmEvent()
                        .setAlarmEpisodeId(String.valueOf(row.getId()))
                        .setTriggeredTime(row.getTriggeredTime())).toList());
    }
}
