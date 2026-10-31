package com.kitex.mom.iot.parameter;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** 不把设备端独立提交包在业务事务中；丢响应沿原号读既有日志。 */
@Service
@RequiredArgsConstructor
@PreAuthorize(IotParameterCommandService.WRITE)
public class IotParameterCommandCoordinator {
    private final IotParameterCommandService commands;
    private final IotParameterSimulator simulator;
    private final IotParameterReceiptService receipts;

    public IotParameterCommandDTO.Detail simulate(String id) {
        try {
            var journal = simulator.execute(id);
            if ("TIMEOUT_AFTER_EXECUTION".equals(commands.detail(id).command().getScenario()))
                commands.markUnknown(id);
            else receipts.accept(id, journal);
            return commands.detail(id);
        } catch (RuntimeException error) {
            unknown(id, error);
            throw error;
        }
    }

    public IotParameterCommandDTO.Detail recover(String id) {
        try {
            var journal = simulator.find(id);
            if (journal == null) commands.markUnknown(id);
            else receipts.accept(id, journal);
            return commands.detail(id);
        } catch (RuntimeException error) {
            unknown(id, error);
            throw error;
        }
    }

    private void unknown(String id, RuntimeException original) {
        try {
            commands.markUnknown(id);
        } catch (RuntimeException failed) {
            original.addSuppressed(failed);
        }
    }
}
