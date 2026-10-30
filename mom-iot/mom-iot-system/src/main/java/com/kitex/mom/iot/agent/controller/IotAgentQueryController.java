package com.kitex.mom.iot.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.iot.agent.dto.IotProcessTelemetryDTO;
import com.kitex.mom.iot.agent.dto.IotProcessDeviationDTO;
import com.kitex.mom.iot.agent.dto.IotAlarmReceiptDTO;
import com.kitex.mom.iot.agent.dto.IotAlarmCandidateDTO;
import com.kitex.mom.iot.agent.dto.IotAlarmEventDTO;
import com.kitex.mom.iot.agent.service.IotAlarmCandidateQueryService;
import com.kitex.mom.iot.agent.service.IotAlarmEventQueryService;
import com.kitex.mom.iot.agent.service.IotAlarmReceiptQueryService;
import com.kitex.mom.iot.agent.service.IotProcessDeviationQueryService;
import com.kitex.mom.iot.agent.service.IotProcessTelemetryQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IoT 面向 Agent 的受控只读语义查询。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/iotAgentQuery")
public class IotAgentQueryController {

    private final IotProcessTelemetryQueryService processTelemetryService;
    private final IotProcessDeviationQueryService processDeviationService;
    private final IotAlarmReceiptQueryService alarmReceiptService;
    private final IotAlarmCandidateQueryService alarmCandidateService;
    private final IotAlarmEventQueryService alarmEventService;

    @Inner
    @PostMapping("/internal/process-telemetry")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotProcessTelemetryDTO.Evidence> processTelemetry(
            @Valid @RequestBody IotProcessTelemetryDTO.Query query) {
        return R.ok(processTelemetryService.query(query));
    }

    /** 逐点越界证据只从已核验告警或实际生产任务开始。 */
    @Inner
    @PostMapping("/internal/process-deviation")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotProcessDeviationDTO.Evidence> processDeviation(
            @Valid @RequestBody IotProcessDeviationDTO.Query query) {
        return R.ok(processDeviationService.query(query));
    }

    /** 原告警状态与异岗复核事实，不代表生产参数治理效果已经通过。 */
    @Inner
    @PostMapping("/internal/process-deviation-alarm-receipt")
    @PreAuthorize("@pms.hasPermission('iot_alarm_view') and @pms.hasPermission('iot_device_view')")
    public R<IotAlarmReceiptDTO.Evidence> processDeviationAlarmReceipt(
            @Valid @RequestBody IotAlarmReceiptDTO.Query query) {
        return R.ok(alarmReceiptService.query(query));
    }

    /** 原告警只读分页目录；选择后仍由逐点取证服务核验规则、设备与生产范围。 */
    @Inner
    @PostMapping("/internal/process-deviation-alarm-candidates")
    @PreAuthorize("@pms.hasPermission('iot_alarm_view') and @pms.hasPermission('iot_device_view')")
    public R<IotAlarmCandidateDTO.CandidatePage> processDeviationAlarmCandidates(
            @Valid @RequestBody IotAlarmCandidateDTO.Query query) {
        return R.ok(alarmCandidateService.candidates(query));
    }

    /** 供工厂事件订阅追赶已落库的原告警，不从模型或请求正文生成事件。 */
    @Inner
    @PostMapping("/internal/process-deviation-alarm-events")
    @PreAuthorize("@pms.hasPermission('iot_alarm_view') and @pms.hasPermission('iot_device_view')")
    public R<IotAlarmEventDTO.Batch> processDeviationAlarmEvents(
            @Valid @RequestBody IotAlarmEventDTO.Query query) {
        return R.ok(alarmEventService.after(query));
    }
}
