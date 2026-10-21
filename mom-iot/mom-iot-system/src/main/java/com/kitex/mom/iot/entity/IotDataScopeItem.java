package com.kitex.mom.iot.entity;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
@Data @Accessors(chain=true) @EqualsAndHashCode(callSuper=true)
@TenantTable @TableName("iot_data_scope_item")
public class IotDataScopeItem extends BaseEntity<IotDataScopeItem> {
    private Long claimId;
    private String sourceKind;
    private Long sourceId;
    private String sourceSnapshot;
    private String fingerprint;
    private String bindingState;
    private String factoryCode;
}

