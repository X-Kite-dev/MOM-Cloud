package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 工作日历DTO
 *
 * @author kitex
 * @date 2025-02-17 16:00:32
 */
@Data
@Schema(description = "工作日历DTO")
public class MdmCalendarDTO {
    /**
     * 时间
     */
    @Schema(description = "时间")
    private LocalDate date;

    /**
     * 类型
     */
    @Schema(description = "类型")
    private String type;
}