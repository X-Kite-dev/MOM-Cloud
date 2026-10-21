package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 投料永久请求结果；APPLIED只对应一次真实出生，CANCELLED只证明原号永不执行。
 */
@Data
@TenantTable
@TableName("mes_warehouse_feed_command")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "投料永久请求结果，不是可编辑投料草稿")
public class MesWarehouseFeedCommandEntity extends BaseEntity<MesWarehouseFeedCommandEntity> {
    /**
     * 实际认证工厂，不从客户端切换上下文。
     */
    @Schema(description = "实际认证工厂")
    private String factoryCode;
    /**
     * 客户端首次同步持久化的永久请求号。
     */
    @Schema(description = "永久请求号")
    private String requestId;
    /**
     * 实际首次认证本人主键。
     */
    @Schema(description = "实际认证本人")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;
    /**
     * APPLIED或CANCELLED，不存在可由客户端抹除的未知成功。
     */
    @Schema(description = "永久终态")
    private String status;
    /**
     * APPLIED的真实新投料主键；作废时为空。
     */
    @Schema(description = "真实投料主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long feedId;
    /**
     * APPLIED的完整出生主账主键；作废时为空。
     */
    @Schema(description = "真实出生主账主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long birthId;
    /**
     * 完整HTTP原文的固定协议版本。
     */
    @Schema(description = "原命令版本")
    private String commandVersion;
    /**
     * 原命令逐字符字符串；禁止重建或事后改写。
     */
    @Schema(description = "原命令全文")
    private String commandSnapshot;
    /**
     * 原版本域和全文的SHA256。
     */
    @Schema(description = "原命令版本域SHA256")
    private String commandFingerprint;
    /**
     * 终态证据独立协议版本。
     */
    @Schema(description = "终态证据版本")
    private String evidenceVersion;
    /**
     * 真实身份、原命令指纹、出生指纹或作废原因的固定原文。
     */
    @Schema(description = "终态证据全文")
    private String evidenceSnapshot;
    /**
     * 终态原版本域SHA256。
     */
    @Schema(description = "终态证据SHA256")
    private String evidenceFingerprint;
    /**
     * 永久作废原因；成功时为空，不撤销已投料实物。
     */
    @Schema(description = "永久作废原因")
    private String cancellationReason;
}
