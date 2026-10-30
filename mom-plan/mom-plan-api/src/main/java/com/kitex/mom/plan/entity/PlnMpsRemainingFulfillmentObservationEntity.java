package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 剩余分期的不可变兑现观察，原MPS观察及历史结论不覆盖。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mps_remaining_fulfillment_observation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "剩余分期的不可变兑现观察，原MPS观察及历史结论不覆盖")
public class PlnMpsRemainingFulfillmentObservationEntity extends BaseEntity<PlnMpsRemainingFulfillmentObservationEntity> {
    /**
     * 剩余分期兑现观察业务号。
     */
    @Schema(description = "剩余分期兑现观察业务号")
    private String observationNo;

    /**
     * 被冻结的剩余承诺案件版本。
     */
    @Schema(description = "被冻结的剩余承诺案件版本")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long commitmentId;

    /**
     * 精确原MPS桶ID。
     */
    @Schema(description = "精确原MPS桶ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mpsLineId;

    /**
     * 采集永久请求号。
     */
    @Schema(description = "采集永久请求号")
    private String requestId;

    /**
     * 采集规范载荷及实际实名指纹。
     */
    @Schema(description = "采集规范载荷及实际实名指纹")
    private String requestFingerprint;

    /**
     * 承诺日末截止容差，0至1440分钟。
     */
    @Schema(description = "承诺日末截止容差，0至1440分钟")
    private Integer toleranceMinutes;

    /**
     * 本次完整兑现采集时刻。
     */
    @Schema(description = "本次完整兑现采集时刻")
    private LocalDateTime observedTime;

    /**
     * 原案件基线指纹，不以当前数覆盖B。
     */
    @Schema(description = "原案件基线指纹，不以当前数覆盖B")
    private String baselineFingerprint;

    /**
     * 完整当前来源/分期/原交期证据稳定指纹。
     */
    @Schema(description = "完整当前来源/分期/原交期证据稳定指纹")
    private String evidenceFingerprint;

    /**
     * 完整不可变观察JSON，最多8MiB。
     */
    @Schema(description = "完整不可变观察JSON，最多8MiB")
    private String evidenceSnapshot;

    /**
     * 当前有效/全部兑现/基线漂移/来源漂移/补录/待核，只由服务端计算。
     */
    @Schema(description = "当前有效/全部兑现/基线漂移/来源漂移/补录/待核，只由服务端计算")
    private String result;

    /**
     * 来源当前C精确文本，缺口时允许未知。
     */
    @Schema(description = "来源当前C精确文本，缺口时允许未知")
    private String currentQuantity;

    /**
     * 仅全依据可信时的Q-C文本，未知不填零。
     */
    @Schema(description = "仅全依据可信时的Q-C文本，未知不填零")
    private String remainingQuantity;

    /**
     * 完整分期日桶数量。
     */
    @Schema(description = "完整分期日桶数量")
    private Integer bucketCount;

    /**
     * PENDING/CONFIRMED/REJECTED/STALE，一次性异人核验。
     */
    @Schema(description = "PENDING/CONFIRMED/REJECTED/STALE，一次性异人核验")
    private String status;

    /**
     * 真实采集依据。
     */
    @Schema(description = "真实采集依据")
    private String reason;

    /**
     * 独立核验永久请求号。
     */
    @Schema(description = "独立核验永久请求号")
    private String reviewRequestId;

    /**
     * 独立核验原载荷及实际实名指纹，防异参/异人重放。
     */
    @Schema(description = "独立核验原载荷及实际实名指纹，防异参/异人重放")
    private String reviewRequestFingerprint;

    /**
     * 实际独立核验账号。
     */
    @Schema(description = "实际独立核验账号")
    private String reviewedBy;

    /**
     * 一次性核验落账时刻。
     */
    @Schema(description = "一次性核验落账时刻")
    private LocalDateTime reviewedTime;

    /**
     * 独立核验依据或失效原因。
     */
    @Schema(description = "独立核验依据或失效原因")
    private String reviewRemark;

    /**
     * 固定核验检查项JSON。
     */
    @Schema(description = "固定核验检查项JSON")
    private String reviewChecksSnapshot;

    /**
     * 实际授权工厂编码。
     */
    @Schema(description = "实际授权工厂编码")
    private String factoryCode;
}
