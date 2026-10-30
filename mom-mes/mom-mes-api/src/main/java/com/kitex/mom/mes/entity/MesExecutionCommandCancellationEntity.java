package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 永久原号取消墓碑；不继承可更新业务实体，不提供改删入口。
 */
@Data
@TableName(value = "mes_execution_command_cancellation", schema = "mom_mes")
public class MesExecutionCommandCancellationEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String factoryCode;
    private String commandKind;
    private String requestId;
    private String requestHash;
    private Long actorId;
    private String createBy;
    private LocalDateTime createTime;
    private String delFlag;
}
