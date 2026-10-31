package com.kitex.mom.wms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库通知单
 *
 * @author kitex
 * @date 2024-12-10 15:11:37
 */
@Data
@TenantTable
@TableName("wms_in_notice")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "入库通知单")
public class WmsInNoticeEntity extends BaseEntity<WmsInNoticeEntity> {

	@ExcelIgnore
	@TableField(exist = false)
	@Schema(description = "当前来源仓库的退料实收可用能力")
	private java.util.Map<String, Boolean> returnReceiptCapabilities;

	@ExcelIgnore
	@Schema(description = "来源系统永久幂等号")
	private String sourceRequestId;

	@ExcelIgnore
	@Schema(description = "来源请求载荷指纹")
	private String sourceRequestFingerprint;

	@ExcelIgnore
	@Schema(description = "成品实收红冲状态：NONE/PARTIALLY_REVERSED/REVERSED")
	private String finishedGoodsReversalStatus;

	@ExcelIgnore
	@Schema(description = "成品实收累计已红冲数量；不覆盖原始实收数量")
	private java.math.BigDecimal finishedGoodsReversedNum;

	@ExcelIgnore
	@Schema(description = "生产退料实收状态：WAITING/RECEIVING/RECEIVED/VARIANCE_PENDING/CLOSED_WITH_VARIANCE")
	private String returnReceiptStatus;

	@ExcelIgnore
	@Schema(description = "生产退料受控确认的短收数量")
	private java.math.BigDecimal returnShortageNum;

	@ExcelIgnore
	@Schema(description = "生产退料现场申报但未入账的溢收数量")
	private java.math.BigDecimal returnOverageNum;

	@ExcelIgnore
	@Schema(description = "生产退料收货差异原因")
	private String returnVarianceReason;

	@ExcelIgnore
	@Schema(description = "生产退料差异申报人编码")
	private String returnReporterCode;

	@ExcelIgnore
	@Schema(description = "生产退料差异申报人姓名")
	private String returnReporterName;

	@ExcelIgnore
	@Schema(description = "生产退料差异申报时间")
	private LocalDateTime returnReportedTime;

	@ExcelIgnore
	@Schema(description = "生产退料差异处置：ACCEPT_SHORTAGE/CONTINUE_RECEIPT/RETURN_OVERAGE")
	private String returnDisposition;

	@ExcelIgnore
	@Schema(description = "生产退料差异处置原因")
	private String returnDispositionReason;

	@ExcelIgnore
	@Schema(description = "生产退料差异处置人编码")
	private String returnReviewerCode;

	@ExcelIgnore
	@Schema(description = "生产退料差异处置人姓名")
	private String returnReviewerName;

	@ExcelIgnore
	@Schema(description = "生产退料差异处置时间")
	private LocalDateTime returnReviewedTime;

	@ExcelIgnore
	@Schema(description = "生产退料收货终结永久请求号")
	private String returnFinalizeRequestId;

	@ExcelIgnore
	@Schema(description = "生产退料差异处置永久请求号")
	private String returnDispositionRequestId;

	/**
	 * 入库通知单状态;0待处理、1处理中、2已完成、3已作废
	 */
	@ExcelProperty("状态")
	@Schema(description = "入库通知单状态;0待处理、1处理中、2已完成、3已作废")
	private String state;

	/**
	 * 入库完成时间
	 */
	@ExcelProperty("入库完成时间")
	@Schema(description = "入库完成时间")
	private LocalDateTime completeTime;

	/**
	 * 供应商编码
	 */
	@ExcelIgnore
	@Schema(description = "供应商编码")
	private String supplierCode;

	/**
	 * 仓库编码
	 */
	@ExcelProperty("仓库编码")
	@Schema(description = "仓库编码")
	private String warehouseCode;

	/**
	 * 仓库名称
	 */
	@ExcelProperty("仓库名称")
	@Schema(description = "仓库名称")
	private String warehouseName;

	/**
	 * 通知入库总数
	 */
	@ExcelProperty("通知入库总数")
	@Schema(description = "通知入库总数")
	private Double noticeInNum;

	/**
	 * 入库总数
	 */
	@ExcelProperty("入库总数")
	@Schema(description = "入库总数")
	private Double allInNum;

	/**
	 * 未入库总数
	 */
	@ExcelProperty("未入库总数")
	@Schema(description = "未入库总数")
	private Double allNotInNum;

	/**
	 * 车间名称
	 */
	@ExcelProperty("车间名称")
	@Schema(description = "车间")
	private String workshop;

	/**
	 * 通知单业务类型
	 */
	@ExcelIgnore
	@Schema(description = "通知单业务类型")
	private String billType;

	/**
	 * 业务单据id;采购订单、生产加工单、外协订单
	 */
	@ExcelIgnore
	@Schema(description = "业务单据id;采购订单、生产加工单、外协订单")
	private Long documentId;

	/**
	 * 业务单据表名
	 */
	@ExcelIgnore
	@Schema(description = "业务单据表名")
	private String documentTable;

	/**
	 * 备注
	 */
	@ExcelProperty("备注")
	@Schema(description = "备注")
	private String remark;

	/**
	 * 扩展字段
	 */
	@ExcelIgnore
	@Schema(description = "扩展字段")
	private String expansion;

	/**
	 * 工厂编码 - 数据所属的工厂编码
	 */
	@ExcelIgnore
	@Schema(description = "工厂编码 - 数据所属的工厂编码")
	@TableField(fill = FieldFill.INSERT)
	private String factoryCode;

	/**
	 * 入库单号（App入库时用到）
	 */
	@ExcelIgnore
	@TableField(exist = false)
	@Schema(description = "入库单号（App入库时用到）")
	private String billNo;

	@ExcelIgnore
	@TableField(exist = false)
	@EntityMapping(thisField = "id", joinField = "noticeId")
	private List<WmsInNoticeDetailEntity> detailList;

}
