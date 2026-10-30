package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * MPS承诺兑现的不可变执行观察与独立核验，不覆盖已开工生产事实。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mps_execution_observation")
@EqualsAndHashCode(callSuper = true)
public class PlnMpsExecutionObservationEntity extends BaseEntity<PlnMpsExecutionObservationEntity> {
    @Schema(description = "执行回评编号")
    private String observationNo;

    @Schema(description = "来源已发布MPS版本")
    private Long versionId;

    @Schema(description = "MPS版本编号")
    private String versionNo;

    @Schema(description = "采集永久请求号")
    private String requestId;

    @Schema(description = "采集参数指纹，同号异内容拒绝")
    private String requestFingerprint;

    @Schema(description = "日桶承诺截止容差分钟")
    private Integer toleranceMinutes;

    @Schema(description = "回评采集时刻")
    private java.time.LocalDateTime observedTime;

    @Schema(description = "MPS、根计划、MES来源与执行事实稳定指纹")
    private String evidenceFingerprint;

    @Schema(description = "完整不可变回评及原始证据快照")
    private String evidenceSnapshot;

    @Schema(description = "CLEAR/DEVIATION/DATA_GAP，仅服务端计算")
    private String result;

    @Schema(description = "当前版本全部时间桶数")
    private Integer bucketCount;

    @Schema(description = "超出承诺截止且未全量完工的桶数")
    private Integer lateBucketCount;

    @Schema(description = "存在结构或取证缺口的桶数")
    private Integer dataGapBucketCount;

    @Schema(description = "PENDING/CONFIRMED/REJECTED/STALE")
    private String status;

    @Schema(description = "采集说明")
    private String reason;

    @Schema(description = "独立核验永久请求号")
    private String reviewRequestId;

    @Schema(description = "独立核验账号")
    private String reviewedBy;

    @Schema(description = "独立核验时刻")
    private java.time.LocalDateTime reviewedTime;

    @Schema(description = "对应数量、来源和处置路径的复核依据")
    private String reviewRemark;

    @Schema(description = "固定复核检查项JSON")
    private String reviewChecks;

    @Schema(description = "当前实际工厂")
    private String factoryCode;

}
