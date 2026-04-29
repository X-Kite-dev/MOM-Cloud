package com.kitex.mom.wms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
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
 * 入库单
 *
 * @author kitex
 * @date 2024-12-16 13:47:16
 */
@Data
@TenantTable
@TableName("wms_in_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "入库单")
public class WmsInBillEntity extends BaseEntity<WmsInBillEntity> {

    /**
     * 入库单号
     */
    @Schema(description = "入库单号")
    private String billNo;

    /**
     * 入库通知单id
     */
    @Schema(description = "入库通知单id")
    private Long noticeId;

    /**
     * 入库单状态;0待提交、1待审核、2已通过
     */
    @Schema(description = "入库单状态;0待提交、1待审核、2已通过")
    private String state;

    /**
     * 入库时间
     */
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 入库仓编码
     */
    @Schema(description = "入库仓编码")
    private String warehouseCode;

    /**
     * 入库仓名称
     */
    @Schema(description = "入库仓名称")
    private String warehouseName;

    /**
     * 入库完成明细条数
     */
    @Schema(description = "入库完成明细条数")
    private Double detailFinishNum;

    /**
     * 入库未完成明细条数
     */
    @Schema(description = "入库未完成明细条数")
    private Double detailNotFinishNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 审核人
     */
    @Schema(description = "审核人")
    private String reviewer;

    /**
     * 审核时间
     */
    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    /**
     * 驳回原因
     */
    @Schema(description = "驳回原因")
    private String rejectReason;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshop;

    /**
     * 入库单类型
     */
    @Schema(description = "入库单类型")
    private String billType;

    /**
     * 业务单据id;采购订单、生产加工单、外协订单
     */
    @Schema(description = "业务单据id;采购订单、生产加工单、外协订单")
    private Long documentId;

    /**
     * 业务单据表名
     */
    @Schema(description = "业务单据表名")
    private String documentTable;

    /**
     * 来源：app、pc
     */
    @Schema(description = "来源")
    private String source;

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

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "billId")
    private List<WmsInBillDetailEntity> details;

}