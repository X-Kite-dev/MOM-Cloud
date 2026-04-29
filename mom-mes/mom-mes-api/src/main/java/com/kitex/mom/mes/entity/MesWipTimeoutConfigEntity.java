package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("mes_wip_timeout_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "在制超时管控配置")
public class MesWipTimeoutConfigEntity extends BaseEntity<MesWipTimeoutConfigEntity> {

    @Schema(description = "配置编码")
    private String code;

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "产线编码")
    private String lineCode;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "L1 报警阈值（秒）")
    private Integer timeoutSec;

    @Schema(description = "L2 报警阈值（秒）")
    private Integer level2Sec;

    @Schema(description = "L3 报警阈值（秒）")
    private Integer level3Sec;

    @Schema(description = "L1 通知角色（逗号分隔）")
    private String level1NotifyRoles;

    @Schema(description = "L2 通知角色（逗号分隔）")
    private String level2NotifyRoles;

    @Schema(description = "L3 通知角色（逗号分隔）")
    private String level3NotifyRoles;

    @Schema(description = "是否启用 1=是 0=否")
    private String enabled;

    @Schema(description = "设置人账号")
    private String setPersonCode;

    @Schema(description = "设置人姓名")
    private String setPersonName;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
