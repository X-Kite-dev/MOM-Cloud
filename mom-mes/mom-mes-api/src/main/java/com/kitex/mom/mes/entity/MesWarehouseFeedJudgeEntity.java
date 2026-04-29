package com.kitex.mom.mes.entity;

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
import java.time.LocalDateTime;

/**
 * <p>
 * 线边库余料退料审批 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-04-08
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "线边库余料退料审批")
@TableName("mes_warehouse_feed_judge")
public class MesWarehouseFeedJudgeEntity extends BaseEntity<MesWarehouseFeedJudgeEntity> {

    /**
     * 申请编码
     */
    @Schema(description = "申请编码")
    private String code;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

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
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String routeCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String routeName;

    /**
     * 退料数量
     */
    @Schema(description = "退料数量")
    private BigDecimal num;

    /**
     * 批次
     */
    @Schema(description = "批次")
    private String batchNo;

    /**
     * 退料原因
     */
    @Schema(description = "退料原因")
    private String reason;

    /**
     * 审批状态
     */
    @Schema(description = "审批状态")
    private String status;

    /**
     * 审批人
     */
    @Schema(description = "审批人")
    private String judgePersonCode;

    /**
     * 审批人姓名
     */
    @Schema(description = "审批人姓名")
    private String judgePersonName;

    /**
     * 审批时间
     */
    @Schema(description = "审批时间")
    private LocalDateTime judgeTime;

    /**
     * 审批意见
     */
    @Schema(description = "审批意见")
    private String opinion;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

