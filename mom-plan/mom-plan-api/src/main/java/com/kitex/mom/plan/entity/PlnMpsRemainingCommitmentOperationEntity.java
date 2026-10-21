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

/** 剩余责任领域命令的追加式永久回执，不可改删。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_mps_remaining_commitment_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "剩余责任领域命令的追加式永久回执，不可改删")
public class PlnMpsRemainingCommitmentOperationEntity extends BaseEntity<PlnMpsRemainingCommitmentOperationEntity> {
    /** 合法剩余责任案件ID。 */
    @Schema(description = "合法剩余责任案件ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long commitmentId;

    /** 精确原MPS桶ID。 */
    @Schema(description = "精确原MPS桶ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mpsLineId;

    /** 本次领域命令永久号，与编制/复核/取消原号对应。 */
    @Schema(description = "本次领域命令永久号，与编制/复核/取消原号对应")
    private String requestId;

    /** 规范化原载荷及实际实名指纹。 */
    @Schema(description = "规范化原载荷及实际实名指纹")
    private String requestFingerprint;

    /** SUBMIT/CANCEL/REJECT/STALE/PUBLISH，不是任意状态编辑。 */
    @Schema(description = "SUBMIT/CANCEL/REJECT/STALE/PUBLISH，不是任意状态编辑")
    private String operationType;

    /** 原状态，首次编制为空。 */
    @Schema(description = "原状态，首次编制为空")
    private String beforeStatus;

    /** 本次合法落账状态。 */
    @Schema(description = "本次合法落账状态")
    private String afterStatus;

    /** 实际认证实名账号。 */
    @Schema(description = "实际认证实名账号")
    private String operatorCode;

    /** 规范化原命令及合法前后状态证据。 */
    @Schema(description = "规范化原命令及合法前后状态证据")
    private String commandSnapshot;

    /** 完整不可变回执，未知响应只能按原号核对。 */
    @Schema(description = "完整不可变回执，未知响应只能按原号核对")
    private String receiptSnapshot;

    /** 实际领域命令落账时刻。 */
    @Schema(description = "实际领域命令落账时刻")
    private LocalDateTime operatedTime;

    /** 实际授权工厂编码。 */
    @Schema(description = "实际授权工厂编码")
    private String factoryCode;
}
