package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 永久采集与异人决策事件，只追加不可改删。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_position_operation")
@Schema(description = "永久采集与异人决策事件，只追加不可改删")
public class MesPhysicalPositionOperationEntity extends BaseEntity<MesPhysicalPositionOperationEntity> {
    /**
     * 实际工厂，与原主账一致。
     */
    private String factoryCode;
    /**
     * 永久位置观察主账ID。
     */
    private Long surveyId;
    /**
     * 永久操作原号，租户内不可换正文重用。
     */
    private String requestId;
    /**
     * OBSERVE/CONFIRM/REJECT/CANCEL。
     */
    private String action;
    /**
     * 明确修订序，不按时间或ID猜事件先后。
     */
    private Long revision;
    /**
     * 本次实际认证用户ID。
     */
    private Long actorId;
    /**
     * 本次实际实名账号。
     */
    private String actorCode;
    /**
     * 本次操作自身协议版本。
     */
    private String requestVersion;
    /**
     * 本次操作完整原字符串，永久保留。
     */
    private String requestSnapshot;
    /**
     * 本次操作版本域与原字符串SHA。
     */
    private String requestFingerprint;
    /**
     * 该明确操作时间。
     */
    private LocalDateTime occurredTime;
    /**
     * 异人确认时服务器重新读取的BUNDLE_V2原文；其他动作为null。
     */
    private String verificationSnapshot;
    /**
     * 重读包自身BUNDLE_V2版本域SHA；不覆写原采集证据。
     */
    private String verificationFingerprint;
    /**
     * 数据库记录该操作实际事务因果号，不是权限。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long operationXid;
}
