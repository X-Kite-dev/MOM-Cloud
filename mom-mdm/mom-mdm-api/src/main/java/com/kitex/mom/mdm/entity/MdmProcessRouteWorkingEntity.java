package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.ArrayTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;

/**
 * 工艺路线和工序关联
 *
 * @author kitex
 * @date 2024-11-01 13:44:51
 */
@Data
@TenantTable
@TableName(value = "mdm_process_route_working", autoResultMap = true)
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺路线和工序关联")
public class MdmProcessRouteWorkingEntity extends BaseEntity<MdmProcessRouteWorkingEntity> {

    /**
     * 路线内稳定节点身份；服务端生成，保存和排序不变，不等同主工序编码。
     */
    @Schema(description = "稳定路线节点编号，只读；新增时由服务端生成")
    private String routeNodeCode;

    /**
     * 本次画布键，旧节点为RN身份，新节点为C-UUID；不落库。
     */
    @TableField(exist = false)
    @Schema(description = "本次节点键，V2保存必填")
    private String clientNodeKey;

    /**
     * 显式新增意图，避免删除重加被误认为原节点。
     */
    @TableField(exist = false)
    @Schema(description = "是否本次新增节点")
    private Boolean newNode;

    /**
     * 精确前置节点，V2权威关系；请求中可暂用本次C键。
     */
    @Schema(description = "前置路线节点编号集合")
    @TableField(updateStrategy = FieldStrategy.ALWAYS, jdbcType = JdbcType.ARRAY, typeHandler = ArrayTypeHandler.class)
    private String[] preRouteNodeCodes;

    /**
     * 后置节点由服务端根据前序派生，不能用主工序编码替代。
     */
    @Schema(description = "后置路线节点编号集合")
    @TableField(updateStrategy = FieldStrategy.ALWAYS, jdbcType = JdbcType.ARRAY, typeHandler = ArrayTypeHandler.class)
    private String[] nextRouteNodeCodes;

    /**
     * 独立节点时间规则，来源为任意祖先节点，版本MOM_NODE_TIMING_V1。
     */
    @Schema(description = "跨节点时间规则JSON")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String nodeTimingRulesJson;

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
     * 类型 一物一码/数量
     */
    @Schema(description = "类型 一物一码/数量")
    private String type;

    /**
     * 工序质检开关，1启用，0关闭，空表示历史未配置。
     */
    @Schema(description = "工序质检：1启用，0关闭，空表示历史未配置")
    private String isInspection;

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
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String timingRulesJson;

    @Schema(description = "允许达到转移批量后与后序重叠执行")
    private Boolean overlapEnabled;

    @Schema(description = "放行后序开工的最小合格转移批量")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal transferBatchQuantity;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
