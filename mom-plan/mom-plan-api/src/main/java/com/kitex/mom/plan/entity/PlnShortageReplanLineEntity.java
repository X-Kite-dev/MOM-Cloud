package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 缺料局部重排工序差异明细。
 *
 * <p>按工序记录正式排程与候选排程之间的时间、资源和人员差异，同时保留发布与回退所需的不可变快照。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_shortage_replan_line")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "缺料局部重排工序差异明细")
public class PlnShortageReplanLineEntity extends BaseEntity<PlnShortageReplanLineEntity> {
    @Schema(description = "所属重排方案主键")
    private Long caseId;

    @Schema(description = "原正式排程工序主键")
    private Long originalOperationId;

    @Schema(description = "候选排程工序主键")
    private Long proposalOperationId;

    @Schema(description = "工序顺序")
    private Integer operationSeq;

    @Schema(description = "工序编码")
    private String processCode;
    /**
     * 原正式操作的冻结节点；旧案件保持空，不能按当前主数据补造。
     */
    private String sourceRouteNodeCode;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "是否因缺料直接受影响")
    private Boolean directAffected;

    @Schema(description = "是否为直接影响工序的下游联动工序")
    private Boolean downstreamAffected;

    @Schema(description = "方案动作", allowableValues = {"MOVE", "KEEP", "BLOCK"})
    private String actionType;

    @Schema(description = "工序不可移动或候选不可行的原因")
    private String blockedReason;

    @Schema(description = "原计划开始时刻")
    private LocalDateTime originalStartTime;

    @Schema(description = "原计划结束时刻")
    private LocalDateTime originalEndTime;

    @Schema(description = "候选计划开始时刻")
    private LocalDateTime proposedStartTime;

    @Schema(description = "候选计划结束时刻")
    private LocalDateTime proposedEndTime;

    @Schema(description = "原资源编码")
    private String originalResourceCode;

    @Schema(description = "候选资源编码")
    private String proposedResourceCode;

    @Schema(description = "原设备编码")
    private String originalDeviceCode;

    @Schema(description = "候选设备编码")
    private String proposedDeviceCode;

    @Schema(description = "原人员编码")
    private String originalEmployeeCode;

    @Schema(description = "候选人员编码")
    private String proposedEmployeeCode;

    @Schema(description = "原工装编码集合")
    private String originalToolingCodes;

    @Schema(description = "候选工装编码集合")
    private String proposedToolingCodes;

    @Schema(description = "相对原开始时刻的移动分钟数")
    private Long shiftMinutes;

    @Schema(description = "原正式工序完整快照（JSON）")
    private String originalOperationSnapshot;

    @Schema(description = "候选工序完整快照（JSON）")
    private String proposedOperationSnapshot;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
