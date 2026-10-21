package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MES成品实收入库受控红冲申请。
 *
 * <p>一张申请冻结一组精确库存行及本次红冲数量。原实收通知、入库单和正向流水作为
 * 历史事实保留，批准后以库存净额、反向流水和跨服务永久回执表达冲销结果。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_finished_goods_receipt_correction")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES成品实收入库受控红冲申请")
public class WmsFinishedGoodsReceiptCorrectionEntity extends BaseEntity<WmsFinishedGoodsReceiptCorrectionEntity> {
    @Schema(description = "红冲申请单号")
    private String correctionNo;
    @Schema(description = "申请命令永久幂等号")
    private String requestId;
    @Schema(description = "申请命令规范载荷SHA-256指纹")
    private String requestFingerprint;
    @Schema(description = "WMS成品入库通知ID")
    private Long noticeId;
    @Schema(description = "MES成品入库永久请求号")
    private String sourceMesInboundRequestId;
    @Schema(description = "MES原入库命令指纹")
    private String sourceRequestFingerprint;
    @Schema(description = "申请时冻结的库存、入库单和通知事实指纹")
    private String evidenceFingerprint;
    @Schema(description = "已消费的QMS质量判定逆向授权ID；无需质检时为空")
    private Long qmsDecisionReversalId;
    @Schema(description = "已消费的QMS质量判定逆向授权码；与授权ID和永久请求共同校验")
    private String qmsDecisionAuthorizationCode;
    @Schema(description = "QMS批准时的任务证据指纹")
    private String qmsDecisionEvidenceFingerprint;
    @Schema(description = "SUBMITTED/REJECTED/WITHDRAWN/STALE/EXECUTED")
    private String status;
    @Schema(description = "红冲原因")
    private String reason;
    @Schema(description = "本次红冲数量")
    private BigDecimal totalQuantity;
    @Schema(description = "FULL_RECEIPT/PARTIAL_BATCH/SN_EXACT/MIXED_EXACT")
    private String reversalMode;
    @Schema(description = "申请时该MES入库来源仍在库的数量")
    private BigDecimal sourceStockQuantityBefore;
    @Schema(description = "批准执行后该MES入库来源仍在库的数量")
    private BigDecimal sourceStockQuantityAfter;
    @Schema(description = "申请时冻结的库存行数")
    private Integer stockLineCount;
    @Schema(description = "申请人账号")
    private String applicantCode;
    @Schema(description = "申请人名称")
    private String applicantName;
    @Schema(description = "申请时间")
    private LocalDateTime appliedTime;
    @Schema(description = "复核人账号；必须与申请人不同")
    private String reviewerCode;
    @Schema(description = "复核人名称")
    private String reviewerName;
    @Schema(description = "复核时间")
    private LocalDateTime reviewedTime;
    @Schema(description = "驳回、撤回或证据失效原因")
    private String rejectReason;
    @Schema(description = "WMS实物红冲执行时间")
    private LocalDateTime executedTime;
    @Schema(description = "WAITING_APPROVAL/PENDING/SENDING/DELIVERED/DEAD；由可靠投递箱同步")
    private String mesReceiptStatus;
    @Schema(description = "MES永久回执JSON")
    private String mesReceiptJson;
    @Schema(description = "NOT_REQUIRED/WAITING_EXECUTION/PENDING/SENDING/DELIVERED/DEAD/NEEDS_RECONCILE；QMS执行结果可靠回送状态")
    private String qmsExecutionReceiptStatus;
    @Schema(description = "QMS确认WMS已执行质量逆向的永久回执JSON")
    private String qmsExecutionReceiptJson;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
