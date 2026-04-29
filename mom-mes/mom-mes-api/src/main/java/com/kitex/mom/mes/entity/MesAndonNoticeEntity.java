package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * <p>
 * 安灯信息处理通知 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-18 16:47:43
 */
@Data
@TenantTable
@TableName("mes_andon_notice")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "安灯信息处理通知")
public class MesAndonNoticeEntity extends BaseEntity<MesAndonNoticeEntity> {


    /**
     * 处理记录编码
     */
    @Schema(description = "处理记录编码")
    private String recordCode;

    /**
     * 员工工号
     */
    @Schema(description = "员工工号")
    private String staffCode;

    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名")
    private String staffName;

    /**
     * 通知时间
     */
    @Schema(description = "通知时间")
    private LocalDateTime noticeTime;

    /**
     * 类别 handle处理人，notice通知人
     */
    @Schema(description = "类别 handle处理人，notice通知人")
    private String noticeType;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
