package com.kitex.mom.plan.exception;

import com.kitex.common.core.exception.CheckedException;
import lombok.Getter;

/**
 * 无已登记原号冲突的本地领域命令被明确拒绝。
 * 仅包裹终态/回执写入前的领域核查；SQL、提交和回滚异常不得转换为此类型。
 * 控制器在事务代理已成功回滚后才响应，不能凭通用错误猜测未落账。
 */
@Getter
public class PlnMpsRemainingRejectedException extends CheckedException {
    private final String requestId;
    private final String operationType;
    private final String targetId;
    private final String payloadSnapshot;

    public PlnMpsRemainingRejectedException(String requestId, String type, String target, String payload, String message) {
        super(message);
        this.requestId = requestId;
        this.operationType = type;
        this.targetId = target;
        this.payloadSnapshot = payload;
    }
}
