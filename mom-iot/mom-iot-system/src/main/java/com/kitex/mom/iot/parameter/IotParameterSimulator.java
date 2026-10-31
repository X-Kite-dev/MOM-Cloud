package com.kitex.mom.iot.parameter;

import static com.kitex.mom.iot.parameter.ParameterCommandCodec.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.TreeMap;

/** 独立事务模拟设备端日志；从未调用真实设备协议，不是生产执行证明。 */
@Service
@RequiredArgsConstructor
@PreAuthorize(IotParameterCommandService.WRITE)
public class IotParameterSimulator {
    private final IotParameterCommandService commands;
    private final IotParameterCommandMapper mapper;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public IotParameterSimExecution execute(String request) {
        var command = commands.lockOwned(request);
        var old = mapper.journal(command.getTenantId(), command.getFactoryCode(), request);
        if (old != null) {
            verify(command, old);
            return old;
        }
        require("REQUESTED".equals(command.getState()), "只有已请求且未执行的原命令可进入模拟设备");
        commands.verifyDispatchSource(command);
        var context = commands.context(command);
        var values = new TreeMap<String, IotParameterCommandDTO.Readback>();
        boolean reject = "REJECT".equals(command.getScenario());
        if (!reject)
            for (var p : context.getParameters())
                values.put(
                        p.identifier(),
                        new IotParameterCommandDTO.Readback(p.targetValue(), p.unit()));
        if ("MISMATCH".equals(command.getScenario())) {
            String first = values.firstKey();
            var value = values.get(first);
            values.put(
                    first,
                    new IotParameterCommandDTO.Readback(
                            decimal(value.value()).add(BigDecimal.ONE).toPlainString(),
                            value.unit()));
        }
        LocalDateTime now = LocalDateTime.now();
        var row =
                new IotParameterSimExecution()
                        .setTenantId(command.getTenantId())
                        .setFactoryCode(command.getFactoryCode())
                        .setRequestId(request)
                        .setCommandHash(command.getContextHash())
                        .setDeviceCode(command.getDeviceCode())
                        .setFirmwareVersion(command.getFirmwareVersion())
                        .setOutcome(reject ? "REJECTED" : "EXECUTED")
                        .setExecutionCount(reject ? 0 : 1)
                        .setValuesJson(new ParameterCommandCodec(json).encode(values))
                        .setReceivedAt(now)
                        .setExecutedAt(reject ? null : now)
                        .setReadBackAt(reject ? null : now);
        require(mapper.insertJournal(row) == 1, "模拟设备日志未保存，未确认执行");
        var persisted = mapper.journal(command.getTenantId(), command.getFactoryCode(), request);
        require(persisted != null, "模拟设备日志未能原号读取");
        verify(command, persisted);
        return persisted;
    }

    /** 只查原号日志，不调用execute；模拟器关闭或身份变化同样不能写回业务事实。 */
    @Transactional(readOnly = true)
    public IotParameterSimExecution find(String request) {
        var detail = commands.detail(request);
        var c = detail.command();
        var row = mapper.journal(c.getTenantId(), c.getFactoryCode(), request);
        if (row != null) verify(c, row);
        return row;
    }

    static void verify(IotParameterCommand command, IotParameterSimExecution row) {
        require(
                Objects.equals(command.getTenantId(), row.getTenantId())
                        && Objects.equals(command.getFactoryCode(), row.getFactoryCode())
                        && Objects.equals(command.getRequestId(), row.getRequestId())
                        && Objects.equals(command.getContextHash(), row.getCommandHash())
                        && Objects.equals(command.getDeviceCode(), row.getDeviceCode())
                        && Objects.equals(command.getFirmwareVersion(), row.getFirmwareVersion()),
                "模拟设备原日志与本命令/设备/版本不一致");
        require(
                row.getReceivedAt() != null
                        && ("REJECTED".equals(row.getOutcome())
                                ? Objects.equals(row.getExecutionCount(), 0)
                                        && row.getExecutedAt() == null
                                        && row.getReadBackAt() == null
                                : "EXECUTED".equals(row.getOutcome())
                                        && Objects.equals(row.getExecutionCount(), 1)
                                        && row.getExecutedAt() != null
                                        && row.getReadBackAt() != null
                                        && !row.getExecutedAt().isBefore(row.getReceivedAt())
                                        && !row.getReadBackAt().isBefore(row.getExecutedAt())),
                "模拟设备阶段日志不完整");
    }
}
