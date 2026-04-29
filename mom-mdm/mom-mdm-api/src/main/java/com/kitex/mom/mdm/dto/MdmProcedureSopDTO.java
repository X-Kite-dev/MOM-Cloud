package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工序-SOP
 *
 * @author kitex
 * @date 2025-03-18 09:27:12
 */
@Data
public class MdmProcedureSopDTO {

    /**
     * 原始文件名
     */
    @Schema(description = "原始文件名")
    private String name;

    /**
     * 文件名
     */
    @Schema(description = "文件名")
    private String fileName;

    /**
     * url
     */
    @Schema(description = "url")
    private String url;

}