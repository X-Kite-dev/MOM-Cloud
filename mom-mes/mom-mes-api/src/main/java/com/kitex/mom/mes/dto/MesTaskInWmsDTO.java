package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 生产工单入库DTO
 *
 * @author kitex
 * @date 2025-04-29 14:34:51
 */
@Data
@Schema(description = "生产工单入库DTO")
public class MesTaskInWmsDTO {

    @Schema(description = "本次入库永久幂等号；所有生产成品入库必填，失败重试必须复用")
    private String requestId;

    /**
     * 用户提交前核验的末工序报工来源指纹。
     *
     * <p>服务端在工单、派工、报工和历史来源明细加锁后重新计算，结果不一致时拒绝入库，
     * 防止预览后新增报工、质量放行或并发入库导致实际来源发生漂移。</p>
     */
    @Schema(description = "入库来源预览证据指纹；新入库必填")
    private String expectedSourceFingerprint;

    /**
     * 工单ID
     */
    @Schema(description = "工单ID")
    private Long id;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 成品入库量
     */
    @Schema(description = "成品入库量")
    private BigDecimal inWmsNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
