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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_transfer_variance_line")
@EqualsAndHashCode(callSuper = true)
public class WmsTransferVarianceLineEntity extends BaseEntity<WmsTransferVarianceLineEntity> {
    private Long caseId;
    private Long sourceFlowId;
    private String materialCode;
    private String materialName;
    private String materialBatch;
    private String materialUnit;
    private BigDecimal issuedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal shortQuantity;
    private BigDecimal overQuantity;
    private BigDecimal heldOverQuantity;
    private Long policyId;
    private String policyCode;
    private String policySnapshot;
    private Boolean withinTolerance;
    private Boolean planReviewRequired;
    private Boolean qualityReviewRequired;
    private String targetSiteCode;
    private String targetSiteName;
    private String targetContainerCode;
    private String dispositionStatus;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
