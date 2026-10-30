package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * 手工建单接受或未创建取消的不可变原件，不随主单逻辑删除。
 */
@Data
@Accessors(chain = true)
@TableName("mom_mes.mes_manual_task_request")
public class MesManualTaskRequestEntity {
    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;
    private String factoryCode;
    private String requestId;
    private Long actorId;
    private String commandPayload;
    private String commandFingerprint;
    private String state;
    private Long taskId;
    private String taskCode;
    private OffsetDateTime createdAt;
}
