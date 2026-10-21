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
 * 移动派工原命令永久账；登记与执行占用独立提交，APPLIED与MES动作同事务。
 * 未决原号不自动重放，远端生产写或提交结果不明保留UNKNOWN。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_terminal_execution_command")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "移动派工原命令与永久回执")
public class MesTerminalExecutionCommandEntity extends BaseEntity<MesTerminalExecutionCommandEntity> {
    /**
     * 实际认证工厂。
     */
    private String factoryCode;
    /**
     * 租户内永久唯一请求号，删除不得释放。
     */
    private String requestId;
    /**
     * 原正文算法版本。
     */
    private String commandVersion;
    /**
     * 带算法域前缀的原正文SHA-256。
     */
    private String requestFingerprint;
    /**
     * 完整不可变原正文JSON，所有ID为文本。
     */
    private String commandSnapshot;
    /**
     * 实际派工ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;
    /**
     * 实际派工编码。
     */
    private String arrangeCode;
    /**
     * 实际生产工单编码，锁目标不能由客户端另行指定。
     */
    private String taskCode;
    /**
     * RECEIVE/START/PAUSE/RESUME/FINISH。
     */
    private String action;
    /**
     * 实名执行人ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;
    /**
     * 实名执行人账号。
     */
    private String actorCode;
    /**
     * 登记时全部本地派工事实JSON。
     */
    private String beforeFactsSnapshot;
    /**
     * 登记事实指纹，不因拒绝或未知而改写。
     */
    private String beforeFactsFingerprint;
    /**
     * PREPARED/EXECUTING/APPLIED/REJECTED/UNKNOWN。
     */
    private String status;
    /**
     * 同MES事务冻结的结果JSON；未知不伪造成功结果。
     */
    private String resultSnapshot;
    /**
     * 领域拒绝或未知原因，不宣称跨服务全部回滚。
     */
    private String message;
    /**
     * 是否曾尝试远端生产写，服务端词法上下文决定。
     */
    private Boolean remoteWritePossible;
    /**
     * 永久原命令登记时间。
     */
    private LocalDateTime registeredTime;
    /**
     * 独立提交的执行占用时间。
     */
    private LocalDateTime claimedTime;
    /**
     * APPLIED/REJECTED/UNKNOWN的登记时间。
     */
    private LocalDateTime resolvedTime;

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
