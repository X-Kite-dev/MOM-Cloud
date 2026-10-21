package com.kitex.mom.mes.exception;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import lombok.Getter;

/**
 * 来源核定在保存前被领域规则明确拒绝。
 *
 * <p>仅包裹尚未调用持久化的校验错误。事务代理回滚后，控制器才可返回明确拒绝契约；
 * SQL、网络、提交异常、权限错误及原号冲突均不得转换为此异常。</p>
 */
@Getter
public class MesReportSourceRejectedException extends CheckedException {
    private final String requestId;
    private final String reportId;
    private final String expectedFingerprint;

    public MesReportSourceRejectedException(MesReportSourceAssignmentDTO.Assign command, String message) {
        super(message);
        this.requestId = command.getRequestId().trim();
        this.reportId = command.getReportId().toString();
        this.expectedFingerprint = command.getExpectedFingerprint();
    }
}
