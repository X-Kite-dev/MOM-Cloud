package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一次复盘的原数、新数与操作依据；只新增和查询，不原地修改。
 */
@Data
@TenantTable
@TableName("wms_inventory_recount_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "盘点复盘履历")
public class WmsInventoryRecountRecordEntity extends BaseEntity<WmsInventoryRecountRecordEntity> {
    private Long planId;
    private Long planDetailId;
    private String requestId;
    private Double previousNum;
    private Double recountNum;
    private String reason;
    private String operatorCode;
    private LocalDateTime operatedTime;

    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
