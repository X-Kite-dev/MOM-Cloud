package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 已完成换型工序归属案件；原证据不覆盖，错误归属经独立撤销案件解除。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_changeover_attribution")
@Schema(description = "已完成换型工序归属案件；原证据不覆盖，错误归属经独立撤销案件解除")
public class MesChangeoverAttributionEntity extends BaseEntity<MesChangeoverAttributionEntity> {
    /**
     * ASSIGN归属或REVOKE撤销申请。
     */
    @Schema(description = "ASSIGN归属或REVOKE撤销申请")
    private String caseType;

    /**
     * PENDING/APPROVED/REJECTED/CANCELLED/STALE/VOIDED。
     */
    @Schema(description = "PENDING/APPROVED/REJECTED/CANCELLED/STALE/VOIDED")
    private String status;

    /**
     * 真实已完成换型账ID。
     */
    @Schema(description = "真实已完成换型账ID")
    private Long executionId;

    /**
     * 精确目标派工ID。
     */
    @Schema(description = "精确目标派工ID")
    private Long arrangeId;

    /**
     * 目标派工编码。
     */
    @Schema(description = "目标派工编码")
    private String arrangeCode;

    /**
     * 精确工序任务编码。
     */
    @Schema(description = "精确工序任务编码")
    private String produceTaskCode;

    /**
     * 物理工单编码。
     */
    @Schema(description = "物理工单编码")
    private String taskCode;

    /**
     * 原工序编码。
     */
    @Schema(description = "原工序编码")
    private String processCode;

    /**
     * 目标产品编码。
     */
    @Schema(description = "目标产品编码")
    private String productCode;

    /**
     * 核定工位。
     */
    @Schema(description = "核定工位")
    private String stationCode;

    /**
     * 原设备编码。
     */
    @Schema(description = "原设备编码")
    private String deviceCode;

    /**
     * 派工原量精确十进制文本。
     */
    @Schema(description = "派工原量精确十进制文本")
    private String originalQuantity;

    /**
     * 撤销案件引用的原批准归属ID。
     */
    @Schema(description = "撤销案件引用的原批准归属ID")
    private Long originalCaseId;

    /**
     * 源完整事实与目标稳定身份SHA-256。
     */
    @Schema(description = "源完整事实与目标稳定身份SHA-256")
    private String basisFingerprint;

    /**
     * 完整原换型及设备记录JSON，含原亚秒时刻。
     */
    @Schema(description = "完整原换型及设备记录JSON，含原亚秒时刻")
    private String sourceSnapshot;

    /**
     * 申请时完整目标原状态JSON。
     */
    @Schema(description = "申请时完整目标原状态JSON")
    private String targetSnapshot;

    /**
     * 实际现场凭证引用。
     */
    @Schema(description = "实际现场凭证引用")
    private String evidenceReference;

    /**
     * 申请或撤销原因。
     */
    @Schema(description = "申请或撤销原因")
    private String reason;

    /**
     * 申请人实名ID，不以可变用户名区分异人。
     */
    @Schema(description = "申请人实名ID，不以可变用户名区分异人")
    private Long applicantUserId;

    /**
     * 申请人用户名。
     */
    @Schema(description = "申请人用户名")
    private String applicantCode;

    /**
     * 申请人姓名。
     */
    @Schema(description = "申请人姓名")
    private String applicantName;

    /**
     * 永久申请号。
     */
    @Schema(description = "永久申请号")
    private String requestId;

    /**
     * 完整申请命令SHA-256。
     */
    @Schema(description = "完整申请命令SHA-256")
    private String requestFingerprint;

    /**
     * 永久审核命令号。
     */
    @Schema(description = "永久审核命令号")
    private String reviewRequestId;

    /**
     * 完整审核命令SHA-256。
     */
    @Schema(description = "完整审核命令SHA-256")
    private String reviewFingerprint;

    /**
     * 审核人实名ID。
     */
    @Schema(description = "审核人实名ID")
    private Long reviewedUserId;

    /**
     * 审核人用户名。
     */
    @Schema(description = "审核人用户名")
    private String reviewedByCode;

    /**
     * 审核人姓名。
     */
    @Schema(description = "审核人姓名")
    private String reviewedByName;

    /**
     * 审核原时刻。
     */
    @Schema(description = "审核原时刻")
    private LocalDateTime reviewedTime;

    /**
     * 审核意见。
     */
    @Schema(description = "审核意见")
    private String reviewRemark;

    /**
     * 完整原审核检查项JSON。
     */
    @Schema(description = "完整原审核检查项JSON")
    private String reviewChecksSnapshot;

    /**
     * 使本批准归属失效的精确撤销案件ID。
     */
    @Schema(description = "使本批准归属失效的精确撤销案件ID")
    private Long voidedByCaseId;

    /**
     * 有权撤销的原时刻。
     */
    @Schema(description = "有权撤销的原时刻")
    private LocalDateTime voidedTime;

    /**
     * 实际工厂编码。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}

