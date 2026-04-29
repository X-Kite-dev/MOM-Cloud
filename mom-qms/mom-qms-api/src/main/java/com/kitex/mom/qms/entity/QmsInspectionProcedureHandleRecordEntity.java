package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 工序质检处置明细
 *
 * @author kitex
 * @since 2025-03-20
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "工序质检处置明细")
@TableName("qms_inspection_procedure_handle_record")
public class QmsInspectionProcedureHandleRecordEntity extends BaseEntity<QmsInspectionProcedureHandleRecordEntity> {

    @Schema(description = "来源任务id")
    private Long taskId;

    @Schema(description = "数量")
    private Integer handleNum;

    @Schema(description = "处置结果")
    private String handleResult;

    @Schema(description = "处置人id")
    private Long handlePersonId;

    @Schema(description = "处置人姓名")
    private String handlePersonName;

    @Schema(description = "处置时间")
    private Date handleTime;

    @Schema(description = "处置备注")
    private String handleRemark;

    @Schema(description = "处置状态")
    private Boolean status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
