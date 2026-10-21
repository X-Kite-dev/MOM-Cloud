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
 * 智能 APS 不可变资源快照。
 *
 * <p>快照汇总 PLAN、MDM、WMS、MES、TPM、QMS 与 PMS 的版本化证据，为计算、A2 和 A3 提供统一依据。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_aps_resource_snapshot")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "智能 APS 不可变资源快照")
public class PlnApsResourceSnapshotEntity extends BaseEntity<PlnApsResourceSnapshotEntity> {

    @Schema(description = "所属 APS 案件主键")
    private Long caseId;

    @Schema(description = "资源快照编号")
    private String snapshotNo;

    @Schema(description = "案件内快照版本")
    private Integer versionNo;

    @Schema(description = "资源取证永久幂等请求号")
    private String requestId;

    @Schema(description = "资源取证请求内容指纹")
    private String requestFingerprint;

    @Schema(description = "本次排产范围指纹")
    private String scopeFingerprint;

    @JsonIgnore
    @Schema(description = "各模块规范化资源证据摘要（JSON），默认不直接输出")
    private String sourceSummary;

    @JsonIgnore
    @Schema(description = "跨模块证据引用集合（JSON），默认不直接输出")
    private String evidenceRefs;

    @Schema(description = "就绪结论：READY、WARNING、BLOCKED 或 STALE")
    private String readinessStatus;

    @Schema(description = "各来源模块采集状态摘要（JSON）")
    private String moduleStatusSummary;

    @Schema(description = "关键证据是否完整")
    private Boolean complete;

    @Schema(description = "快照是否已因业务事实变化而过期")
    private Boolean stale;

    @Schema(description = "快照过期原因")
    private String staleReason;

    @Schema(description = "资源取证操作人编码")
    private String capturedBy;

    @Schema(description = "资源取证操作人名称")
    private String capturedByName;

    @Schema(description = "资源证据获取完成时间")
    private LocalDateTime capturedTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
