package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 成品入库出站命令永久账；记录发送边界，不冒充仓库接收或实收回执。
 */
@Data
@TenantTable
@TableName("mes_task_in_wms_command")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "成品入库出站命令永久账")
public class MesTaskInWmsCommandEntity extends BaseEntity<MesTaskInWmsCommandEntity> {
    /**
     * 租户内永久唯一的V2请求号，取消后不可重开。
     */
    @Schema(description = "永久请求号")
    private String requestId;
    /**
     * 首次实际认证操作者。
     */
    @Schema(description = "实际操作者主键")
    private Long actorId;
    /**
     * 实际工厂，不能由期望字段赋权。
     */
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
    /**
     * 固定命令版本，不修改旧入库V1指纹。
     */
    @Schema(description = "命令协议版本")
    private String commandVersion;
    /**
     * 严格固定字段的完整参数，服务端按文本数量和身份生成。
     */
    @Schema(description = "不可变完整命令原文")
    private String commandSnapshot;
    /**
     * 版本域与完整原文的SHA-256。
     */
    @Schema(description = "命令SHA-256")
    private String commandFingerprint;
    /**
     * REGISTERED/OUTBOUND_STARTED/CANCELLED_BEFORE_DISPATCH；未知不自动取消。
     */
    @Schema(description = "命令出站状态")
    private String status;
}
