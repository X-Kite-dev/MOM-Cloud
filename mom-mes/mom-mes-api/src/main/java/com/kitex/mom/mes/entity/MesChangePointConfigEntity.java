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

import java.math.BigDecimal;

/**
 * 变化点配置 实体类
 *
 * @author kitex
 * @since 2025-05-26
 */
@Data
@TenantTable
@TableName("mes_change_point_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点配置")
public class MesChangePointConfigEntity extends BaseEntity<MesChangePointConfigEntity> {

    /**
     * 变化点编码
     */
    @Schema(description = "变化点编码")
    private String code;

    /**
     * 变化点类型
     */
    @Schema(description = "变化点类型")
    private String type;

    /**
     * 变化点内容
     */
    @Schema(description = "变化点内容")
    private String content;

    /**
     * 等级
     */
    @Schema(description = "等级")
    private String level;

    @Schema(description = "变化点名称")
    private String name;

    @Schema(description = "大类：MAN/MACHINE/MATERIAL/METHOD/ENV")
    private String category;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "监控字段标识（如 TEMP/PRESSURE/TORQUE）")
    private String monitorField;

    @Schema(description = "监控单位（℃/MPa/N·m）")
    private String monitorUnit;

    @Schema(description = "通知角色列表（逗号分隔）")
    private String notifyRoles;

    @Schema(description = "产线编码，* 表示全局默认")
    private String lineCode;

    @Schema(description = "工序编码，* 表示全局默认")
    private String routeCode;

    @Schema(description = "是否启用 1=是 0=否")
    private String enabled;

    /**
     * 上限
     */
    @Schema(description = "上限")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal upperLimit;

    /**
     * 下限
     */
    @Schema(description = "下限")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal lowerLimit;

    /**
     * 设置用户账号
     */
    @Schema(description = "设置用户账号")
    private String setPersonCode;

    /**
     * 设置用户姓名
     */
    @Schema(description = "设置用户姓名")
    private String setPersonName;

    /**
     * 是否需要验证
     */
    @Schema(description = "是否需要验证")
    private Boolean isVerify;

    /**
     * 验证角色
     */
    @Schema(description = "验证角色")
    private String verifyRole;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
