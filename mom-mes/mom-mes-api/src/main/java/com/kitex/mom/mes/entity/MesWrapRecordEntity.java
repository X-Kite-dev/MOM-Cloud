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

/**
 * <p>
 * 包装装箱扫码记录 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-04-01
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "包装装箱扫码记录")
@TableName("mes_wrap_record")
public class MesWrapRecordEntity extends BaseEntity<MesWrapRecordEntity> {

    @Schema(description = "关联报工记录ID")
    private Long reportId;

    @Schema(description = "已封装箱码；为空表示当前尚未封箱")
    private String wrapCode;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

    /**
     * 工序任务编号
     */
    @Schema(description = "工序任务编号")
    private String procedureTaskCode;

    /**
     * 派工单号
     */
    @Schema(description = "派工单号")
    private String arrangeCode;

    /**
     * 报工条码
     */
    @Schema(description = "报工条码")
    private String transferCode;

    /** 原报工的包装模式，按组织和生产来源回读。历史未能核实时为空，仅用于重组界面。 */
    @TableField(exist = false)
    @Schema(description = "原报工模式，PACKAGE_QUANTITY 可拆量，逐件只能整件移动")
    private String sourceReportMode;

    @Schema(description = "本包装内容数量")
    private BigDecimal quantity;

    @Schema(description = "拆箱后尚可用于重组的数量")
    private BigDecimal availableQuantity;

    @Schema(description = "内容状态 STAGED/PACKED/UNPACKED/PARTIAL/MOVED/REVERSED")
    private String packingStatus;

    @Schema(description = "重组内容的来源记录ID")
    private Long sourceRecordId;

    @Schema(description = "最近包装操作请求号")
    private String operationRequestId;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
