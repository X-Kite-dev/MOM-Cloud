package com.kitex.mom.mdm.entity;

import com.alibaba.excel.annotation.ExcelProperty;
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
 * 工艺路线
 *
 * @author kitex
 * @date 2024-10-30 14:36:59
 */
@Data
@TenantTable
@TableName("mdm_process_route")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线")
public class MdmProcessRouteEntity extends BaseEntity<MdmProcessRouteEntity> {

    /**
     * 路线图并发版本，由数据库递增；调用方不能直接写入。
     */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    @Schema(description = "路线图并发版本，只读")
    private Long graphRevision;

    /**
     * 节点图协议；历史空值保留，只有受控图保存入口可以升级。
     */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    @Schema(description = "图协议，ROUTE_NODE_GRAPH_V2；历史主工序图为空")
    private String graphSchemaVersion;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 产品规格型号
     */
    @Schema(description = "产品规格型号")
    private String productSpec;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String productUnit;

    /**
     * bom版本
     */
    @Schema(description = "bom版本")
    private String bomVersion;

    /**
     * 产线编码
     */
    @Schema(description = "产线编码")
    private String productLineCode;

    /**
     * 产线名称
     */
    @Schema(description = "产线名称")
    private String productLineName;

    /**
     * 生产速率(每小时生产个数)
     */
    @ExcelProperty(value = "生产速率(每小时生产个数)")
    private BigDecimal productRate;

    /**
     * 是否默认工艺路线
     */
    @Schema(description = "是否默认工艺路线")
    private Boolean isDefault;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 工序快照
     */
    @Schema(description = "工序快照")
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String procedureSnapshot;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
