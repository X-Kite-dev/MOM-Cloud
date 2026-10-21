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

    /**
     * 新入库命令协议；旧内部调用保持空值，不能借此字段跳过原业务校验。
     */
    @Schema(description = "MES_TASK_IN_WMS_COMMAND_V2")
    private String commandVersion;

    /**
     * 原提交所见租户，只与实际认证上下文比较。
     */
    @Schema(description = "期望租户主键文本")
    private String expectedTenantId;

    /**
     * 原提交所见工厂，不授予工厂权限。
     */
    @Schema(description = "期望工厂编码")
    private String expectedFactoryCode;

    /**
     * 原提交实名用户，避免跨窗口切换账号后使用新身份执行旧参数。
     */
    @Schema(description = "期望用户主键文本")
    private String expectedActorId;

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
