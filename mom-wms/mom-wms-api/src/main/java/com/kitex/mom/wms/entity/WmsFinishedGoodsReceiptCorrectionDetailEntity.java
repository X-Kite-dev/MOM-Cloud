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
import java.time.LocalDate;

/**
 * 红冲申请时冻结的每条当前库存证据。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_finished_goods_receipt_correction_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "成品实收入库红冲库存证据")
public class WmsFinishedGoodsReceiptCorrectionDetailEntity extends BaseEntity<WmsFinishedGoodsReceiptCorrectionDetailEntity> {
    @Schema(description = "红冲申请ID")
    private Long correctionId;
    @Schema(description = "申请时冻结的实时库存ID")
    private Long stockId;
    @Schema(description = "库存首次形成时的入库单ID")
    private Long sourceInBillId;
    @Schema(description = "库存首次形成时的入库单明细ID")
    private Long sourceInBillDetailId;
    @Schema(description = "物料编码")
    private String matCode;
    @Schema(description = "物料名称")
    private String matName;
    @Schema(description = "规格型号")
    private String matSpec;
    @Schema(description = "物料批次")
    private String matBatch;
    @Schema(description = "物料条码或产品SN；有值时只允许整行红冲")
    private String matBarcode;
    @Schema(description = "库存计量单位")
    private String matUnit;
    @Schema(description = "申请时该库存行的完整可见数量")
    private BigDecimal sourceStockQuantity;
    @Schema(description = "本次从该库存行选择的红冲数量")
    private BigDecimal quantity;
    @Schema(description = "仓库编码")
    private String warehouseCode;
    @Schema(description = "仓库名称")
    private String warehouseName;
    @Schema(description = "库位编码")
    private String siteCode;
    @Schema(description = "库位名称")
    private String siteName;
    @Schema(description = "容器编码")
    private String containerCode;
    @Schema(description = "生产日期")
    private LocalDate produceDate;
    @Schema(description = "标准质量状态")
    private String qualityStatus;
    @Schema(description = "质量判定结果")
    private String qualityResult;
    @Schema(description = "质量隔离单号")
    private String qualityHoldNo;
    @Schema(description = "首次成品入库实际依赖的QMS任务ID JSON数组")
    private String sourceQualityTaskIds;
    @Schema(description = "首次成品入库QMS质量证据SHA-256指纹")
    private String sourceQualityEvidenceFingerprint;
    @Schema(description = "首次成品入库QMS质量证据快照")
    private String sourceQualityEvidenceSnapshot;
    @Schema(description = "本库存行完整事实的SHA-256指纹")
    private String lineFingerprint;
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
