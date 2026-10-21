package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 仓库确认报废或退货实物已完成的幂等命令。
 */
@Data
public class WmsQualityActionExecuteCommand {
    private Long operationId;
    private String requestId;
    private String executionRef;
    private String remark;
}
