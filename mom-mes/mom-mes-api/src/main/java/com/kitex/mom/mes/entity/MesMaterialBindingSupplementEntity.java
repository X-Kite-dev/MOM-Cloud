package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 同一初始批次来源池的永久追加证据；不修改初始绑定。
 */
@Data
@TenantTable
@TableName("mes_material_binding_supplement")
@EqualsAndHashCode(callSuper = true)
public class MesMaterialBindingSupplementEntity extends BaseEntity<MesMaterialBindingSupplementEntity> {
    private String factoryCode;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private String routeNodeCode;
    private String productBatchNo;
    private String requestId;
    private String requestHash;
    private String previewFingerprint;
    private String supplementJson;
    private String supplementFingerprint;
    private String previousFingerprint;
    private Integer sequenceNo;
    private Long initialBindingId;
    private String operatorCode;
    private Long arrangeId;
    private Long operatorId;
    private LocalDateTime confirmedAt;
}
