package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** OMS订单变更不可覆盖的版本头与前后事实。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_order_change_version")
public class OmsOrderChangeVersionEntity extends BaseEntity<OmsOrderChangeVersionEntity> {
    private String orderCode;
    private Long reviewId;
    private Integer versionNo;
    private String submitRequestId;
    private String requestFingerprint;
    private String beforeSnapshot;
    private String proposedSnapshot;
    private String afterSnapshot;
    /** SUBMITTED、APPROVED、REJECTED、CANCELLED、SUPERSEDED。 */
    private String status;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime submittedTime;
    private String reviewRequestId;
    private String reviewFingerprint;
    private String reviewRemark;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
