package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 智能 APS 结构化预检问题。
 *
 * <p>问题指向原业务模块的处理入口；智能 APS 展示和复核问题，但不重复维护主数据、库存、质量或设备事实。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_aps_readiness_issue")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "智能 APS 结构化预检问题")
public class PlnApsReadinessIssueEntity extends BaseEntity<PlnApsReadinessIssueEntity> {

    @Schema(description = "所属 APS 案件主键")
    private Long caseId;

    @Schema(description = "所属资源快照主键")
    private Long snapshotId;

    @Schema(description = "稳定的问题代码")
    private String issueCode;

    @Schema(description = "问题分类，如ENGINEERING、MATERIAL、RESOURCE、QUALITY或EXECUTION")
    private String category;

    @Schema(description = "严重度：INFO、WARNING 或 BLOCKER")
    private String severity;

    @Schema(description = "证据来源模块")
    private String sourceModule;

    @Schema(description = "证据来源对象类型")
    private String sourceType;

    @Schema(description = "证据来源对象主键")
    private Long sourceId;

    @Schema(description = "证据来源对象编码")
    private String sourceCode;

    @Schema(description = "证据来源业务版本")
    private String sourceVersion;

    @Schema(description = "证据来源内容指纹")
    private String sourceFingerprint;

    @Schema(description = "受影响对象类型")
    private String impactedObjectType;

    @Schema(description = "受影响对象主键")
    private Long impactedObjectId;

    @Schema(description = "受影响对象业务编码")
    private String impactedObjectCode;

    @Schema(description = "问题标题")
    private String title;

    @Schema(description = "问题说明和阻断依据")
    private String description;

    @Schema(description = "回到原业务模块处理问题的前端路由")
    private String resolutionRoute;

    @JsonIgnore
    @Schema(description = "处理路由的受控参数（JSON），默认不直接输出")
    private String resolutionParams;

    @Schema(description = "问题状态：OPEN、ACKNOWLEDGED、RESOLVED 或 OBSOLETE")
    private String issueStatus;

    @Schema(description = "最近复核人编码")
    private String verifiedBy;

    @Schema(description = "最近复核时间")
    private LocalDateTime verifiedTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
