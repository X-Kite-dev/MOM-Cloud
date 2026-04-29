package com.kitex.mom.iot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * IoT设备消息趋势数据
 *
 * @author claude
 * @date 2025-03-14
 */
@Data
@Schema(description = "IoT设备消息趋势数据")
public class IotMessageTrendDTO {

    /**
     * X轴数据（时间点）
     */
    @Schema(description = "X轴数据")
    private List<String> xAxis;

    /**
     * 消息数量数据
     */
    @Schema(description = "消息数量数据")
    private List<Integer> series;
} 