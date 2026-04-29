package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工序-工位绑定DTO
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@Schema(description = "工序-工位绑定DTO")
public class ProcedureStationBindDTO {

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String procedureCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String procedureName;

    /**
     * 绑定的工位列表
     */
    @Schema(description = "绑定的工位列表")
    private List<StationBindInfo> stations;

    @Data
    @Schema(description = "工位绑定信息")
    public static class StationBindInfo {

        /**
         * 工位ID
         */
        @Schema(description = "工位ID")
        private Long stationId;

        /**
         * 工位编码
         */
        @Schema(description = "工位编码")
        private String stationCode;

        /**
         * 工位名称
         */
        @Schema(description = "工位名称")
        private String stationName;

        /**
         * 是否主工位
         */
        @Schema(description = "是否主工位")
        private Boolean isPrimary;

        /**
         * 优先级
         */
        @Schema(description = "优先级")
        private Integer priority;

        /**
         * 标准工时（分钟）
         */
        @Schema(description = "标准工时（分钟）")
        private BigDecimal standardWorkingTime;

        /**
         * 工位产能（件/小时）
         */
        @Schema(description = "工位产能（件/小时）")
        private BigDecimal stationCapacity;

        /**
         * 线体信息
         */
        @Schema(description = "线体信息")
        private LineInfo line;
    }

    @Data
    @Schema(description = "线体信息")
    public static class LineInfo {

        /**
         * 线体ID
         */
        @Schema(description = "线体ID")
        private Long lineId;

        /**
         * 线体编码
         */
        @Schema(description = "线体编码")
        private String lineCode;

        /**
         * 线体名称
         */
        @Schema(description = "线体名称")
        private String lineName;

        /**
         * 车间信息
         */
        @Schema(description = "车间信息")
        private WorkshopInfo workshop;
    }

    @Data
    @Schema(description = "车间信息")
    public static class WorkshopInfo {

        /**
         * 车间ID
         */
        @Schema(description = "车间ID")
        private Long workshopId;

        /**
         * 车间编码
         */
        @Schema(description = "车间编码")
        private String workshopCode;

        /**
         * 车间名称
         */
        @Schema(description = "车间名称")
        private String workshopName;
    }
}