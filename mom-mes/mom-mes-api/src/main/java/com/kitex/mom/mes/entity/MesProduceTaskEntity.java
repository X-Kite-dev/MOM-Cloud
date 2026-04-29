package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.common.query.annotation.QueryColumn;
import com.kitex.common.query.enums.QueryRuleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 生产工序任务 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产工序任务单")
@TableName("mes_produce_task")
public class MesProduceTaskEntity extends BaseEntity<MesProduceTaskEntity> {

    /**
     * 任务单号
     */
    @Schema(description = "任务单号")
    private String code;

    /**
     * 生产工单号
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 工艺路线id
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
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String routeCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String routeName;

    /**
     * 工序类型
     */
    @Schema(description = "工序类型")
    private String routeType;

    /**
     * 工序工作中心
     */
    @Schema(description = "工序工作中心")
    private String routeWorkCenter;

    /**
     * 工序顺序
     */
    @Schema(description = "工序顺序")
    private Integer routeOrder;

    /**
     * 是否最后一道工序
     */
    @Schema(description = "是否最后一道工序")
    private Boolean lastRoute;

    /**
     * 工位编码
     */
    @Schema(description = "工位编码")
    private String worksiteCodes;

    /**
     * 工位名称
     */
    @Schema(description = "工位名称")
    private String worksiteNames;

    /**
     * 工位报工类型 自动 手动
     */
    @Schema(description = "工位报工类型 自动 手动")
    private String worksiteTypes;

    /**
     * 下达数量
     */
    @Schema(description = "下达数量")
    private BigDecimal noticeNum;

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
     * 不合格品数量
     */
    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

    /**
     * 下达日期
     */
    @Schema(description = "下达日期")
    private LocalDateTime noticeDate;

    /**
     * 状态 待派工 待接收 已接收 生产中 已完成 暂停中
     */
    @Schema(description = "状态 待派工 待接收 已接收 生产中 已完成 暂停中")
    @QueryColumn(type = QueryRuleEnum.EQ)
    private String status;

    /**
     * 实际开工时间
     */
    @Schema(description = "实际开工时间")
    private LocalDateTime startTime;

    /**
     * 实际完工时间
     */
    @Schema(description = "实际完工时间")
    private LocalDateTime finishTime;

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
     * 包装规则编码
     */
    @Schema(description = "包装规则编码")
    private String wrapCode;

    /**
     * 报工类型
     */
    @Schema(description = "报工类型")
    private String reportType;

    /**
     * 是否需要质检
     */
    @Schema(description = "是否需要质检")
    private String isInspection;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}


