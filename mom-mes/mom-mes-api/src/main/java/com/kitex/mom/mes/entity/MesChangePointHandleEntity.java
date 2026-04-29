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
import java.time.LocalDateTime;

/**
 * 变化点信息处理 实体类
 *
 * @author kitex
 * @since 2025-05-26
 */
@Data
@TenantTable
@TableName("mes_change_point_handle")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点信息处理")
public class MesChangePointHandleEntity extends BaseEntity<MesChangePointHandleEntity> {

    /**
     * 变化点编码
     */
    @Schema(description = "变化点编码")
    private String configCode;

    /**
     * 变化点类型
     */
    @Schema(description = "变化点类型")
    private String configName;

    /**
     * 变化点内容
     */
    @Schema(description = "变化点内容")
    private String configContent;

    /**
     * 等级
     */
    @Schema(description = "等级")
    private String configLevel;

    /**
     * 上限
     */
    @Schema(description = "上限（配置快照）")
    private BigDecimal upperLimit;

    /**
     * 下限
     */
    @Schema(description = "下限（配置快照）")
    private BigDecimal lowerLimit;

    @Schema(description = "产品条码/流转码")
    private String transferCode;

    @Schema(description = "产线编码")
    private String lineCode;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "大类（冗余，方便过滤）")
    private String category;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "监控字段标识")
    private String monitorField;

    @Schema(description = "实际采集值（数值型变化点）")
    private BigDecimal actualValue;

    @Schema(description = "触发来源：MANUAL=人工 / AUTO=自动")
    private String triggerSource;

    @Schema(description = "本次报警时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime alertTime;

    @Schema(description = "本次报警等级（字典）")
    private String alertLevel;

    @Schema(description = "是否已处理 0=否 1=是")
    private String handled;

    @Schema(description = "处理人")
    private String handleBy;

    /**
     * 通知用户
     */
    @Schema(description = "通知用户")
    private String noticePersonCode;

    /**
     * 通知用户姓名
     */
    @Schema(description = "通知用户姓名")
    private String noticePersonName;

    /**
     * 触发时间
     */
    @Schema(description = "触发时间")
    private LocalDateTime triggerTime;


    /**
     * 实际情况
     */
    @Schema(description = "实际情况")
    private String actualSituation;

    /**
     * 处理角色
     */
    @Schema(description = "处理角色")
    private String handleRole;

    /**
     * 处理人员
     */
    @Schema(description = "处理人员")
    private String handlePersonCode;

    /**
     * 处理人员姓名
     */
    @Schema(description = "处理人员姓名")
    private String handlePersonName;

    /**
     * 解决时间
     */
    @Schema(description = "解决时间")
    private LocalDateTime handleTime;

    /**
     * 解决说明
     */
    @Schema(description = "解决说明")
    private String handleDesc;

    /**
     * 状态 待解决、已完成
     */
    @Schema(description = "状态 待解决、已完成")
    private String status;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
