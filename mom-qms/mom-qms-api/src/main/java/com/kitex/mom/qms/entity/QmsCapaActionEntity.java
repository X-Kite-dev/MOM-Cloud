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

import java.time.LocalDateTime;

/** 原CAPA周期措施，执行责任和客观完成结果沿原质量流程保存。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_capa_action")
@EqualsAndHashCode(callSuper = true)
public class QmsCapaActionEntity extends BaseEntity<QmsCapaActionEntity> {
    private String actionNo;
    private String requestId;
    /** 所属原CAPA，按文本传输以保留跨页面定位精度。 */
    @JsonSerialize(using = ToStringSerializer.class)
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

    /** 数据库及Java仍为Long，仅JSON边界保持精确主键。 */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /** 与原案件和工厂一并核对的租户主键。 */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}
