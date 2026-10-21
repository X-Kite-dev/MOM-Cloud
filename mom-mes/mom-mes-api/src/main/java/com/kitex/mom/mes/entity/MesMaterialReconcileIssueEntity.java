package com.kitex.mom.mes.entity;

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
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mes_material_reconcile_issue")
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(description = "替代料预留与执行跨模块对账异常")
public class MesMaterialReconcileIssueEntity extends BaseEntity<MesMaterialReconcileIssueEntity> {
    private String issueKey;
    private String issueType;
    private String severity;
    private String title;
    private String description;
    private String reservationNo;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String requirementMatCode;
    private String actualMatCode;
    private String wmsStatus;
    private BigDecimal expectedNum;
    private BigDecimal actualNum;
    private BigDecimal varianceNum;
    /**
     * OPEN / CLAIMED / IGNORED / RESOLVED。
     */
    private String status;
    private String ownerCode;
    private String ownerName;
    private Integer occurrenceCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime firstFoundTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastFoundTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime resolvedTime;
    private String resolvedBy;
    private String handleReason;
    private String evidenceSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
