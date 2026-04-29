package com.kitex.mom.qms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 质检历史VO
 *
 * @author kitex
 * @since 2025-04-28 09:43:18
 */
@Data
@Schema(description = "质检历史VO")
public class QmsHistorySpcVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 质检值
     */
    @Schema(description = "质检值")
    private Double doubleValue;

    /**
     * 质检时间
     */
    @Schema(description = "质检时间")
    private Date time;

    /**
     * 是否合格
     */
    @Schema(description = "是否合格")
    private Boolean qualified;
}
