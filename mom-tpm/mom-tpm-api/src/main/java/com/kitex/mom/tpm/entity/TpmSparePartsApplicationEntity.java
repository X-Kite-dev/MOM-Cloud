package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 备件出入库申请主表
 *
 * @author kitex
 * @date 2025-03-18 10:32:15
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_application")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "备件出入库申请主表")
public class TpmSparePartsApplicationEntity extends BaseEntity<TpmSparePartsApplicationEntity> {


    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 申请号
     */
    @Schema(description = "申请号")
    private String applyCode;

    /**
     * 类型 出/入库
     */
    @Schema(description = "类型 出/入库")
    private String type;

    /**
     * 来源编码
     */
    @Schema(description = "来源编码")
    private String sourceCode;

    /**
     * 来源 计划更换、故障更换
     */
    @Schema(description = "来源 计划更换、故障更换")
    private String source;

    /**
     * 任务编码
     */
    @Schema(description = "任务编码")
    private String taskCode;

    /**
     * 申请人编码
     */
    @Schema(description = "申请人编码")
    private String requesterCode;

    /**
     * 申请人
     */
    @Schema(description = "申请人")
    private String requesterName;

    /**
     * 申请日期
     */
    @Schema(description = "申请日期")
    private LocalDate requestDate;

    /**
     * 审批状态（0待审/1通过/2驳回）
     */
    @Schema(description = "审批状态（0待审/1通过/2驳回）")
    private String approvalStatus;

    @Schema(description = "审批人")
    private String approvalPerson;

    @Schema(description = "审批人名称")
    private String approvalPersonName;

    @Schema(description = "审批时间")
    private LocalDateTime approvalTime;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "明细表")
    private List<TpmSparePartsApplicationDetailsEntity> detailsList;


    @TableField(exist = false)
    @Schema(description = "出库数据")
    private List<TpmSparePartsInOrOutBillEntity> outBillList;

}