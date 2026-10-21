package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 未受理原号的永久封存证据；与成功账互斥，不删除原号或制造成功报工。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_request_close")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "报工未受理原号永久封存账")
public class MesReportRequestCloseEntity extends BaseEntity<MesReportRequestCloseEntity> {

    /**
     * 操作者实际授权工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 租户内永久唯一原号，封存后不能再被迟到请求使用。
     */
    @Schema(description = "永久原请求号")
    private String requestId;

    /**
     * 原命令算法版本，不反推升级前未知历史。
     */
    @Schema(description = "原正文算法版本")
    private String commandVersion;

    /**
     * 原解析前正文的域分离SHA-256。
     */
    @Schema(description = "原命令正文指纹")
    private String requestFingerprint;

    /**
     * 原公开输入、可信入口及真实组织人员的完整JSON。
     */
    @Schema(description = "不可变原命令正文")
    private String commandSnapshot;

    /**
     * 由服务端入口指定STATION/MOBILE，不接收客户端选择。
     */
    @Schema(description = "原可信入口")
    private String channel;

    /**
     * 原输入的精确派工号，仅作为未执行原命令证据，不造生产事实。
     */
    @Schema(description = "原派工单号")
    private String arrangeCode;

    /**
     * 原实名用户ID，以文本交付。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "原实名用户ID")
    private Long actorId;

    /**
     * 原实名账号编码。
     */
    @Schema(description = "原实名账号编码")
    private String actorCode;

    /**
     * 固定CLOSED_UNACCEPTED，与成功及已冲销状态分别留证。
     */
    @Schema(description = "封存状态")
    private String status;

    /**
     * 封存原因不可在重试时覆盖。
     */
    @Schema(description = "封存原因")
    private String reason;

    /**
     * 请求锁下确认未成功并封存的时间。
     */
    @Schema(description = "封存时间")
    private LocalDateTime closedTime;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}
