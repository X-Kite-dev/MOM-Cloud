package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 原CAPA分周期根因假设与独立验证；AI来源仅为可选非持久化追溯摘要。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_capa_cause")
@EqualsAndHashCode(callSuper = true)
public class QmsCapaCauseEntity extends BaseEntity<QmsCapaCauseEntity> {
    private String causeNo;
    private String requestId;
    private Long capaId;
    private Integer cycleNo;
    private String causeType;
    private String analysisMethod;
    private String causeDescription;
    private String evidence;
    private String proposedBy;
    private String proposedByName;
    private LocalDateTime proposedTime;
    private String verificationStatus;
    private String verifierCode;
    private String verifierName;
    private LocalDateTime verificationTime;
    private String verificationRemark;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /** 原AI引用永久请求号；可选本地追溯摘要，不属于原根因物理列。 */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String aiSourceRequestId;

    /** 原质量运行完整主键；未安装AI专项或人工根因时不返回。 */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String aiSourceRunId;

    /** 原结构化分析产物完整主键；只来自本地ACCEPTED账。 */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String aiSourceArtifactId;

    /** 原保存分析内的唯一假设编号，不从模型自由回答猜测。 */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String aiSourceHypothesisId;
}
