package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 实物转换一次正式执行主账；提交后不可改删，不是生产报工产量。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_transform_execution")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换正式执行主账")
public class MesPhysicalTransformExecutionEntity extends BaseEntity<MesPhysicalTransformExecutionEntity> {
    private String factoryCode;
    private Long commandId;
    private Long holdId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long positionSurveyId;
    private String executionVersion;
    private String commandFingerprint;
    private String holdFingerprint;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String positionSurveyFingerprint;
    private String operationType;
    private String materialCode;
    private String inputUnit;
    private String outputUnit;
    private BigDecimal inputQuantity;
    private BigDecimal goodQuantity;
    private BigDecimal scrapQuantity;
    private String calculationSnapshot;
    private String calculationFingerprint;
    private String applySnapshot;
    private String applyFingerprint;
    private String receiptSnapshot;
    private String receiptFingerprint;
    private Long actorId;
    private String actorCode;
    private String status;
    private LocalDateTime appliedTime;
}
