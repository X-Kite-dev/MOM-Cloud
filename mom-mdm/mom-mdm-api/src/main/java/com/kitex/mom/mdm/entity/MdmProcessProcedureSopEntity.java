package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工艺路线-工序-SOP 表
 *
 * @author kitex
 * @date 2025-03-17 16:27:09
 */
@Data
@TenantTable
@TableName("mdm_process_procedure_sop")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线-工序-SOP 表")
public class MdmProcessProcedureSopEntity extends BaseEntity<MdmProcessProcedureSopEntity> {

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 路线内稳定节点身份；主工序重复出现时各次配置相互独立。
     */
    @Schema(description = "工艺路线节点编号，由服务端解析真实节点")
    private String routeNodeCode;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 原始文件名
     */
    @Schema(description = "原始文件名")
    private String name;

    /**
     * 文件名
     */
    @Schema(description = "文件名")
    private String fileName;

    /**
     * url
     */
    @Schema(description = "url")
    private String url;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
