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
     * 通知单id
     */
    @Schema(description = "通知单id")
    private Long noticeId;
    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
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
}
