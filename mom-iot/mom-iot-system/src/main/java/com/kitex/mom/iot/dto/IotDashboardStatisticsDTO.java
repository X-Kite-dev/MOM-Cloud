package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * IoT设备看板统计数据
 *
 * @author claude
 * @date 2025-03-14
 */
@Data
@Schema(description = "IoT设备看板统计数据")
public class IotDashboardStatisticsDTO {

    /**
     * 产品总数
     */
    @Schema(description = "产品总数")
    private Integer productTotal;

    /**
     * 正常产品数
     */
    @Schema(description = "正常产品数")
    private Integer productNormal;

    /**
     * 禁用产品数
     */
    @Schema(description = "禁用产品数")
    private Integer productDisabled;

    /**
     * 设备总数
     */
    @Schema(description = "设备总数")
    private Integer deviceTotal;

    /**
     * 在线设备数
     */
    @Schema(description = "在线设备数")
    private Integer deviceOnline;

    /**
     * 离线设备数
     */
    @Schema(description = "离线设备数")
    private Integer deviceOffline;

    /**
     * 当前在线设备数
     */
    @Schema(description = "当前在线设备数")
    private Integer currentOnline;

    /**
     * 总计在线设备数（历史累计）
     */
    @Schema(description = "总计在线设备数")
    private Integer totalOnline;

    /**
     * 今日消息数
     */
    @Schema(description = "今日消息数")
    private Integer todayMessages;

    /**
     * 当月消息数
     */
    @Schema(description = "当月消息数")
    private Integer monthMessages;
} 