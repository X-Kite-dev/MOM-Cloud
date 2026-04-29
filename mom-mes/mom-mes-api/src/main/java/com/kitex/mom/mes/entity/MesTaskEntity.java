package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.mes.dto.RouteProcessDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生产工单 实体类
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产工单")
@TableName("mes_task")
public class MesTaskEntity extends BaseEntity<MesTaskEntity> {

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String code;

    /**
     * 工单类型 成品计划 零件计划
     */
    @Schema(description = "工单类型 成品计划 零件计划")
    private String type;

    /**
     * 生产计划单号
     */
    @Schema(description = "生产计划单号")
    private String planNo;

    /**
     * 生产订单号
     */
    @Schema(description = "生产订单号")
    private String productionOrder;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 计划开始时间
     */
    @Schema(description = "计划开始时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartDate;

    /**
     * 计划结束时间
     */
    @Schema(description = "计划结束时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndDate;

    /**
     * 总需求数量
     */
    @Schema(description = "总需求数量")
    private BigDecimal noticeNum;

    /**
     * 日需求数量
     */
    @Schema(description = "日需求数量")
    private BigDecimal dayNeedNum;

    /**
     * 已排产数量
     */
    @Schema(description = "已排产数量")
    private BigDecimal plannedNum;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    /**
     * 成品入库量
     */
    @Schema(description = "成品入库量")
    private BigDecimal inStockNum;

    /**
     * 成品待入库量
     */
    @Schema(description = "成品待入库量")
    private BigDecimal unInStockNum;

    /**
     * 不合格品数量
     */
    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    /**
     * 已下达数量
     */
    @Schema(description = "已下达数量")
    private BigDecimal alreadyNoticeNum;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    /**
     * 状态 新建、下达、已派工、生产中、完成
     */
    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    private String status;

    /**
     * BOM ID
     */
    @Schema(description = "bomId")
    private Long bomId;

    /**
     * BOM快照
     */
    @Schema(description = "bom快照")
    private String bomSnapshot;

    /**
     * BOM版本
     */
    @Schema(description = "bom版本")
    private String bomVersion;

    /**
     * 工艺路线ID
     */
    @Schema(description = "工艺路线id")
    private Long processRouteId;

    /**
     * 工艺路线快照
     */
    @Schema(description = "工艺路线快照")
    private String processRouteSnapshot;

    /**
     * 工艺路线版本
     */
    @Schema(description = "工艺路线版本")
    private String processRouteCode;

    /**
     * 来源类型
     */
    @Schema(description = "来源类型")
    private String resourceType;

    /**
     * 来源ID
     */
    @Schema(description = "来源id")
    private String resourceId;

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
     * 包装编码
     */
    @Schema(description = "包装编码")
    private String wrapCode;

    /**
     * 包装容量
     */
    @Schema(description = "包装容量")
    private String wrapCapacity;

    /**
     * 批次号生成规则
     */
    @Schema(description = "批次号生成规则")
    private String batchRuleCode;

    /**
     * 流转卡绑定
     */
    @Schema(description = "流转卡绑定")
    private String transferCode;

    /**
     * 工序进度列表
     */
    @Schema(description = "工序进度列表")
    @TableField(exist = false)
    private List<RouteProcessDTO> processList;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

