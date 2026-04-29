package com.kitex.mom.common.seq.entity;


import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 序列号管理
 */
@Data
@TenantTable
@TableName("mdm_seq_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "序列号管理")
public class MdmSeqConfigEntity extends BaseEntity<MdmSeqConfigEntity> {

    /**
     * 业务号
     */
    @Schema(description = "业务号")
    private String bizName;

    /**
     * 是否每天从1开始
     */
    @Schema(description = "是否每天从1开始")
    private Boolean dayNew;

    /**
     * 前缀
     */
    @Schema(description = "前缀")
    private String prefix;

    /**
     * 号码序号长度
     */
    @Schema(description = "号码序号长度")
    private Integer numLength;

    /**
     * 是否启用
     */
    @Schema(description = "是否启用")
    private Boolean enable;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
