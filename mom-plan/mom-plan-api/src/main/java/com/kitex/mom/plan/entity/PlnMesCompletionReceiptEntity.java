package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PLAN对提交后MES完工通知的永久处理回执；不替代入库数量账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mes_completion_receipt")
@EqualsAndHashCode(callSuper = true)
public class PlnMesCompletionReceiptEntity extends BaseEntity<PlnMesCompletionReceiptEntity> {
    private Long outboxId;
    private String eventId;
    private String payloadFingerprint;
    private Long mesTaskId;
    private String mesTaskCode;
    private Integer completionVersion;
    private Integer sourceCount;
    private Long issueId;
    private String issueNo;
    private BigDecimal allocatedQuantity;
    private BigDecimal productionQuantity;
    private String sourceFingerprint;
    private String decision;
    private String statusBefore;
    private String statusAfter;
    private LocalDateTime recordedTime;
    private String factoryCode;
}
