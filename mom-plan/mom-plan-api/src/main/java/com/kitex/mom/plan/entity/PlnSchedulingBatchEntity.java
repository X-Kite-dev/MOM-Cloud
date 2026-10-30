package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_scheduling_batch")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "APS排产批次头；支持SINGLE、SPLIT、MERGED数量账")
public class PlnSchedulingBatchEntity extends BaseEntity<PlnSchedulingBatchEntity> {
    private Long taskId;
    private String batchNo;
    private String batchType;
    private Integer batchSeq;
    private Long primaryIssueId;
    private String primaryIssueNo;
    private String productCode;
    private String productName;
    private String routeCode;
    /**
     * 本批次唯一允许消费的已批准工程版本。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringReleaseId;
    /**
     * 与releaseId共同组成不可替换的生产版本对。
     */
    private String engineeringContentHash;
    private Integer engineeringVersionNo;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringRouteId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long engineeringBomId;
    private String engineeringBomVersion;
    private BigDecimal plannedQuantity;
    private Integer allocationCount;
    private String allocationSnapshot;
    private String sourceFingerprint;
    private String batchStatus;
    private LocalDate requestedStartDate;
    private LocalDateTime scheduledStartTime;
    private LocalDateTime scheduledEndTime;
    private Boolean lockStatus;
    private LocalDateTime lockTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
