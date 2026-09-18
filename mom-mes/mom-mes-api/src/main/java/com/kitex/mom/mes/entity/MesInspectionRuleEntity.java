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
import java.util.List;

/**
 * <p>
 * 质检任务生成规则 实体类
 * </p>
 *
 * @author kitex
 * @since 2024-02-06
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "质检任务生成规则")
@TableName("mes_inspection_rule")
public class MesInspectionRuleEntity extends BaseEntity<MesInspectionRuleEntity> {

    /**
     * 编码
     */
    @Schema(description = "编码")
    private String code;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 触发方式 小时 班次
     */
    @Schema(description = "触发方式 小时 班次")
    private String method;

    /**
     * 检验类型 首中末
     */
    @Schema(description = "检验类型 首中末")
    private String inspectionTypes;

    /**
     * 时间间隔 N小时 N班次
     */
    @Schema(description = "时间间隔 N小时 N班次")
    private BigDecimal num;

    /**
     * 是否启用1启用0停用
     */
    @Schema(description = "是否启用1启用0停用")
    private String status;

    /**
     * 质量门控制模式：NONE关闭、WARN仅预警、BLOCK严格阻断。
     */
    @Schema(description = "质量门控制模式 NONE/WARN/BLOCK")
    private String gateMode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matNames;

    /**
     * 上次生成时间
     */
    @Schema(description = "上次生成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastGenTime;

    /**
     * 上次生成班次
     */
    @Schema(description = "上次生成班次")
    private String lastGenShift;

    /**
     * 关联物料列表
     */
    @Schema(description = "关联物料列表")
    @TableField(exist = false)
    private List<MesInspectionRuleMatEntity> matList;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
