package com.kitex.mom.mdm.vo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonInclude;
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
     * 路线内稳定节点身份，不能作为主工序能力编号。
     */
    @Schema(description = "稳定路线节点编号")
    private String routeNodeCode;

    /**
     * 精确节点前后关系，与主工序编码关系分开。
     */
    @Schema(description = "前置路线节点编号集合")
    private String[] preRouteNodeCodes;

    @Schema(description = "后置路线节点编号集合")
    private String[] nextRouteNodeCodes;

    /**
     * 来源可为任意祖先节点；MOM_NODE_TIMING_V1。
     */
    @Schema(description = "跨节点时间规则JSON")
    private String nodeTimingRulesJson;

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

    /**
     * 目标工序的跨工序自然时间规则；下达时冻结，空表示未配置。
     */
    @Schema(description = "跨工序时间规则JSON，MOM_PROCESS_TIMING_V1")
    private String timingRulesJson;

    @Schema(description = "允许达到转移批量后与后序重叠执行")
    private Boolean overlapEnabled;

    @Schema(description = "放行后序开工的最小合格转移批量")
    private BigDecimal transferBatchQuantity;

    /**
     * 类型 一物一码/数量
     */
    @Schema(description = "类型 一物一码/数量")
    private String type;

    /**
     * 工程发布冻结的工序质检开关。历史未配置时省略，保持原工程包的比对口径。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "工序质检：1启用，0关闭，空表示历史未配置")
    private String isInspection;

    @Schema(description = "工艺路线下工序关联的投料列表")
    private List<MdmProcessProcedureMatEntity> mdmProcessProcedureMatList;

    @Schema(description = "工艺路线下工序关联的SOP列表")
    private List<MdmProcessProcedureSopEntity> mdmProcessProcedureSopList;

    @Schema(description = "工艺路线下工序关联的工位列表")
    private List<MdmProcessProcedureWorkVO> mdmProcessProcedureWorkList;

}
