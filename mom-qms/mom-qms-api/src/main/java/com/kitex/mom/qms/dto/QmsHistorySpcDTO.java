package com.kitex.mom.qms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 质检历史DTO
 *
 * @author kitex
 * @since 2025-04-28 10:33:11
 */
@Data
@Schema(description = "质检历史DTO")
public class QmsHistorySpcDTO {

    /**
     * 质检数据，用逗号隔开
     */
    @Schema(description = "质检数据，用逗号隔开")
    @NotNull(message = "质检数据不能为空")
    private String inputData;

    /**
     * 上限
     */
    @Schema(description = "上限")
    @NotNull(message = "上限不能为空")
    private Double upper;

    /**
     * 下限
     */
    @Schema(description = "下限")
    @NotNull(message = "下限不能为空")
    private Double lower;
}
