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

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

