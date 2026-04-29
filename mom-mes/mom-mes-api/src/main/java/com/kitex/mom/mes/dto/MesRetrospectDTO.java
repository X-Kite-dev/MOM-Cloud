package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 生产数据
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class MesRetrospectDTO {

    // ---------- 生产细节 -----------

    @Schema(description = "工作中心名称")
    private String worksiteName;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "操作员")
    private String workerName;

    @Schema(description = "温度")
    private BigDecimal temperature;

    @Schema(description = "压力")
    private BigDecimal pressure;

    @Schema(description = "转速")
    private BigDecimal speed;

    @Schema(description = "进给速度")
    private BigDecimal feedRate;

    // ---------- 半成品信息 -----------

    @Schema(description = "半成品批次")
    private String batchNo;

    @Schema(description = "加工时间")
    private BigDecimal finishTime;

    @Schema(description = "工序")
    private String routeCode;

    @Schema(description = "工序")
    private String routeName;
}
