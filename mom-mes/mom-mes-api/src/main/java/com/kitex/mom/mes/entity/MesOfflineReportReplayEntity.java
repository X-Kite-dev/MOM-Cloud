package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 弱网报工重放结果账；同一请求号只保留一条并累计尝试次数。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_offline_report_replay")
@EqualsAndHashCode(callSuper = true)
public class MesOfflineReportReplayEntity extends BaseEntity<MesOfflineReportReplayEntity> {
    private String factoryCode;
    private String requestId;
    private Long permitId;
    private String permitNo;
    private Long localSequence;
    private LocalDateTime capturedAt;
    private LocalDateTime receivedAt;
    private String commandFingerprint;
    private String status;
    private Integer attemptCount;
    private String failureCode;
    private String failureMessage;
    private Long reportId;
    private Long operatorId;
    private String operatorCode;
    private LocalDateTime acceptedAt;
}
