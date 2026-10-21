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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_andon_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "安灯全生命周期不可变操作履历")
public class MesAndonOperationEntity extends BaseEntity<MesAndonOperationEntity> {

    private String requestId;
    private Long recordId;
    private String recordCode;
    private String operationType;
    private String beforeState;
    private String afterState;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    private String pauseOutcome;
    private String pauseContextJson;
    private String devicePauseReceiptJson;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
