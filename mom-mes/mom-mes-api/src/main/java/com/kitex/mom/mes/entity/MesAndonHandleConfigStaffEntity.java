package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 安灯异常处理配置关联人员 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-18 16:23:18
 */
@Data
@TenantTable
@TableName("mes_andon_handle_config_staff")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "安灯异常处理配置关联人员")
public class MesAndonHandleConfigStaffEntity extends BaseEntity<MesAndonHandleConfigStaffEntity> {

    /**
     * 异常编码
     */
    @Schema(description = "异常编码")
    private String configCode;

    /**
     * 级别 serious严重，general一般
     */
    @Schema(description = "级别 serious严重，general一般")
    private String level;

    /**
     * 分类 0处理人，1一级通知人，2二级通知人，3三级通知人...
     */
    @Schema(description = "分类 0处理人，1一级通知人，2二级通知人，3三级通知人...")
    private String sort;

    /**
     * 班组编码
     */
    @Schema(description = "班组编码")
    private String teamCode;

    /**
     * 员工号
     */
    @Schema(description = "员工号")
    private String staffCode;

    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名")
    private String staffName;

    /**
     * 处理限时 分钟
     */
    @Schema(description = "处理限时 分钟")
    private Integer limitTime;
}
