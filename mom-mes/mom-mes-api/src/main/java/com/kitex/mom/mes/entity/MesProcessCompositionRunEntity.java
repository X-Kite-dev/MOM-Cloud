package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一次真实派工及不可变过程绑定；数量和执行状态始终读取原领域单据。
 */
@Data
@TenantTable
@TableName("mes_process_composition_run")
@EqualsAndHashCode(callSuper = true)
public class MesProcessCompositionRunEntity extends BaseEntity<MesProcessCompositionRunEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long actorId;
    private Long templateId;
    private Integer templateVersion;
    private String templateName;
    private String definitionSnapshot;
    private String nodesJson;
    private String taskCodesJson;
    private String factoryCode;
}
