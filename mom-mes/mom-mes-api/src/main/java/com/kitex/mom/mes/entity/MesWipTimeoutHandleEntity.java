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

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mes_wip_timeout_handle")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "在制超时管控记录")
public class MesWipTimeoutHandleEntity extends BaseEntity<MesWipTimeoutHandleEntity> {

    @Schema(description = "关联配置编码")
    private String configCode;

    @Schema(description = "产线编码")
    private String lineCode;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "产品流转码/SN")
    private String transferCode;

    @Schema(description = "进站时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime enterTime;

    @Schema(description = "本次报警时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime alertTime;

    @Schema(description = "已超时秒数")
    private Integer overtimeSec;

    @Schema(description = "当前报警等级 1/2/3")
    private Integer alertLevel;

    @Schema(description = "处理人账号")
    private String handlePersonCode;

    @Schema(description = "处理人姓名")
    private String handlePersonName;

    @Schema(description = "处理时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handleTime;

    @Schema(description = "处理说明")
    private String handleDesc;

    @Schema(description = "状态 0=待处理 1=已处理")
    private String status;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
