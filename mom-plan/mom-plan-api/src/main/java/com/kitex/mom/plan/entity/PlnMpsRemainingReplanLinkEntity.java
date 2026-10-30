package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * MPS执行观察到剩余工序重排的不可变来源绑定，状态由权威局部案件决定。
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("pln_mps_remaining_replan_link")
public class PlnMpsRemainingReplanLinkEntity extends BaseEntity<PlnMpsRemainingReplanLinkEntity> {
    @Schema(description = "业务发起永久请求号")
    private String requestId;

    @Schema(description = "完整发起参数指纹，同号异参数拒绝")
    private String requestFingerprint;

    @Schema(description = "已独立核验的MPS兑现观察主键")
    private Long observationId;

    @Schema(description = "原始MPS时间桶行主键")
    private Long mpsLineId;

    @Schema(description = "唯一根生产计划主键")
    private Long issueId;

    @Schema(description = "唯一根生产计划编号")
    private String issueNo;

    @Schema(description = "既有正式排程来源情景主键")
    private Long sourceScenarioId;

    @Schema(description = "本次生成的三候选局部重排情景主键")
    private Long replanScenarioId;

    @Schema(description = "预览及发起时完整业务证据指纹")
    private String evidenceFingerprint;

    @Schema(description = "原观察、原承诺数量和逐工序PLAN/MES事实，不可改写")
    private String evidenceSnapshot;

    @Schema(description = "发起原因，不改变原MPS/工单数量")
    private String reason;

    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
