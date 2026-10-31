package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * CAPA历史经验引用的不可变业务回执，引用≠验证结论。
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("qms_capa_experience_reference")
public class QmsCapaExperienceReferenceEntity extends BaseEntity<QmsCapaExperienceReferenceEntity> {
    /**
     * 永久原请求和包含实际人员的完整命令指纹。
     */
    private String requestId;
    private String commandHash;
    private String actorId;
    /**
     * 目标案件与当时周期/全量源指纹。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long capaId;
    private Integer cycleNo;
    private String caseFingerprint;
    /**
     * AI反查的精确候选、知识版和不可变批准谱系。
     */
    private String experienceId;
    private String documentId;
    private String documentContentHash;
    private String sourceProofJson;
    /**
     * 人工适用判断必须独立于模型建议记录。
     */
    private String applicabilityReview;
    private String differences;
    private String exclusionReview;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
    /** 业务主键按文本传输，避免Snowflake在浏览器JSON数字中丢失精度。 */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 与引用所属案件、工厂一并按精确身份校验。 */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }

}
