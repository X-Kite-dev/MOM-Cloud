package com.kitex.mom.mes.exception;

import com.kitex.mom.mes.dto.MesChangeoverAttributionDTO;
import lombok.Getter;

/**
 * 仅保存前领域拒绝；控制器必须在事务代理完成回滚之后返回该精确命令的确认。
 */
@Getter
public class MesChangeoverAttributionRejectedException extends RuntimeException {
    private final MesChangeoverAttributionDTO.Receipt receipt;

    public MesChangeoverAttributionRejectedException(MesChangeoverAttributionDTO.Receipt receipt, String message) {
        super(message);
        this.receipt = receipt.setOutcome("ROLLBACK_CONFIRMED").setMessage(message);
    }
}
