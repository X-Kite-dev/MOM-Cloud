package com.kitex.mom.wms.entity;

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
 * 出库单
 *
 * @author kitex
 * @date 2024-12-24 11:36:28
 */
@Data
@TenantTable
@TableName("wms_out_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "出库单")
public class WmsOutBillEntity extends BaseEntity<WmsOutBillEntity> {

    /**
     * 出库单号
     */
    @Schema(description = "出库单号")
    private String billNo;

    /**
     * 出库通知单id
     */
    @Schema(description = "出库通知单id")
    private Long noticeId;

    /**
     * 出库单状态
     */
    @Schema(description = "出库单状态")
    private String state;

    /**
     * 出库时间
     */
    @Schema(description = "出库时间")
    private LocalDateTime outTime;

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
     * 出库完成明细条数
     */
    @Schema(description = "出库完成明细条数")
    private Double detailFinishNum;

    /**
     * 出库未完成明细条数
     */
    @Schema(description = "出库未完成明细条数")
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
     * 出库单类型
     */
    @Schema(description = "出库单类型")
    private String billType;

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
     * 来源：app、pc
     */
    @Schema(description = "来源")
    private String source;

    /**
     * 销售发运记账纠错状态：NULL/NONE、PENDING、REJECTED、EXECUTED。
     */
    @Schema(description = "销售发运记账纠错状态")
    private String correctionState;

    /**
     * 新生产发料是否必须经过线边配送交接后才允许MES签收。
     */
    @Schema(description = "是否强制线边配送交接")
    private Boolean lineDeliveryRequired;

    /**
     * 唯一线边配送任务。
     */
    @Schema(description = "线边配送任务ID")
    private Long lineDeliveryTaskId;

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

    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "billId")
    private List<WmsOutBillDetailEntity> details;

    /**
     * 通知单明细-app使用
     */
    @TableField(exist = false)
    private List<WmsOutNoticeDetailEntity> noticeDetails;
}
