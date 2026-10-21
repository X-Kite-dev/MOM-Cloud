package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * PLAN生产计划被MES接收后的永久回执。
 *
 * <p>回执同时携带请求号和载荷指纹，PLAN可据此区分同请求重放与载荷漂移，
 * 避免远程调用成功但本地事务未提交时再次创建生产工单。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "生产计划下达MES接收回执")
public class MesTaskIssueReceiptDTO {

    @Schema(description = "PLAN生成的永久幂等请求号")
    private String requestId;

    @Schema(description = "PLAN下达载荷SHA-256指纹")
    private String requestFingerprint;

    @Schema(description = "MES生产工单ID")
    private Long taskId;

    @Schema(description = "MES生产工单编码")
    private String taskCode;

    @Schema(description = "生产计划号")
    private String planNo;

    @Schema(description = "MES首次接收时间")
    private LocalDateTime acceptedTime;

    @Schema(description = "是否为同请求重放返回")
    private Boolean replayed;
}
