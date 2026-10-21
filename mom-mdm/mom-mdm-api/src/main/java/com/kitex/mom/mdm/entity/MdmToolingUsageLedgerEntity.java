package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@TenantTable
@TableName("mdm_tooling_usage_ledger")
@EqualsAndHashCode(callSuper = true)
public class MdmToolingUsageLedgerEntity extends BaseEntity<MdmToolingUsageLedgerEntity> {
    private String requestId;
    private String businessType;
    private String businessCode;
    private String toolingCode;
    private BigDecimal usage;
    private BigDecimal beforeUsedLife;
    private BigDecimal afterUsedLife;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
