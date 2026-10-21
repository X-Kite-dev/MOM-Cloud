package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 成品质量判定逆向授权的不可变、幂等操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("qms_finished_goods_decision_reversal_operation")
public class QmsFinishedGoodsDecisionReversalOperationEntity
        extends BaseEntity<QmsFinishedGoodsDecisionReversalOperationEntity> {
    private Long reversalId;
    private String requestId;
    private String commandFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
