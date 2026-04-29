package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.qms.enums.InspectionTaskStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 成品质检任务
 *
 * @author kitex
 * @date 2022/12/1
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "成品质检任务")
@TableName(value = "qms_inspection_product_task", autoResultMap = true)
public class QmsInspectionProductTaskEntity extends BaseEntity<QmsInspectionProductTaskEntity> {

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "关联物料编码")
    private String matCode;

    @Schema(description = "关联物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "关联质量标准")
    private Long standardId;

    @Schema(description = "关联质量标准名称")
    private String standardName;

    @Schema(description = "工序编码")
    private String processCode;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "批次")
    private String batchNo;

    @Schema(description = "数量")
    private Integer num = 0;

    @Schema(description = "取样数")
    private Integer inspectionNum = 0;

    /**
     * @see InspectionTaskStatusEnum
     */
    @Schema(description = "处理状态")
    private String status = InspectionTaskStatusEnum.WAIT.name();

    @Schema(description = "处理结果")
    private String result;

    @Schema(description = "可用数量")
    private Integer usableNum;

    @Schema(description = "处理数量")
    private Integer handleNum;

    @Schema(description = "处理结果")
    private String handleResult;

    /**
     * 记录是否是免检的 1是；0否
     * 因为免检不应该有报告，代码流转中创建入库通知后，状态都变成了已处理，免检的报告按钮也显示出来了，会报错
     */
    @Schema(description = "免检状态")
    private String lestStatus;

    @Schema(description = "最后的质检时间")
    private Date latestInspectionTime;

    @Schema(description = "最后的更新任务时间")
    private Date latestAppendTime;

    @Schema(description = "备注")
    private String remark;

    /**
     * 通知入库数量
     */
    @Schema(description = "通知入库数量")
    private BigDecimal noticeInBillNum;

    /**
     * 车间编号
     */
    @Schema(description = "车间编号", name = "deptCode")
    private String deptCode;

    @Schema(description = "工单编码")
    private String orderNo;

    @Schema(description = "工单id")
    private String orderId;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    @Schema(description = "附件")
    private String annex;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
