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
@TableName("qms_capa_action")
@EqualsAndHashCode(callSuper = true)
public class QmsCapaActionEntity extends BaseEntity<QmsCapaActionEntity> {
    private String actionNo;
    private String requestId;
    private Long capaId;
    private Integer cycleNo;
    private String actionType;
    private String title;
    private String description;
    private String ownerCode;
    private String ownerName;
    private LocalDateTime dueTime;
    private String status;
    private String acceptanceRemark;
    private LocalDateTime acceptedTime;
    private String completionResult;
    private String evidence;
    private String completedBy;
    private String completedByName;
    private LocalDateTime completedTime;
    @TableField(exist = false)
    private Boolean overdueFlag;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
