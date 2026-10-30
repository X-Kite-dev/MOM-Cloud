package com.kitex.mom.mes.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 精确来源与永久审计账；仅由专用Mapper写入。
 */
@Data
public class MesDispatchCorrectionRequestEntity {

    private Long id;

    private Long tenantId;

    private String factoryCode;

    private Long arrangeId;

    private String arrangeCode;

    private String taskCode;

    private String produceTaskCode;

    private String status;

    private String sourceFingerprint;

    private String evidenceFingerprint;

    private String targetShiftCode;

    private String targetShiftName;

    private String targetWorksiteCodes;

    private String reason;

    private Long applicantId;

    private String applicantCode;

    private String applicantName;

    private LocalDateTime submittedTime;

    private Long reviewerId;

    private String reviewerCode;

    private String reviewerName;

    private LocalDateTime reviewedTime;

    private String reviewReason;

    private Long replacementArrangeId;

    private String replacementArrangeCode;

    private String beforeSnapshot;

    private String afterSnapshot;

    private String proposalSnapshot;

    private String originalPolicySnapshot;
}
