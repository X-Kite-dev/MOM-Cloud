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
 * 出库通知单明细
 *
 * @author kitex
 * @date 2024-12-23 20:07:39
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_out_notice_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "wms_out_notice_detail")
public class WmsOutNoticeDetailEntity extends BaseEntity<WmsOutNoticeDetailEntity> {

    /**
     * 受控出库锁定的实时库存行；为空时沿用历史批次匹配逻辑。
     */
    @Schema(description = "受控出库锁定的实时库存行ID")
    private Long sourceStockId;

    /**
     * 通知单id
     */
    @Schema(description = "通知单id")
    private Long noticeId;
    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "BOM需求数量当量")
    private BigDecimal requirementMatNum;

    @Schema(description = "是否为替代料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例")
    private BigDecimal substituteRatio;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 计量单位
     */
    @Schema(description = "计量单位")
    private String matUnit;
    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;
    /**
     * 物料分组
     */
    @Schema(description = "物料分组")
    private String matGroup;
    /**
     * 物料分组名称
     */
    @Schema(description = "物料分组名称")
    private String matGroupName;
    /**
     * 通知出库量
     */
    @Schema(description = "通知出库量")
    private Double noticeOutNum;
    /**
     * 通知已出库量
     */
    @Schema(description = "通知已出库量")
    private Double outNum;
    /**
     * 通知未出库量
     */
    @Schema(description = "通知未出库量")
    private Double notOutNum;
    /**
     * 出库仓编码
     */
    @Schema(description = "出库仓编码")
    private String warehouseCode;
    /**
     * 出库仓名称
     */
    @Schema(description = "出库仓名称")
    private String warehouseName;
    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;
    /**
     * 有效期
     */
    @Schema(description = "有效期")
    private Integer matValidPeriod;
    /**
     * 有效截至日期
     */
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;
    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshop;
    /**
     * 业务单据id
     */
    @Schema(description = "业务单据id")
    private Long documentId;
    /**
     * 业务单据表名
     */
    @Schema(description = "业务单据表名")
    private String documentTable;
    /**
     * 扩展字段
     */
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 状态
     */
    @TableField(exist = false)
    @Schema(description = "状态")
    private String state;

    /**
     * 单据类型
     */
    @TableField(exist = false)
    @Schema(description = "单据类型")
    private String billType;

    /**
     * 以下字段仅用于PDA展示冻结库存，不落出库通知明细表。
     */
    @TableField(exist = false)
    @Schema(description = "冻结库存批次（PDA）")
    private String matBatch;
    @TableField(exist = false)
    @Schema(description = "冻结库存供应商编码（PDA）")
    private String supplierCode;
    @TableField(exist = false)
    @Schema(description = "冻结库存供应商名称（PDA）")
    private String supplierName;
    @TableField(exist = false)
    @Schema(description = "冻结库存库位编码（PDA）")
    private String siteCode;
    @TableField(exist = false)
    @Schema(description = "冻结库存库位名称（PDA）")
    private String siteName;
    @TableField(exist = false)
    @Schema(description = "冻结库存容器编码（PDA）")
    private String containerCode;
}
