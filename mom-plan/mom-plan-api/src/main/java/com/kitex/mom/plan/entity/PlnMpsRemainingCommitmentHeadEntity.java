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

/** 精确原MPS桶的唯一当前生效版本指针，历史案件不覆盖。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mps_remaining_commitment_head")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "精确原MPS桶的唯一当前生效版本指针，历史案件不覆盖")
public class PlnMpsRemainingCommitmentHeadEntity extends BaseEntity<PlnMpsRemainingCommitmentHeadEntity> {
    /** 唯一精确原MPS桶ID。 */
    @Schema(description = "唯一精确原MPS桶ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mpsLineId;

    /** 合法已发布的当前案件ID。 */
    @Schema(description = "合法已发布的当前案件ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long currentCommitmentId;

    /** 同原桶连续发布计数，原子切换时递增。 */
    @Schema(description = "同原桶连续发布计数，原子切换时递增")
    private Integer publicationVersion;

    /** 本次合法独立发布的原永久请求号。 */
    @Schema(description = "本次合法独立发布的原永久请求号")
    private String publicationRequestId;

    /** 实际授权工厂编码。 */
    @Schema(description = "实际授权工厂编码")
    private String factoryCode;
}
