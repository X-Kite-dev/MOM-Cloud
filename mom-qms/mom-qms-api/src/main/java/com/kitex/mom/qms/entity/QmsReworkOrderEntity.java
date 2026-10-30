package com.kitex.mom.qms.entity;

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
@TableName(value = "qms_rework_order", schema = "mom_qms")
@Schema(description = "质量返工与复检协调单")
public class QmsReworkOrderEntity extends BaseEntity<QmsReworkOrderEntity> {
    @Schema(description = "跨QMS、MES永久返工请求号")
    private String requestId;

    @Schema(description = "来源质检类型：PROCEDURE或PRODUCT")
    private String inspectionType;

    private Long inspectionTaskId;
    private Long handleRecordId;

    @Schema(description = "原MES生产工单编码")
    private String originalMesTaskCode;

    @Schema(description = "原质检实际来源MES工序任务编码；用于精确解析稳定节点")
    private String originalMesProduceTaskCode;

    @Schema(description = "质量处置要求执行的返工主工序")
    private String reworkProcessCode;

    private String matCode;
    private String matName;
    private String matSpec;
    private String batchNo;
    private BigDecimal quantity;
    private String reason;
    private String status;
    private String mesTaskCode;
    private BigDecimal mesCompletedQuantity;
    private Long reinspectionTaskId;
    private String reinspectionStatus;
    private Integer dispatchCount;
    private Integer callbackCount;
    private Integer resultSyncCount;
    private String lastError;
    private String initiatedBy;
    private String initiatedByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime initiatedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime mesCompletedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reinspectionCreatedTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime resultSyncTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
