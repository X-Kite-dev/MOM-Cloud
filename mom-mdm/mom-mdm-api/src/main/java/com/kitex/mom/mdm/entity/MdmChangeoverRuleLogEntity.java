package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 换型规则状态迁移和版本操作的不可变审计履历。
 */
@Data
@TenantTable
@TableName("mdm_changeover_rule_log")
@EqualsAndHashCode(callSuper = true)
@Schema(name = "MdmChangeoverRuleLog", description = "换型规则编制、启用、暂停和退役的审计履历")
public class MdmChangeoverRuleLogEntity extends BaseEntity<MdmChangeoverRuleLogEntity> {

    @Schema(description = "换型规则主键")
    private Long ruleId;

    @Schema(description = "操作类型，如 CREATE、UPDATE、ACTIVATE、SUSPEND、RETIRE")
    private String action;

    @Schema(description = "操作前状态")
    private String fromStatus;

    @Schema(description = "操作后状态")
    private String toStatus;

    @Schema(description = "操作原因或复核意见")
    private String reason;

    @Schema(description = "操作人账号")
    private String operatedBy;

    @Schema(description = "操作人姓名快照")
    private String operatedByName;

    @Schema(description = "操作时间")
    private LocalDateTime operatedTime;

    @Schema(description = "操作前规则JSON快照")
    private String beforeSnapshot;

    @Schema(description = "操作后规则JSON快照")
    private String afterSnapshot;

    @Schema(description = "所属工厂编码，由当前工厂上下文自动填充", accessMode = Schema.AccessMode.READ_ONLY)
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
