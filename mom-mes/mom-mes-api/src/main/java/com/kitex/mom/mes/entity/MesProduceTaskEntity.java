package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
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

    @Schema(description = "工单下达时冻结的工艺参数标准编码")
    private String processParameterStandardNo;

    @Schema(description = "工单下达时冻结的工艺参数标准版本")
    private Integer processParameterStandardVersion;

    @Schema(description = "仅包含本工序参数的冻结标准JSON")
    private String processParameterStandardSnapshot;

    @Schema(description = "本工序参数冻结JSON的SHA-256指纹")
    private String processParameterSnapshotFingerprint;

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
     * 下达时从原路线快照取得；历史任务不回填、不借用当前MDM身份。
     */
    @Schema(description = "来源路线稳定节点编码，历史任务允许为空")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceRouteNodeCode;

    /**
     * 完整执行图合同独立于参数/SOP的节点身份；旧任务保持空值。
     */
    @Schema(description = "冻结执行图协议，NODE_EXECUTION_V1或历史空值")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String routeExecutionSchemaVersion;

    @Schema(description = "冻结前序节点CSV；空串表示起点，null表示旧执行合同")
    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NEVER)
    private String predecessorNodeCodes;

    @Schema(description = "冻结后序节点CSV；空串表示终点，null表示旧执行合同")
    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NEVER)
    private String successorNodeCodes;

    /**
     * 保留工程节点时间原文，不替换旧时间证据及其解释协议。
     */
    @Schema(description = "冻结节点时间规则JSON")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String nodeTimingRulesJson;

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

    @Schema(description = "前置工序编码，多个逗号分隔；新执行图起点须保留空串")
    @TableField(insertStrategy = FieldStrategy.NOT_NULL)
    private String predecessorCodes;

    @Schema(description = "后置工序编码，多个逗号分隔；新执行图终点须保留空串")
    @TableField(insertStrategy = FieldStrategy.NOT_NULL)
    private String successorCodes;

    @Schema(description = "允许达到转移批量后放行后序")
    private Boolean overlapEnabled;

    @Schema(description = "放行后序开工的最小合格转移批量")
    private BigDecimal transferBatchQuantity;

    @Schema(description = "达到放行条件后仍需等待的转序分钟")
    private Long transferMinutes;

    @Schema(description = "本工序实际达到数量和转序等待条件的后序放行时间")
    private LocalDateTime transferReleasedTime;

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

    @Schema(description = "APS选定工位编码")
    private String plannedWorksiteCode;
    private String plannedWorksiteName;
    @Schema(description = "APS选定的工位并行槽位")
    private Integer plannedResourceSlotNo;
    @Schema(description = "APS跨工位共享资源组编码")
    private String plannedResourceGroupCode;
    private String plannedResourceGroupName;
    private Integer plannedResourceGroupCapacity;
    private Integer plannedResourceGroupSlotNo;
    @Schema(description = "APS选定设备编码")
    private String plannedDeviceCode;
    private String plannedDeviceName;
    @Schema(description = "APS选定员工编码")
    private String plannedWorkerCode;
    private String plannedWorkerName;
    @Schema(description = "APS选定工装编码，多个逗号分隔")
    private String plannedToolingCodes;
    private String plannedToolingNames;
    @Schema(description = "APS工装能力、兼容性和不可用窗口证据JSON")
    private String toolingAllocationSnapshot;
    @Schema(description = "APS工装寿命预占JSON，键为工装编码")
    private String toolingLifeUsageSnapshot;
    private LocalDateTime plannedStartTime;
    private LocalDateTime plannedEndTime;
    @Schema(description = "该工序APS锁定证据快照JSON")
    private String apsOperationSnapshot;

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

    @Schema(description = "质量已放行完成数量")
    private BigDecimal qualityReleasedNum;

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
     * 目标工序的跨工序自然时间规则；下达时冻结，空表示未配置。
     */
    @Schema(description = "跨工序时间规则JSON，MOM_PROCESS_TIMING_V1")
    private String timingRulesJson;

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
