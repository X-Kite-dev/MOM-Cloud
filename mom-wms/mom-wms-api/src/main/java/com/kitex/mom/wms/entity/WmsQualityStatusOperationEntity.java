package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量库存隔离、处置和实物执行账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_quality_status_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsQualityStatusOperationEntity extends BaseEntity<WmsQualityStatusOperationEntity> {
    private String requestId;
    private String holdNo;
    private String sourceType;
    private String sourceId;
    private String action;
    private String matCode;
    private String matBatch;
    /**
     * 冻结数量的库存单位，防止同物料批次跨单位合并。
     */
    private String matUnit;
    private String supplierCode;
    private String warehouseCode;
    private String siteCode;
    private String containerCode;
    private BigDecimal requestedQuantity;
    private BigDecimal affectedQuantity;
    private String affectedStockIds;
    /** 本次处置已完整记录拆分；历史事务默认false，不能用于整行无余量证明。 */
    private Boolean splitRecorded;
    /** 本次处置新建的余量库存行ID，逗号分隔；无拆分时为空。 */
    private String splitRemainderStockIds;
    /** 原库存行、余量库存行及拆分数量的同事务映射；历史事务不补造。 */
    private String splitLineageJson;
    /** 实际完成库存拆分/状态写入的时间，不随仓库后续执行更新。 */
    private LocalDateTime splitRecordedAt;
    private String status;
    private String reason;
    private String resultMessage;
    private String executionRequestId;
    private String executionRef;
    private String executionRemark;
    private String executedBy;
    private String executedByName;
    private LocalDateTime executedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
