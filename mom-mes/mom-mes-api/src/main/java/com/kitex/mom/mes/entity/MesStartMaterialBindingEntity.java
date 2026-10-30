package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 同一实际派工生产批次的不可变来源池；无更新生命周期。
 */
@Data
@TenantTable
@TableName("mes_start_material_binding")
@EqualsAndHashCode(callSuper = true)
public class MesStartMaterialBindingEntity extends BaseEntity<MesStartMaterialBindingEntity> {
    private String factoryCode;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private String routeNodeCode;
    private String productBatchNo;
    private String requestId;
    private String requestHash;
    private String previewFingerprint;
    private String bindingJson;
    private String bindingFingerprint;
    private String operatorCode;
    private Long arrangeId;
    private Long materialGateRecordId;
    private Long operatorId;
    private LocalDateTime startedAt;
}
