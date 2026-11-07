package com.kitex.mom.wms.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;

import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionDetailEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 成品实收入库红冲的可执行性预览和冻结申请详情。
 */
@Data
@Accessors(chain = true)
@Schema(description = "成品实收入库红冲资格、申请和冻结证据视图")
public class WmsFinishedGoodsReceiptCorrectionView {
	@Schema(description = "WMS成品入库通知ID")
	private Long noticeId;
	@Schema(description = "MES原成品入库永久请求号")
	private String sourceMesInboundRequestId;
	@Schema(description = "MES原成品入库命令指纹")
	private String sourceRequestFingerprint;
	@Schema(description = "通知数量")
	@JsonSerialize(using = DecimalTextSerializer.class)
	private BigDecimal noticeQuantity;
	@Schema(description = "WMS累计实收数量")
	@JsonSerialize(using = DecimalTextSerializer.class)
	private BigDecimal receivedQuantity;
	@Schema(description = "当前仍在库的精确来源数量")
	@JsonSerialize(using = DecimalTextSerializer.class)
	private BigDecimal currentStockQuantity;
	@Schema(description = "当前精确来源库存行数")
	private Integer currentStockLineCount;
	@Schema(description = "扣除活动预留和不可用状态后可选择红冲的数量")
	@JsonSerialize(using = DecimalTextSerializer.class)
	private BigDecimal selectableStockQuantity;
	@Schema(description = "当前精确来源库存的逐行选择信息")
	private List<StockOption> stockOptions = new ArrayList<>();
	@Schema(description = "首次成品入库实际依赖的QMS任务ID集合")
	private List<Long> sourceQualityTaskIds = new ArrayList<>();
	@Schema(description = "首次成品入库聚合QMS质量证据指纹")
	private String sourceQualityEvidenceFingerprint;
	@Schema(description = "是否必须先完成QMS质量判定逆向审批")
	private Boolean qualityAuthorizationRequired;
	@Schema(description = "当前匹配的QMS质量逆向授权ID")
	private Long qualityAuthorizationId;
	@Schema(description = "当前匹配的QMS质量逆向授权单号")
	private String qualityAuthorizationNo;
	@Schema(description = "QMS质量逆向状态")
	private String qualityAuthorizationStatus;
	@Schema(description = "可提交WMS时返回的QMS授权码")
	private String qualityAuthorizationCode;
	@Schema(description = "QMS质量逆向状态说明")
	private String qualityAuthorizationMessage;
	@Schema(description = "QMS申请时冻结的任务证据指纹；复核必须原值回传")
	private String qualityAuthorizationEvidenceFingerprint;
	@Schema(description = "QMS质量逆向授权到期时间")
	private java.time.LocalDateTime qualityAuthorizationExpiresAt;
	@Schema(description = "当前是否满足提交资格")
	private Boolean eligible;
	@Schema(description = "当前通知、入库单、库存、流水和预留证据指纹")
	private String evidenceFingerprint;
	@Schema(description = "不可申请或不可批准的明确原因")
	private List<String> blockers = new ArrayList<>();
	@Schema(description = "该通知最近一张红冲申请")
	private WmsFinishedGoodsReceiptCorrectionEntity correction;
	/**
	 * 当前红冲申请的固定批准原号是否已永久结束；为空或false不保证符合实时批准条件。
	 */
	@Schema(description = "固定批准原号已永久结束，须正常撤回或驳回后重新申请")
	private Boolean approvalRequestEnded;
	@Schema(description = "申请时冻结的库存证据；尚未申请时为空")
	private List<WmsFinishedGoodsReceiptCorrectionDetailEntity> details = new ArrayList<>();

	/**
	 * 可选量可来自预留扣减，必须在JSON解析前保留十进制原值，且不输出科学记数法。
	 */
	public static final class DecimalTextSerializer extends JsonSerializer<BigDecimal> {
		@Override
		public void serialize(BigDecimal value, JsonGenerator output,
		                      SerializerProvider provider) throws IOException {
			output.writeString(value.toPlainString());
		}
	}

	@Data
	@Accessors(chain = true)
	@Schema(description = "成品红冲可选择库存行")
	public static class StockOption {
		private Long stockId;
		private String matCode;
		private String matName;
		private String matBatch;
		private String matBarcode;
		private String matUnit;
		private String warehouseCode;
		private String warehouseName;
		private String siteCode;
		private String siteName;
		private String containerCode;
		@JsonSerialize(using = DecimalTextSerializer.class)
		private BigDecimal quantity;
		@JsonSerialize(using = DecimalTextSerializer.class)
		private BigDecimal productionReservedQuantity;
		@JsonSerialize(using = DecimalTextSerializer.class)
		private BigDecimal salesReservedQuantity;
		@JsonSerialize(using = DecimalTextSerializer.class)
		private BigDecimal selectableQuantity;
		@Schema(description = "是否为必须整行选择的条码/SN库存")
		private Boolean wholeLineRequired;
		private Boolean selectable;
		private String blocker;
	}
}
