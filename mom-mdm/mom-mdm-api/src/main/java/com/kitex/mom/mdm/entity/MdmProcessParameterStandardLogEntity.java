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
 * 工艺参数标准全生命周期审计记录。
 *
 * <p>记录草稿创建、修改、复制、发布、退役和删除操作的前后快照；记录只允许新增，
 * 数据库触发器禁止修改或删除。</p>
 */
@Data
@TenantTable
@TableName("mdm_process_parameter_standard_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺参数标准生命周期审计记录")
public class MdmProcessParameterStandardLogEntity extends BaseEntity<MdmProcessParameterStandardLogEntity> {
    @Schema(description = "关联的工艺参数标准版本ID")
    private Long standardId;

    @Schema(description = "操作类型：CREATE、UPDATE、COPY、RELEASE、RETIRE或DELETE")
    private String action;

    @Schema(description = "操作前状态")
    private String fromStatus;

    @Schema(description = "操作后状态")
    private String toStatus;

    @Schema(description = "操作原因")
    private String reason;

    @Schema(description = "操作人用户ID")
    private String operatedBy;

    @Schema(description = "操作人姓名快照")
    private String operatedByName;

    @Schema(description = "操作时间")
    private LocalDateTime operatedTime;

    @Schema(description = "操作前完整JSON快照")
    private String beforeSnapshot;

    @Schema(description = "操作后完整JSON快照")
    private String afterSnapshot;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
