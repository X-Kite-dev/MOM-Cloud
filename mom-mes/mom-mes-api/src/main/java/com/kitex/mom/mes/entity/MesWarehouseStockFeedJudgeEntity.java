package com.kitex.mom.mes.entity;

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
 * <p>
 * 线边库生产退料审批 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-04-08
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "线边库生产退料审批")
@TableName("mes_warehouse_stock_feed_judge")
public class MesWarehouseStockFeedJudgeEntity extends BaseEntity<MesWarehouseStockFeedJudgeEntity> {

    @Schema(description = "工作流版本；V2为受控退库，LEGACY为升级前历史单")
    private String workflowVersion;

    @Schema(description = "提交永久幂等请求号")
    private String requestId;

    @Schema(description = "提交命令指纹")
    private String requestFingerprint;

    @Schema(description = "申请时来源库存事实指纹")
    private String evidenceFingerprint;

    @Schema(description = "申请时来源库存事实快照JSON")
    private String evidenceSnapshot;

    /**
     * 申请编码
     */
    @Schema(description = "申请编码")
    private String code;

    /**
     * 单据号
     */
    @Schema(description = "线边库存来源领料/签收单号")
    private String billNo;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "是否为替代料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例")
    private BigDecimal substituteRatio;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 仓库编码
     */
    private String warehouseCode;

    /**
     * 仓库名称
     */
    private String warehouseName;

    /**
     * 库位编码
     */
    private String siteCode;

    /**
     * 库位名称
     */
    private String siteName;

    /**
     * 供应商编码
     */
    private String supplierCode;

    /**
     * 供应商名称
     */
    private String supplierName;

    /**
     * 退料数量
     */
    @Schema(description = "退料数量")
    private BigDecimal num;

    /**
     * 批次
     */
    @Schema(description = "批次")
    private String matBatch;

    /**
     * 退料原因
     */
    @Schema(description = "退料原因")
    private String reason;

    /**
     * 审批状态
     */
    @Schema(description = "新流程为SUBMITTED/APPROVED/REJECTED/WITHDRAWN/STALE；历史流程保留0/1/2")
    private String status;

    @Schema(description = "申请人主键")
    private Long applicantId;

    @Schema(description = "申请人工号")
    private String applicantCode;

    @Schema(description = "申请人姓名")
    private String applicantName;

    @Schema(description = "申请时间")
    private LocalDateTime submittedTime;

    @Schema(description = "审批永久幂等请求号")
    private String reviewRequestId;

    @Schema(description = "审批命令指纹")
    private String reviewFingerprint;

    @Schema(description = "审批结论")
    private Boolean approved;

    @Schema(description = "审批人主键")
    private Long reviewerId;

    /**
     * 审批人
     */
    @Schema(description = "审批人")
    private String judgePersonCode;

    /**
     * 审批人姓名
     */
    @Schema(description = "审批人姓名")
    private String judgePersonName;

    /**
     * 审批时间
     */
    @Schema(description = "审批时间")
    private LocalDateTime judgeTime;

    /**
     * 审批意见
     */
    @Schema(description = "审批意见")
    private String opinion;

    @Schema(description = "WMS生产退料通知永久幂等号")
    private String wmsRequestId;

    @Schema(description = "WMS生产退料入库通知主键")
    private Long wmsNoticeId;

    @Schema(description = "WMS实际收货状态：WAITING/RECEIVING/RECEIVED/VARIANCE_PENDING/CLOSED_WITH_VARIANCE")
    private String wmsReceiptStatus;

    @Schema(description = "WMS累计实际收货数量")
    private BigDecimal wmsActualReceivedNum;

    @Schema(description = "WMS受控确认的短收数量")
    private BigDecimal wmsShortageNum;

    @Schema(description = "WMS申报但未入账的溢收数量")
    private BigDecimal wmsOverageNum;

    @Schema(description = "WMS最后实收时间")
    private LocalDateTime wmsReceiptTime;

    @Schema(description = "退料后齐套反馈状态：WAITING/REPLENISHED/NO_RECEIPT")
    private String readinessFeedbackStatus;

    @Schema(description = "最后接收的WMS实收事件号")
    private String lastReceiptEventId;

    @Schema(description = "最后接收的WMS实收事件指纹")
    private String lastReceiptFingerprint;

    @Schema(description = "WMS通知状态：ACCEPTED/NOT_REQUIRED")
    private String wmsStatus;

    @Schema(description = "流程处理完成时间")
    private LocalDateTime handledTime;

    @Schema(description = "审批执行结果说明")
    private String resultMessage;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
