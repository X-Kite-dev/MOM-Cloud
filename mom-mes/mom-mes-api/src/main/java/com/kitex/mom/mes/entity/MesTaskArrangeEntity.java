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
 * 生产派工任务 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Schema(description = "生产工序任务单")
@TableName("mes_task_arrange")
@TenantTable
public class MesTaskArrangeEntity extends BaseEntity<MesTaskArrangeEntity> {

    /**
     * 派工单号
     */
    @Schema(description = "派工单号")
    private String code;

    /**
     * 任务单号
     */
    @Schema(description = "任务单号")
    private String produceTaskCode;

    /**
     * 生产工单号
     */
    @Schema(description = "生产工单号")
    private String taskCode;

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
     * 从真实工序任务出生合同复制；不从当前MDM或页面输入推断。
     */
    @Schema(description = "原工序任务的路线节点")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceRouteNodeCode;

    @Schema(description = "冻结执行图协议，NODE_EXECUTION_V1或历史空值")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String routeExecutionSchemaVersion;

    @Schema(description = "原工序任务的冻结前序节点CSV")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String predecessorNodeCodes;

    @Schema(description = "原工序任务的冻结后序节点CSV")
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String successorNodeCodes;

    @Schema(description = "原工序任务的冻结节点时间规则JSON")
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

    @Schema(description = "派工时冻结的报工模式；历史数据为空时兼容使用routeType")
    private String reportMode;

    @Schema(description = "派工时命中的生产执行模板ID")
    private Long executionTemplateId;

    @Schema(description = "派工时命中的生产执行模板编码")
    private String executionTemplateCode;

    @Schema(description = "派工时命中的生产执行模板版本")
    private Integer executionTemplateVersion;

    @Schema(description = "派工时冻结的完整生产执行策略JSON")
    private String executionPolicySnapshot;

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

    @Schema(description = "前置工序编码，多个逗号分隔")
    private String predecessorCodes;

    @Schema(description = "后置工序编码，多个逗号分隔")
    private String successorCodes;

    @Schema(description = "允许达到转移批量后放行后序")
    private Boolean overlapEnabled;

    @Schema(description = "放行后序开工的最小合格转移批量")
    private BigDecimal transferBatchQuantity;

    @Schema(description = "达到放行条件后仍需等待的转序分钟")
    private Long transferMinutes;


    /**
     * 是否最后一道工序
     */
    @Schema(description = "是否最后一道工序")
    private Boolean lastRoute;

    /**
     * 员工工号
     */
    @Schema(description = "员工工号")
    private String workerCode;

    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名")
    private String workerName;

    /**
     * 派工人工号
     */
    @Schema(description = "派工人工号")
    private String dispatcherCode;

    /**
     * 派工人姓名
     */
    @Schema(description = "派工人姓名")
    private String dispatcherName;

    /**
     * 派工班次编码
     */
    @Schema(description = "派工班次编码")
    private String shiftCode;

    /**
     * 派工班次名称
     */
    @Schema(description = "派工班次名称")
    private String shiftName;

    /**
     * 派工时间
     */
    @Schema(description = "派工时间")
    private LocalDateTime dispatcherTime;

    /**
     * 上岗时间
     */
    @Schema(description = "上岗时间")
    private LocalDateTime startTime;

    /**
     * 离岗时间
     */
    @Schema(description = "离岗时间")
    private LocalDateTime finishTime;

    /**
     * 上岗工时
     */
    @Schema(description = "上岗工时")
    private Integer finishHour;

    /**
     * 状态 新建、下达、已派工、生产中、完成
     */
    @Schema(description = "状态 新建、下达、已派工、生产中、完成")
    @QueryColumn(type = QueryRuleEnum.EQ)
    private String status;

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

    @Schema(description = "任务接收时确认的实际执行工位")
    private String actualWorksiteCode;

    @Schema(description = "APS计划选定工位编码")
    private String plannedWorksiteCode;
    private String plannedWorksiteName;

    @Schema(description = "APS计划选定员工编码")
    private String plannedWorkerCode;
    private String plannedWorkerName;

    @Schema(description = "APS计划开始时间")
    private LocalDateTime plannedStartTime;
    @Schema(description = "APS计划结束时间")
    private LocalDateTime plannedEndTime;
    @Schema(description = "APS计划设备编码")
    private String plannedDeviceCode;
    private String plannedDeviceName;
    @Schema(description = "APS选定的工位并行槽位")
    private Integer plannedResourceSlotNo;
    @Schema(description = "APS跨工位共享资源组编码")
    private String plannedResourceGroupCode;
    private String plannedResourceGroupName;
    private Integer plannedResourceGroupCapacity;
    private Integer plannedResourceGroupSlotNo;
    @Schema(description = "APS计划工装编码，多个逗号分隔")
    private String plannedToolingCodes;
    private String plannedToolingNames;
    @Schema(description = "APS工装能力、兼容性和不可用窗口证据JSON")
    private String toolingAllocationSnapshot;
    @Schema(description = "APS工装寿命预占JSON，键为工装编码")
    private String toolingLifeUsageSnapshot;
    @Schema(description = "派工时保留的APS工序证据快照JSON")
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
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long materialGateRecordId;

    /**
     * 不合格品数量
     */
    @Schema(description = "不合格品数量")
    private BigDecimal unPassNum;

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
     * 批次号
     */
    @Schema(description = "批次号")
    private String batchNo;

    /**
     * 包装规则编码
     */
    @Schema(description = "包装规则编码")
    private String wrapRuleCode;

    /**
     * 包装箱码
     */
    @Schema(description = "包装箱码")
    private String wrapCode;

    /**
     * 箱码容量
     */
    @Schema(description = "箱码容量")
    private String wrapCapacity;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
