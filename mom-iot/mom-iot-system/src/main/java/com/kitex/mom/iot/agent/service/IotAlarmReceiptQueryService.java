package com.kitex.mom.iot.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.iot.agent.dto.IotAlarmReceiptDTO;
import com.kitex.mom.iot.entity.IotAlarmEpisode;
import com.kitex.mom.iot.entity.IotAlarmOperation;
import com.kitex.mom.iot.mapper.IotAlarmEpisodeMapper;
import com.kitex.mom.iot.mapper.IotAlarmOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 按冻结的告警主键核对原 IoT 状态与实名操作账。
 *
 * <p>本服务不确认生产参数改善效果；IoT 的恢复与异岗关闭只在原告警状态机完成。</p>
 */
@Service
@RequiredArgsConstructor
public class IotAlarmReceiptQueryService {

    private final IotActualFactoryAccessService factoryAccess;
    private final IotAlarmEpisodeMapper alarmMapper;
    private final IotAlarmOperationMapper operationMapper;

    public IotAlarmReceiptDTO.Evidence query(IotAlarmReceiptDTO.Query query) {
        String factory = factoryAccess.require("read");
        Long tenantId = factoryAccess.tenantId();
        if (query == null || !factory.equals(query.getFactoryCode())
                || query.getAlarmEpisodeId() == null
                || !query.getAlarmEpisodeId().matches("[1-9][0-9]{0,18}")
                || query.getDeviceCode() == null || query.getParameterIdentifier() == null) {
            throw new CheckedException("原告警回执缺少当前工厂或可信告警主键");
        }
        Long alarmId;
        try {
            alarmId = Long.valueOf(query.getAlarmEpisodeId());
        } catch (NumberFormatException invalid) {
            throw new CheckedException("原告警主键超出有效范围");
        }
        IotAlarmEpisode alarm = alarmMapper.selectById(alarmId);
        if (alarm == null || !Objects.equals(alarm.getTenantId(), tenantId)
                || !factory.equals(alarm.getFactoryCode())
                || !query.getDeviceCode().equals(alarm.getDeviceCode())
                || !query.getParameterIdentifier().equals(alarm.getIdentifier())) {
            throw new CheckedException("原IoT告警不存在或与当前工厂、设备、参数不一致");
        }
        Long operationCount = operationMapper.selectCount(new LambdaQueryWrapper<IotAlarmOperation>()
                .eq(IotAlarmOperation::getTenantId, tenantId)
                .eq(IotAlarmOperation::getFactoryCode, factory)
                .eq(IotAlarmOperation::getAlarmId, alarmId));
        List<IotAlarmOperation> latestRows = operationMapper.selectList(new LambdaQueryWrapper<IotAlarmOperation>()
                .eq(IotAlarmOperation::getTenantId, tenantId)
                .eq(IotAlarmOperation::getFactoryCode, factory)
                .eq(IotAlarmOperation::getAlarmId, alarmId)
                .orderByDesc(IotAlarmOperation::getOperationTime, IotAlarmOperation::getId)
                .last("LIMIT 1"));
        IotAlarmOperation latest = latestRows.isEmpty() ? null : latestRows.get(0);
        boolean closedWithProof = "CLOSED".equals(alarm.getStatus())
                && alarm.getAcknowledgedBy() != null && alarm.getVerifiedBy() != null
                && !Objects.equals(alarm.getAcknowledgedBy(), alarm.getVerifiedBy())
                && alarm.getRecoveredTime() != null && alarm.getClosedTime() != null
                && latest != null && "VERIFY_RECOVERY".equals(latest.getAction())
                && "CLOSED".equals(latest.getResultStatus())
                && Objects.equals(alarm.getVerifiedBy(), latest.getOperatorId());
        List<String> gaps = new ArrayList<>();
        if ("CLOSED".equals(alarm.getStatus()) && !closedWithProof) {
            gaps.add("告警标记为已关闭，但确认、有效恢复或异岗复核操作账不完整；不能认作可信领域闭环");
        }
        if (operationCount == null || operationCount == 0) {
            gaps.add("该告警尚无实名确认或复核操作；自动恢复不等于人工关闭");
        }
        if (alarm.getStatus() == null
                || !List.of("OPEN", "ACKNOWLEDGED", "RECOVERED", "CLOSED").contains(alarm.getStatus())) {
            gaps.add("原告警状态不在当前受控状态集中，需在IoT原台账核对");
        }
        List<String> refs = new ArrayList<>();
        refs.add("IOT:iot_alarm_episode:" + alarmId);
        if (latest != null) {
            refs.add("IOT:iot_alarm_operation:" + latest.getId());
        }
        IotAlarmReceiptDTO.Evidence evidence = new IotAlarmReceiptDTO.Evidence()
                .setTenantId(String.valueOf(tenantId)).setFactoryCode(factory)
                .setAlarmEpisodeId(String.valueOf(alarmId))
                .setDeviceCode(alarm.getDeviceCode()).setParameterIdentifier(alarm.getIdentifier())
                .setStatus(alarm.getStatus()).setAcknowledgedBy(text(alarm.getAcknowledgedBy()))
                .setVerifiedBy(text(alarm.getVerifiedBy()))
                .setFirstBreachTime(alarm.getFirstBreachTime())
                .setAcknowledgedTime(alarm.getAcknowledgedTime())
                .setRecoveredTime(alarm.getRecoveredTime()).setClosedTime(alarm.getClosedTime())
                .setOperationCount(operationCount)
                .setLatestOperationId(latest == null ? null : String.valueOf(latest.getId()))
                .setLatestOperationAction(latest == null ? null : latest.getAction())
                .setLatestOperationTime(latest == null ? null : latest.getOperationTime())
                .setLifecycleComplete(closedWithProof).setObservedAt(LocalDateTime.now())
                .setDataGaps(gaps).setEvidenceRefs(refs);
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private String fingerprint(IotAlarmReceiptDTO.Evidence evidence) {
        String facts = String.join("|", evidence.getTenantId(), evidence.getFactoryCode(),
                evidence.getAlarmEpisodeId(), evidence.getDeviceCode(), evidence.getParameterIdentifier(),
                String.valueOf(evidence.getStatus()), String.valueOf(evidence.getAcknowledgedBy()),
                String.valueOf(evidence.getVerifiedBy()), String.valueOf(evidence.getRecoveredTime()),
                String.valueOf(evidence.getClosedTime()), String.valueOf(evidence.getLatestOperationId()),
                String.valueOf(evidence.getLatestOperationAction()), String.valueOf(evidence.getOperationCount()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法生成原告警回执指纹", failure);
        }
    }
}
