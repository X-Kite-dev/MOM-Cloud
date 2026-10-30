package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 一次批量订单变更复核的原子命令账，整批成功才落库。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_order_change_review_batch")
public class OmsOrderChangeReviewBatchEntity extends BaseEntity<OmsOrderChangeReviewBatchEntity> {
    private String requestId;
    private String requestFingerprint;
    private String reviewIdsSnapshot;
    private String reviewType;
    private String remark;
    private String operatorCode;
    private LocalDateTime completedTime;
}
