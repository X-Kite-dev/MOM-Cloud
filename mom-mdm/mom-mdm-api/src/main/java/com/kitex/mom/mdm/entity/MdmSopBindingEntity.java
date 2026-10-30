package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 精确路线节点的正式SOP绑定，不替换旧可变附件。
 */
@Data
@TenantTable
@TableName("mdm_sop_binding")
@EqualsAndHashCode(callSuper = true)
public class MdmSopBindingEntity extends BaseEntity<MdmSopBindingEntity> {

    /**
     * 工艺路线编码。
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;
    /**
     * 稳定路线节点码。
     */
    @Schema(description = "稳定路线节点码")
    private String routeNodeCode;
    /**
     * 主工序编码，仅用于归属核对。
     */
    @Schema(description = "主工序编码，仅用于归属核对")
    private String processCode;
    /**
     * 绑定版本族编号。
     */
    @Schema(description = "绑定版本族编号")
    private String sopNo;
    /**
     * 精确正式版本ID。
     */
    @Schema(description = "精确正式版本ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long versionId;
    /**
     * 绑定时版本内容指纹。
     */
    @Schema(description = "绑定时版本内容指纹")
    private String versionHash;
    /**
     * BOUND或UNBOUND；解绑保留历史。
     */
    @Schema(description = "BOUND或UNBOUND；解绑保留历史")
    private String status;
    /**
     * 绑定修订指纹。
     */
    @Schema(description = "绑定修订指纹")
    private String bindingHash;
    /**
     * 所属工厂编码。
     */
    @Schema(description = "所属工厂编码")
    private String factoryCode;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}

