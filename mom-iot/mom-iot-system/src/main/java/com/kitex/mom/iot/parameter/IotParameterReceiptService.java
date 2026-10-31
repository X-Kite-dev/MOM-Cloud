package com.kitex.mom.iot.parameter;

import static com.kitex.mom.iot.parameter.ParameterCommandCodec.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 仅消费既有模拟设备原日志；生产协议接入必须提供独立真实适配及鉴真合同。 */
@Service
@RequiredArgsConstructor
@PreAuthorize(IotParameterCommandService.WRITE)
public class IotParameterReceiptService {
    private final IotParameterCommandService commands;
    private final IotParameterCommandMapper mapper;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public void accept(String id, IotParameterSimExecution supplied) {
        var command = commands.lockOwned(id);
        require(!"PREPARED".equals(command.getState()), "原命令尚未请求");
        var codec = new ParameterCommandCodec(json);
        var journal = mapper.journal(command.getTenantId(), command.getFactoryCode(), id);
        require(
                journal != null && Objects.equals(codec.encode(journal), codec.encode(supplied)),
                "必须核对原号已持久的模拟设备日志");
        IotParameterSimulator.verify(command, journal);
        Map<String, IotParameterCommandDTO.Readback> values = codec.values(journal.getValuesJson());
        require(values != null, "设备读回原件为空");
        boolean rejected = "REJECTED".equals(journal.getOutcome());
        var facts = new LinkedHashMap<String, LocalDateTime>();
        facts.put(rejected ? "REJECTED" : "RECEIVED", journal.getReceivedAt());
        if (!rejected) {
            facts.put("EXECUTED", journal.getExecutedAt());
            facts.put("READ_BACK", journal.getReadBackAt());
        }
        var old = mapper.receipts(command.getTenantId(), command.getFactoryCode(), id);
        require(
                old.size() <= facts.size()
                        && old.stream().allMatch(r -> facts.containsKey(r.getStage())),
                "原命令存在不兼容的阶段回执");
        boolean terminal = Set.of("VERIFIED", "MISMATCH", "REJECTED").contains(command.getState());
        if (terminal) require(old.size() == facts.size(), "命令终态缺少原阶段回执，请受控核对");
        for (var fact : facts.entrySet()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("schema", "SIM_PARAMETER_RECEIPT@1");
            payload.put("mode", "SIMULATION");
            payload.put("requestId", id);
            payload.put("commandHash", command.getContextHash());
            payload.put("deviceCode", command.getDeviceCode());
            payload.put("firmwareVersion", command.getFirmwareVersion());
            payload.put("stage", fact.getKey());
            payload.put("observedAt", fact.getValue().toString());
            payload.put("values", "READ_BACK".equals(fact.getKey()) ? values : Map.of());
            String raw = codec.encode(payload);
            var previous = old.stream().filter(r -> r.getStage().equals(fact.getKey())).findFirst();
            if (previous.isPresent()) {
                require(
                        Objects.equals(previous.get().getFactJson(), raw)
                                && Objects.equals(previous.get().getFactHash(), hash(raw)),
                        "原回执内容不能被重放覆盖");
            } else
                require(
                        mapper.insertReceipt(
                                        new IotParameterReceipt()
                                                .setTenantId(command.getTenantId())
                                                .setFactoryCode(command.getFactoryCode())
                                                .setRequestId(id)
                                                .setStage(fact.getKey())
                                                .setFactJson(raw)
                                                .setFactHash(hash(raw))
                                                .setReceivedAt(LocalDateTime.now()))
                                == 1,
                        "阶段回执保存失败，整笔回滚");
        }
        boolean matches =
                !rejected && values.size() == commands.context(command).getParameters().size();
        if (!rejected)
            for (var p : commands.context(command).getParameters()) {
                var actual = values.get(p.identifier());
                matches &=
                        actual != null
                                && Objects.equals(p.unit(), actual.unit())
                                && actual.value() != null
                                && decimal(p.targetValue()).compareTo(decimal(actual.value())) == 0;
            }
        String state = rejected ? "REJECTED" : matches ? "VERIFIED" : "MISMATCH";
        require(!terminal || Objects.equals(state, command.getState()), "原命令终态与设备日志冲突");
        if (!terminal) {
            command.setState(state);
            commands.save(command);
        }
    }
}
