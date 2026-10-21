package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mdm_bom_ecn_binding")
public class MdmBomEcnBindingEntity extends BaseEntity<MdmBomEcnBindingEntity> {
    private Long ecnId;
    private Long bomId;
    private String factoryCode;
}
