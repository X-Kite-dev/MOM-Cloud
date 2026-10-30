package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工艺路线-工序-物料 表
 *
 * @author kitex
 * @date 2025-03-17 16:17:55
 */
@Data
@TenantTable
@TableName("mdm_process_procedure_mat")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线-工序-物料 表")
public class MdmProcessProcedureMatEntity extends BaseEntity<MdmProcessProcedureMatEntity> {

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
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String materialCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String materialName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String materialSpec;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal quantity;

    /**
     * 投料顺序
     */
    @Schema(description = "投料顺序")
    private Integer step;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
