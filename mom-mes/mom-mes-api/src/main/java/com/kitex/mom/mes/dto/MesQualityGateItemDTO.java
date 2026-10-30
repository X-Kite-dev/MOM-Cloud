package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "质量门质检任务证据")
public class MesQualityGateItemDTO {

    private Long recordId;
    private String taskCode;
    private String produceTaskCode;
    private String inspectionType;
    private Long inspectionId;
    private String sourceRequestId;
    private String genFlag;
    private String genRemark;
    private String inspectionStatus;
    private String inspectionResult;
    private String gateStatus;
    private String syncMessage;
    private Integer blockedCount;
    private Integer qmsTotalNum;
    private Integer qmsUsableNum;
    private Integer qmsHandleNum;
    private String qmsHandleResult;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime genTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastBlockedTime;

    private Long qualityEventId;
    private Long qualityEventSequence;
    private String qualityEventFingerprint;
    private Long qualityEventVersion;
    private String qualityEventState;
    private Long qualityBlockedEventId;
}
