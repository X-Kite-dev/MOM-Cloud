package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 每行是一个过程组合版本；发布后不修改定义。
 */
@Data
@TenantTable
@TableName("mes_process_composition_template")
@EqualsAndHashCode(callSuper = true)
public class MesProcessCompositionTemplateEntity extends BaseEntity<MesProcessCompositionTemplateEntity> {
    private String code;
    private String name;
    private Integer versionNo;
    private Long revisionNo;
    private String status;
    private String definitionJson;
    private String factoryCode;
}
