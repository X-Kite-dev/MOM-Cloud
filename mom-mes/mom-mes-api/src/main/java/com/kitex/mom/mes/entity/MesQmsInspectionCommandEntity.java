package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * MES创建QMS工序质检任务的永久出站账。
 *
 * <p>它记录独立提交的原命令、真正开始远端调用的边界和QMS明确返回的任务主键。
 * OUTBOUND_STARTED不等于失败或未创建，必须沿原请求号向QMS重放核账。</p>
 */
@Data
@TenantTable
@TableName("mes_qms_inspection_command")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES工序质检永久出站账")
public class MesQmsInspectionCommandEntity extends BaseEntity<MesQmsInspectionCommandEntity> {
    @Schema(description = "来源系统永久请求号")
    private String requestId;

    @Schema(description = "MES工序任务编码")
    private String sourceTaskCode;

    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @Schema(description = "固定命令协议版本")
    private String commandVersion;

    @Schema(description = "不可变完整创建参数原文")
    private String commandSnapshot;

    @Schema(description = "完整创建参数SHA-256")
    private String commandFingerprint;

    @Schema(description = "REGISTERED/OUTBOUND_STARTED/QMS_CONFIRMED")
    private String status;

    @Schema(description = "QMS明确返回的质检任务主键")
    private Long qmsTaskId;

    @Schema(description = "实际远端调用次数")
    private Integer attemptCount;

    @Schema(description = "最近一次开始远端调用时间")
    private LocalDateTime lastAttemptTime;

    @Schema(description = "最近一次调用异常摘要；不据此判定远端未执行")
    private String lastError;
}
