package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * MPS/APS 顺序相关换型规则。
 *
 * <p>产线范围用于MPS/RCCP粗能力换型评估；工位、设备或工装范围用于APS有限能力排程。
 * 规则描述相邻产品族从 A 切换到 B 时的清洁、调整占用以及禁排约束，正式计划只使用
 * 处于有效期内的启用版本。</p>
 */
@Data
@TenantTable
@TableName("mdm_changeover_rule")
@EqualsAndHashCode(callSuper = true)
@Schema(name = "MdmChangeoverRule", description = "MPS/APS顺序相关换型规则及相邻产品族禁排矩阵")
public class MdmChangeoverRuleEntity extends BaseEntity<MdmChangeoverRuleEntity> {

    @Schema(description = "规则编号；同一租户、工厂内唯一", example = "COR-20260918-001")
    private String ruleNo;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则版本号，从1开始递增", example = "1")
    private Integer versionNo;

    @Schema(description = "资源作用域：LINE=产线，STATION=工位，DEVICE=设备，TOOLING=工装",
            allowableValues = {"LINE", "STATION", "DEVICE", "TOOLING"})
    private String scopeType;

    @Schema(description = "作用资源编码")
    private String resourceCode;

    @Schema(description = "作用资源名称快照")
    private String resourceName;

    @Schema(description = "限定工序编码；为空表示该资源上的全部工序；产线范围必须为空")
    private String processCode;

    @Schema(description = "限定工序名称快照")
    private String processName;

    @Schema(description = "前序产品族编码，必须引用当前工厂已启用的排产产品族")
    private String fromFamilyCode;

    @Schema(description = "前序产品族名称快照")
    private String fromFamilyName;

    @Schema(description = "后序产品族编码，必须引用当前工厂已启用的排产产品族")
    private String toFamilyCode;

    @Schema(description = "后序产品族名称快照")
    private String toFamilyName;

    @Schema(description = "清洁占用分钟数，必须大于等于0", example = "15")
    private Long cleaningMinutes;

    @Schema(description = "调整或装夹占用分钟数，必须大于等于0", example = "20")
    private Long setupMinutes;

    @Schema(description = "是否禁止两个产品族在该资源上相邻排产")
    private Boolean forbidden;

    @Schema(description = "规则匹配优先级，数值越小越优先", example = "100")
    private Integer priority;

    @Schema(description = "规则生效时间；为空表示立即生效")
    private LocalDateTime validStartTime;

    @Schema(description = "规则失效时间；为空表示长期有效")
    private LocalDateTime validEndTime;

    @Schema(description = "规则状态",
            allowableValues = {"DRAFT", "ACTIVE", "SUSPENDED", "RETIRED"})
    private String status;

    @Schema(description = "启用复核人账号")
    private String approvedBy;

    @Schema(description = "启用复核人姓名快照")
    private String approvedByName;

    @Schema(description = "启用复核时间")
    private LocalDateTime approvedTime;

    @Schema(description = "暂停或退役原因")
    private String closeReason;

    @Schema(description = "暂停或退役操作人账号")
    private String closedBy;

    @Schema(description = "暂停或退役操作人姓名快照")
    private String closedByName;

    @Schema(description = "暂停或退役时间")
    private LocalDateTime closedTime;

    @Schema(description = "规则备注")
    private String remark;

    @Schema(description = "所属工厂编码，由当前工厂上下文自动填充", accessMode = Schema.AccessMode.READ_ONLY)
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @Schema(description = "换型总占用分钟，等于清洁分钟数与调整分钟数之和", accessMode = Schema.AccessMode.READ_ONLY)
    public Long getTotalMinutes() {
        return Math.max(0L, cleaningMinutes == null ? 0L : cleaningMinutes)
                + Math.max(0L, setupMinutes == null ? 0L : setupMinutes);
    }
}
