package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.service.MesQmsInspectionCommandService;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工序质检跨服务协调器：永久登记和发送边界先于Feign调用，明确回执独立保存。
 * 调用失败不代表QMS未创建；调用者只允许沿相同来源请求号重试。
 */
@Service
@RequiredArgsConstructor
public class MesQmsInspectionCoordinator {
    private final MesQmsInspectionCommandService commands;
    private final RemoteProcedureService remoteProcedureService;

    /**
     * 使用相同原号可安全重放；QMS端会按完整参数摘要返回同一任务。
     */
    public Long create(InspectionProcedureCreateParam command, String factoryCode) {
        var registered = commands.register(command, factoryCode);
        if ("QMS_CONFIRMED".equals(registered.getStatus())) {
            return registered.getQmsTaskId();
        }
        var outbound = commands.beginAttempt(command, factoryCode);
        if ("QMS_CONFIRMED".equals(outbound.getStatus())) {
            return outbound.getQmsTaskId();
        }
        try {
            var response = remoteProcedureService.create(command);
            if (response == null || response.getCode() != 0 || response.getData() == null || response.getData() <= 0) {
                throw new CheckedException(response == null ? "QMS未返回结果" : response.getMsg());
            }
            return commands.confirm(command, factoryCode, response.getData()).getQmsTaskId();
        } catch (RuntimeException failure) {
            try {
                commands.recordFailure(command, factoryCode, failure);
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }
}
