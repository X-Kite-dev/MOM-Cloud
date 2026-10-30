package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 每次拣配的作业证据。完成出库后仍保留，与正式出库流水分开计数。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_picking_event")
public class WmsPickingEventEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private String factoryCode;
    private String warehouseCode;
    private String siteCode;

    private Long billId;
    private Long billDetailId;
    private String billNo;
    private Long sourceStockId;
    private Long sourceInBillId;
    private Long sourceInBillDetailId;
    private String sourceMesInboundRequestId;

    private String matCode;
    private String matBatch;
    private String matBarcode;
    private Double matNum;
    private String matUnit;

    private String stockOwnerType;
    private String stockOwnerCode;
    private String sourceBusinessNo;
    private Boolean serialManaged;

    private LocalDateTime pickedAt;
}
