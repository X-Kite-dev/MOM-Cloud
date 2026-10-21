package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 线边签收/冲销永久操作账，不通过通用CRUD修改。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_material_receipt")
public class MesMaterialReceiptEntity extends BaseEntity<MesMaterialReceiptEntity> {
    private Long noticeId;
    private Long pickingBillId;
    private Long wmsOutDetailId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private Long sourceReceiptId;
    private BigDecimal quantity;
    private BigDecimal beforeQuantity;
    private BigDecimal afterQuantity;
    private String siteCode;
    private String siteName;
    private String reason;
    private Long operatorId;
    private String operatorCode;
    private LocalDateTime operationTime;
    private String sourceSnapshot;
    private String stockFingerprint;
    private String factoryCode;
}
