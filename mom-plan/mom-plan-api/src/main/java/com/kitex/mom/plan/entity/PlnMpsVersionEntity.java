package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MPS候选版本；提交后内容不可修改，异岗复核发布或驳回。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("pln_mps_version")
public class PlnMpsVersionEntity extends BaseEntity<PlnMpsVersionEntity> {
    private String versionNo;
    private Integer revision;
    private LocalDate horizonStart;
    private LocalDate horizonEnd;
    private LocalDate freezeThrough;
    private Long policyId;
    private Integer policyVersion;
    private String status;
    private String submitRequestId;
    private String requestFingerprint;
    private String evidenceFingerprint;
    private String reason;
    private String applicantCode;
    private String reviewerCode;
    private String reviewReason;
    private LocalDateTime submittedTime;
    private LocalDateTime reviewedTime;
    private LocalDateTime publishedTime;
    /**
     * 批准后跨模块执行的永久请求号。
     */
    private String executionRequestId;
    /**
     * 失败后的人工续跑次数。
     */
    private Integer retryCount;
    /**
     * 最近一次跨模块撤回或 MRP 重算错误。
     */
    private String lastError;
    /**
     * 跨模块发布全部完成时间。
     */
    private LocalDateTime completedTime;
    private String factoryCode;
}
