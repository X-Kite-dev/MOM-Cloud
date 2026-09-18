package com.kitex.mom.mdm.vo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.entity.MdmProcessProcedureSopEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工艺路线和工序关联
 *
 * @author kitex
 * @date 2024-11-01 13:44:51
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线和工序关联")
public class MdmProcessRouteWorkingVO extends BaseEntity<MdmProcessRouteWorkingVO> {

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String processName;

    /**
     * 顺序
     */
    @Schema(description = "顺序")
    private Integer orderNum;

    /**
     * 前置工序编码
     */
    @Schema(description = "前置工序编码")
    private String[] preRouteCode;

    /**
     * 后置工序编码
     */
    @Schema(description = "后置工序编码")
    private String[] nextRouteCode;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 是否控制转序时间
     */
    @Schema(description = "是否控制转序时间")
    private Boolean timeCheck;

    /**
     * 转序时间要求（分钟）
     */
    @Schema(description = "转序时间要求（分钟）")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal timeVal;

    @Schema(description = "允许达到转移批量后与后序重叠执行")
    private Boolean overlapEnabled;

    @Schema(description = "放行后序开工的最小合格转移批量")
    private BigDecimal transferBatchQuantity;

    /**
     * 类型 一物一码/数量
     */
    @Schema(description = "类型 一物一码/数量")
    private String type;

    @Schema(description = "工艺路线下工序关联的投料列表")
    private List<MdmProcessProcedureMatEntity> mdmProcessProcedureMatList;

    @Schema(description = "工艺路线下工序关联的SOP列表")
    private List<MdmProcessProcedureSopEntity> mdmProcessProcedureSopList;

    @Schema(description = "工艺路线下工序关联的工位列表")
    private List<MdmProcessProcedureWorkVO> mdmProcessProcedureWorkList;

}
