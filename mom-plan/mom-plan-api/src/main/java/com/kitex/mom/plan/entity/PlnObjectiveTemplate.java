package com.kitex.mom.plan.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("pln_objective_template")
public class PlnObjectiveTemplate extends BaseEntity<PlnObjectiveTemplate> {
    // 冻结快照使用独立Jackson实例，不能依赖HTTP全局Long序列化配置。
    @Override
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    public Long getId(){return super.getId();}
    @Override
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    public Long getTenantId(){return super.getTenantId();}
    private String factoryCode;
    private String templateCode;
    private String templateName;
    private String industryCode;
    private Integer versionNo;
    private String status;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long replacesId;
    private String profilesJson;
    private String reason;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long applicantId;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long reviewerId;
    private String reviewReason;
}
