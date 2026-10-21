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

import java.math.BigDecimal;
import java.time.LocalDate;
/** 剩余责任的不可变内部日桶，不代表物理拆批或新增工单。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mps_remaining_commitment_line")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "剩余责任的不可变内部日桶，不代表物理拆批或新增工单")
public class PlnMpsRemainingCommitmentLineEntity extends BaseEntity<PlnMpsRemainingCommitmentLineEntity> {
    /** 所属剩余责任案件ID。 */
    @Schema(description = "所属剩余责任案件ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long commitmentId;

    /** 精确原MPS桶ID。 */
    @Schema(description = "精确原MPS桶ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mpsLineId;

    /** 从1连续的内部日桶序号，最多100。 */
    @Schema(description = "从1连续的内部日桶序号，最多100")
    private Integer bucketNo;

    /** 新内部生产完成承诺日，不更改客户交期。 */
    @Schema(description = "新内部生产完成承诺日，不更改客户交期")
    private LocalDate promisedDate;

    /** 本桶正责任数量，完整合计严格为原R。 */
    @Schema(description = "本桶正责任数量，完整合计严格为原R")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;

    /** 原权威产品单位。 */
    @Schema(description = "原权威产品单位")
    private String productUnit;

    /** 原可信粗能力资源。 */
    @Schema(description = "原可信粗能力资源")
    private String capacityResourceCode;

    /** 冻结本桶保守粗能力预计分钟，非工序实绩。 */
    @Schema(description = "冻结本桶保守粗能力预计分钟，非工序实绩")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal projectedMinutes;

    /** 本桶日历/速率/换型能力依据指纹。 */
    @Schema(description = "本桶日历/速率/换型能力依据指纹")
    private String capacityFingerprint;

    /** 完整本桶能力证据JSON。 */
    @Schema(description = "完整本桶能力证据JSON")
    private String capacitySnapshot;

    /** 实际授权工厂编码。 */
    @Schema(description = "实际授权工厂编码")
    private String factoryCode;
}
