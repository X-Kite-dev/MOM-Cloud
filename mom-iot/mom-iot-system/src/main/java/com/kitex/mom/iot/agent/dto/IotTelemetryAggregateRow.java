package com.kitex.mom.iot.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** IoT 属性历史在一个实际生产时间窗内的数据库聚合行。 */
@Data
public class IotTelemetryAggregateRow {
    private String identifier;
    private String parameterName;
    private String dataType;
    private String unit;
    private Integer numericUnitCount;
    private Long missingNumericUnitCount;
    private Long sampleCount;
    private Long goodSampleCount;
    private Long nonGoodSampleCount;
    private Long numericGoodSampleCount;
    private BigDecimal minimumValue;
    private BigDecimal maximumValue;
    private BigDecimal averageValue;
    private LocalDateTime firstCollectTime;
    private LocalDateTime lastCollectTime;
    private Long firstPropertyId;
    private Long lastPropertyId;
}
