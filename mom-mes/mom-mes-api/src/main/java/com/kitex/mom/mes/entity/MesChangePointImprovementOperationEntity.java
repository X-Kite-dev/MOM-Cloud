package com.kitex.mom.mes.entity;

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
 * 变化点改善来源单的不可变链接履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_change_point_improvement_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点改善链接履历")
public class MesChangePointImprovementOperationEntity extends BaseEntity<MesChangePointImprovementOperationEntity> {
    private String requestId;
    private Long improvementId;
    private String improvementNo;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
