package com.kitex.mom.qms.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * app统计显示
 *
 * @author kitex
 * @since 2025-05-10
 */
@Data
public class QmsStatisticsDTO {

    @Schema(description = "状态")
    private String name;

    @Schema(description = "数量")
    private Integer num;


    public QmsStatisticsDTO(String name, Integer num) {
        this.name = name;
        this.num = num;
    }

}
