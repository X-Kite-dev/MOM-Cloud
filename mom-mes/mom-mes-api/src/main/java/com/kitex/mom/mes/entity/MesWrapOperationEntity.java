package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("mes_wrap_operation")
@Schema(description = "包装拆箱/重组不可变操作审计")
public class MesWrapOperationEntity extends BaseEntity<MesWrapOperationEntity> {

    private String requestId;
    private String operationType;
    private String sourceWrapCodes;
    private String targetWrapCode;
    private BigDecimal quantity;
    private String reason;
    private String detailSnapshot;
    private String operatorCode;
    private String operatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime operationTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
