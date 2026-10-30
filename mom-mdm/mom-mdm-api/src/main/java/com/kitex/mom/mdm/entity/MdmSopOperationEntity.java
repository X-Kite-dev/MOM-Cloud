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
 * SOP永久命令回执与审计，同号异载荷不得重放。
 */
@Data
@TenantTable
@TableName("mdm_sop_operation")
@EqualsAndHashCode(callSuper = true)
public class MdmSopOperationEntity extends BaseEntity<MdmSopOperationEntity> {

    /**
     * 当前租户工厂内永久请求号。
     */
    @Schema(description = "当前租户工厂内永久请求号")
    private String requestId;
    /**
     * 动作、载荷和操作人SHA256。
     */
    @Schema(description = "动作、载荷和操作人SHA256")
    private String commandHash;
    /**
     * 业务动作。
     */
    @Schema(description = "业务动作")
    private String action;
    /**
     * 关联版本ID，原件上传时为空。
     */
    @Schema(description = "关联版本ID，原件上传时为空")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long versionId;
    /**
     * 真实操作人ID。
     */
    @Schema(description = "真实操作人ID")
    private String actorId;
    /**
     * 操作人显示快照。
     */
    @Schema(description = "操作人显示快照")
    private String actorName;
    /**
     * 操作前业务快照。
     */
    @Schema(description = "操作前业务快照")
    private String beforeJson;
    /**
     * 不可变回执JSON。
     */
    @Schema(description = "不可变回执JSON")
    private String receiptJson;
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

