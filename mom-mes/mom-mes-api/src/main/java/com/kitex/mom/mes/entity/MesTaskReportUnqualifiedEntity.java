package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 生产报工-不合格品上报 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-05
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产报工-不合格品上报")
@TableName("mes_task_report_unqualified")
public class MesTaskReportUnqualifiedEntity extends BaseEntity<MesTaskReportUnqualifiedEntity> {

    /**
     * 派工单号
     */
    @Schema(description = "派工单号")
    private String arrangeCode;

    /**
     * 任务单号
     */
    @Schema(description = "任务单号")
    private String produceTaskCode;

    /**
     * 生产工单号
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 员工工号
     */
    @Schema(description = "员工工号")
    private String workerCode;

    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名")
    private String workerName;

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;


    /**
     * 不合格数量
     */
    @Schema(description = "不合格数量")
    private BigDecimal unqualifiedNum;

    /**
     * 报工时间
     */
    @Schema(description = "报工时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportTime;

    /**
     * 条码/流转码
     */
    @Schema(description = "条码/流转码")
    private String transferCode;

    /**
     * 不合格类型
     */
    @Schema(description = "不合格类型")
    private String type;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

