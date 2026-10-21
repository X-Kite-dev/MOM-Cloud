package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 智能 APS 排产案件头。
 *
 * <p>案件只编排五阶段流程并关联现有 APS 情景、候选和效果回评，不复制排程结果明细。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_aps_case")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "智能 APS 排产案件头")
public class PlnApsCaseEntity extends BaseEntity<PlnApsCaseEntity> {

    @Schema(description = "案件编号")
    private String caseNo;

    @Schema(description = "案件类型")
    private String caseType;

    @Schema(description = "案件标题")
    private String title;

    @Schema(description = "案件生命周期状态")
    private String caseStatus;

    @Schema(description = "当前五阶段代码")
    private String currentStage;

    @Schema(description = "当前阶段内步骤代码")
    private String currentStep;

    @Schema(description = "案件负责人编码")
    private String ownerCode;

    @Schema(description = "案件负责人名称")
    private String ownerName;

    @Schema(description = "启动案件的永久幂等请求号")
    private String launchRequestId;

    @Schema(description = "服务端重采来源后形成的启动内容指纹")
    private String launchFingerprint;

    @Schema(description = "同一业务来源的案件修订号")
    private Integer sourceVersion;

    @Schema(description = "被本案件替代的前序案件主键")
    private Long previousCaseId;

    @Schema(description = "工厂计划权威策略主键")
    private Long planningPolicyId;

    @Schema(description = "工厂计划权威策略版本")
    private Integer planningPolicyVersion;

    @Schema(description = "工厂计划权威策略内容指纹")
    private String planningPolicyFingerprint;

    @Schema(description = "MRP 权威模式：ERP_MRP 或 MOM_MRP")
    private String mrpAuthority;

    @Schema(description = "正式计划合同主键")
    private Long formalPlanContractId;

    @Schema(description = "正式计划合同编号")
    private String formalPlanContractNo;

    @Schema(description = "正式计划合同版本")
    private Integer formalPlanContractVersion;

    @Schema(description = "正式计划合同内容指纹")
    private String formalPlanContractFingerprint;

    @Schema(description = "当前生效资源快照主键")
    private Long resourceSnapshotId;

    @Schema(description = "当前生效资源快照内容指纹")
    private String resourceFingerprint;

    @Schema(description = "复用的 APS 多候选情景主键")
    private Long scenarioId;

    @Schema(description = "A2 选中的 APS 候选任务主键")
    private Long selectedTaskId;

    @Schema(description = "已发布方案对应的执行效果回评主键")
    private Long effectReviewId;

    @Schema(description = "MES 下达汇总状态")
    private String dispatchStatus;

    @Version
    @Schema(description = "案件乐观锁版本，每次受控推进递增")
    private Integer workflowVersion;

    @Schema(description = "当前案件聚合上下文指纹，用于拦截过期操作卡")
    private String contextFingerprint;

    @Schema(description = "取消、关闭或被替代的业务原因")
    private String closeReason;

    @Schema(description = "案件关闭时间")
    private LocalDateTime closedTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
