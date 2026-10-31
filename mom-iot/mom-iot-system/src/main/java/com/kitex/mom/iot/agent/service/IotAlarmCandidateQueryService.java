package com.kitex.mom.iot.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.iot.agent.dto.IotAlarmCandidateDTO;
import com.kitex.mom.iot.entity.IotAlarmEpisode;
import com.kitex.mom.iot.mapper.IotAlarmEpisodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 为参数异常工作台提供当前实名工厂的原告警候选，不推断告警与工单的关联。 */
@Service
@RequiredArgsConstructor
public class IotAlarmCandidateQueryService {

    private static final Set<String> KNOWN_STATES = Set.of("OPEN", "ACKNOWLEDGED", "RECOVERED", "CLOSED");

    private final IotActualFactoryAccessService factoryAccess;
    private final IotAlarmEpisodeMapper alarms;

    @Transactional(readOnly = true)
    public IotAlarmCandidateDTO.CandidatePage candidates(IotAlarmCandidateDTO.Query command) {
        String factory = factoryAccess.require("read");
        Long tenant = factoryAccess.tenantId();
        if (command == null || !factory.equals(command.getFactoryCode())) {
            throw new CheckedException("告警候选必须按当前实名租户和实际工厂查询");
        }
        int current = command.getCurrent() == null ? 1 : command.getCurrent();
        int size = command.getSize() == null ? 10 : command.getSize();
        if (current < 1 || current > 100_000 || size < 1 || size > 20
                || (long) (current - 1) * size > 1_000_000) {
            throw new CheckedException("告警候选分页超过安全边界");
        }
        String rawKeyword = command.getKeyword() == null ? "" : command.getKeyword();
        if (rawKeyword.length() > 100 || rawKeyword.chars().anyMatch(Character::isISOControl)) {
            throw new CheckedException("告警候选关键词超过100字或包含控制字符");
        }
        String keyword = rawKeyword.trim();
        LambdaQueryWrapper<IotAlarmEpisode> query = new LambdaQueryWrapper<IotAlarmEpisode>()
                .eq(IotAlarmEpisode::getTenantId, tenant)
                .eq(IotAlarmEpisode::getFactoryCode, factory)
                .eq(IotAlarmEpisode::getDelFlag, "0")
                .select(IotAlarmEpisode::getId, IotAlarmEpisode::getRuleId,
                        IotAlarmEpisode::getDeviceId, IotAlarmEpisode::getDeviceCode,
                        IotAlarmEpisode::getIdentifier, IotAlarmEpisode::getName,
                        IotAlarmEpisode::getSeverity, IotAlarmEpisode::getStatus,
                        IotAlarmEpisode::getFirstBreachTime, IotAlarmEpisode::getTriggeredTime)
                .orderByDesc(IotAlarmEpisode::getTriggeredTime, IotAlarmEpisode::getId);
        if (StringUtils.hasText(keyword)) {
            query.and(part -> part.like(IotAlarmEpisode::getDeviceCode, keyword)
                    .or().like(IotAlarmEpisode::getIdentifier, keyword)
                    .or().like(IotAlarmEpisode::getName, keyword));
        }
        Page<IotAlarmEpisode> page = alarms.selectPage(new Page<>(current, size), query);
        if (page.getRecords() == null || page.getRecords().size() > size || page.getTotal() < 0) {
            throw new CheckedException("告警候选分页结果不完整");
        }
        List<IotAlarmCandidateDTO.Candidate> records = page.getRecords().stream()
                .map(this::candidate).toList();
        return new IotAlarmCandidateDTO.CandidatePage().setTenantId(String.valueOf(tenant)).setFactoryCode(factory)
                .setCurrent(current).setSize(size).setTotal(page.getTotal()).setRecords(records);
    }

    private IotAlarmCandidateDTO.Candidate candidate(IotAlarmEpisode alarm) {
        LocalDateTime occurred = alarm.getFirstBreachTime() == null
                ? alarm.getTriggeredTime() : alarm.getFirstBreachTime();
        String reason = alarm.getStatus() == null || !KNOWN_STATES.contains(alarm.getStatus())
                ? "告警状态不在受控范围"
                : alarm.getRuleId() == null || alarm.getDeviceId() == null
                ? "缺少原告警规则或设备主键"
                : !StringUtils.hasText(alarm.getDeviceCode()) || !StringUtils.hasText(alarm.getIdentifier())
                ? "缺少可信设备或参数标识" : occurred == null ? "缺少告警发生时间" : null;
        return new IotAlarmCandidateDTO.Candidate()
                .setAlarmEpisodeId(String.valueOf(alarm.getId()))
                .setDeviceCode(alarm.getDeviceCode())
                .setParameterIdentifier(alarm.getIdentifier())
                .setParameterName(alarm.getName())
                .setSeverity(alarm.getSeverity())
                .setStatus(alarm.getStatus())
                .setOccurredAt(occurred)
                .setSelectable(reason == null)
                .setUnavailableReason(reason);
    }
}
