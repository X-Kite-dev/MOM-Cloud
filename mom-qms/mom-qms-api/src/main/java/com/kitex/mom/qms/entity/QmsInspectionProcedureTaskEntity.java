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

import java.util.Date;

/**
 * 生产质检任务
 *
 * @author kitex
 * @date 2022/12/1
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产质检任务")
@TableName("qms_inspection_procedure_task")
public class QmsInspectionProcedureTaskEntity extends BaseEntity<QmsInspectionProcedureTaskEntity> {

    @Schema(description = "MES工序任务编码")
    private String sourceTaskCode;

    @Schema(description = "来源系统永久幂等请求号")
    private String sourceRequestId;

    @Schema(description = "来源系统固定创建参数SHA-256；用于精确幂等核账")
    private String sourceRequestFingerprint;

    @Deprecated
    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "关联物料编码")
    private String matCode;

    @Schema(description = "关联物料名称")
    private String matName;

    @Schema(description = "规格型号")
    private String matSpec;

    @Schema(description = "工序编码")
    private String processCode;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "关联质量标准")
    private Long standardId;

    @Schema(description = "关联质量标准名称")
    private String standardName;

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
    private String status;

    @Schema(description = "处理结果")
    private String result;

    @Schema(description = "标签类型")
    private String labelType;

    @Schema(description = "可用数量")
    private Integer usableNum;

    @Schema(description = "处理数量")
    private Integer handleNum;

    @Schema(description = "处理结果")
    private String handleResult;

    @Schema(description = "最后的质检(合格判定)时间")
    private Date latestInspectionTime;

    @Schema(description = "最后的更新任务(追加报检)时间")
    private Date latestAppendTime;

    @Deprecated
    @Schema(description = "是否完成当前所有的质检项")
    private Boolean currentFinish;

    @Schema(description = "备注")
    private String remark;

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
