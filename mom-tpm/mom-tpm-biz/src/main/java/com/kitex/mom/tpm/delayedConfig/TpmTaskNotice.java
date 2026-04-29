package com.kitex.mom.tpm.delayedConfig;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 任务呼叫通知
 */
@Data
public class TpmTaskNotice {
    /**
     * 业务id
     */
    @Schema(description = "业务id")
    private Long id;
    /**
     * 通知人员
     */
    @Schema(description = "通知人员")
    private List<Long> userIdList;
}
