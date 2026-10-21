package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

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
}
