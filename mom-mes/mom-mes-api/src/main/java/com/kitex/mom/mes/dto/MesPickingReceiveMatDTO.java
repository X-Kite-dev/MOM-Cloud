package com.kitex.mom.mes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 领料出库单完成后返回结果
 *
 * @author kitex
 * @date 2025/2/28 16:05
 */
@Data
public class MesPickingReceiveMatDTO {
    /**
     * 生产领料单id
     */
    @NotNull
    private Long id;
    /**
     * 库位编码
     */
    @NotBlank
    private String siteCode;
    /**
     * 库位名称
     */
    @NotBlank
    private String siteName;
}
