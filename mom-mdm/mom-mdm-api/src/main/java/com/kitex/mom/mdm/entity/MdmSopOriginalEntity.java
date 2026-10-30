package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * SOP不可变原件元数据；storageKey永不出网。
 */
@Data
@TenantTable
@TableName("mdm_sop_original")
@EqualsAndHashCode(callSuper = true)
public class MdmSopOriginalEntity extends BaseEntity<MdmSopOriginalEntity> {

    /**
     * 安全显示文件名。
     */
    @Schema(description = "安全显示文件名")
    private String fileName;
    /**
     * 服务端校验的媒体类型。
     */
    @Schema(description = "服务端校验的媒体类型")
    private String mediaType;
    /**
     * 真实字节SHA256。
     */
    @Schema(description = "真实字节SHA256")
    private String sha256;
    /**
     * 原件字节数。
     */
    @Schema(description = "原件字节数")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long byteSize;
    /**
     * 私有存储随机对象名，不含客户端路径。
     */
    @Schema(description = "私有存储随机对象名，不含客户端路径")
    @JsonIgnore
    private String storageKey;
    /**
     * 上传人员实名ID。
     */
    @Schema(description = "上传人员实名ID")
    private String uploadedBy;
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

