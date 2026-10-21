package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_delivery_commitment")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购订单行供应商交期承诺版本")
public class PmsDeliveryCommitmentEntity extends BaseEntity<PmsDeliveryCommitmentEntity> {
    private String commitmentNo;
    private Long pmsOrderId;
    private Long orderDetailId;
    private String pmsOrderNo;
    private String pmsPlanNo;
    private String supplierCode;
    private String supplierName;
    private String matCode;
    private String matName;
    private String matUnit;
    private Integer versionNo;
    /**
     * ACTIVE/SUPERSEDED/CANCELLED
     */
    private String status;
    private BigDecimal totalCommittedNum;
    private BigDecimal orderNumSnapshot;
    private BigDecimal arrivalNumBaseline;
    /**
     * SUPPLIER_PORTAL/EDI/BUYER_CONFIRMED/OFFLINE_DOCUMENT
     */
    private String sourceType;
    private String evidenceRef;
    private String changeReason;
    private String confirmerCode;
    private String confirmerName;
    private LocalDateTime confirmedTime;
    private Long supersededById;
    private String cancelledBy;
    private String cancelledByName;
    private LocalDateTime cancelledTime;
    private String cancelReason;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
    @TableField(exist = false)
    private List<PmsDeliveryCommitmentItemEntity> items;
}
