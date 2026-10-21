package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 缺料局部重排操作审计记录。
 *
 * <p>记录方案生成、选案、发布、取消和回退等关键状态变化。该表只追加审计事实，不承担方案当前状态。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_shortage_replan_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "缺料局部重排操作审计记录")
public class PlnShortageReplanOperationEntity extends BaseEntity<PlnShortageReplanOperationEntity> {
    @Schema(description = "所属重排方案主键")
    private Long caseId;

    @Schema(description = "触发本次操作的业务幂等号")
    private String requestId;

    @Schema(description = "操作类型", allowableValues = {"PROPOSE", "SELECT", "PUBLISH", "CANCEL", "ROLLBACK"})
    private String operationType;

    @Schema(description = "操作前方案状态")
    private String beforeStatus;

    @Schema(description = "操作后方案状态")
    private String afterStatus;

    @Schema(description = "操作结论和关键业务说明")
    private String content;

    @Schema(description = "操作人编码")
    private String operatorCode;

    @Schema(description = "操作人名称")
    private String operatorName;

    @Schema(description = "操作时刻")
    private LocalDateTime operationTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
