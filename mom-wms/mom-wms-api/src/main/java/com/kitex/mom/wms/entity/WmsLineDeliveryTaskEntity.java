package com.kitex.mom.wms.entity;

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
import java.util.List;

/**
 * 生产发料从仓库到工位的线边配送任务。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_line_delivery_task")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "线边配送任务")
public class WmsLineDeliveryTaskEntity extends BaseEntity<WmsLineDeliveryTaskEntity> {
    private String taskNo;
    private Long outBillId;
    private String outBillNo;
    private String state;
    private Integer operationSeq;
    private String sourceFingerprint;
    private BigDecimal plannedNum;
    private BigDecimal deliveredNum;
    private BigDecimal exceptionNum;
    private BigDecimal recoveredNum;
    private String targetSiteCode;
    private String targetSiteName;
    private String priority;
    private LocalDateTime requiredArrivalTime;
    private String dispatchBy;
    private String dispatchByName;
    private LocalDateTime dispatchTime;
    private String dispatchReason;
    private String dispatchEvidence;
    private String transporterCode;
    private String transporterName;
    private LocalDateTime acceptedTime;
    private String transportMode;
    private String vehicleCode;
    private String containerCode;
    private String sealNo;
    private LocalDateTime departedTime;
    private String arrivalBy;
    private String arrivalByName;
    private LocalDateTime arrivedTime;
    private String arrivalEvidence;
    private String closeBy;
    private String closeByName;
    private LocalDateTime closedTime;
    private String closeSummary;
    private String closeEvidence;
    private String factoryCode;
    @TableField(exist = false)
    private List<WmsLineDeliveryDetailEntity> details;
    @TableField(exist = false)
    private List<WmsLineDeliveryOperationEntity> operations;
}
