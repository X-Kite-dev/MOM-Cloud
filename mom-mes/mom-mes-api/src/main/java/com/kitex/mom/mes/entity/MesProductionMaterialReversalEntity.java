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
 * 误登记NG投料耗用的永久全额恢复账；不取消不良声明或生产数量。
 */
@Data
@TenantTable
@TableName("mes_production_material_reversal")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "误登记NG投料耗用的永久全额恢复账；不取消不良声明或生产数量。")
public class MesProductionMaterialReversalEntity extends BaseEntity<MesProductionMaterialReversalEntity> {
    /**
     * 独立恢复请求号，不复用原不良请求号。
     */
    private String requestId;
    /**
     * 精确原FEED耗用账ID，不是GOOD报告ID。
     */
    private Long operationId;
    /**
     * 原真实NG来源，恢复不使该声明失效。
     */
    private Long sourceUnqualifiedReportId;
    /**
     * 本次已认证恢复操作人。
     */
    private Long actorId;
    /**
     * 明确的耗料账纠正原因，不代表实物退料。
     */
    private String reason;
    /**
     * 原耗用完整字符串的版本域SHA。
     */
    private String originalFingerprint;
    /**
     * 严格恢复请求协议版本。
     */
    private String commandVersion;
    /**
     * 原请求完整传输字符串，重投不得改字符。
     */
    private String commandSnapshot;
    /**
     * 请求版本域SHA，含实际期望身份及原operation。
     */
    private String commandFingerprint;
    /**
     * 服务器恢复分配协议版本。
     */
    private String evidenceVersion;
    /**
     * 完整逐feed恢复证据，保留全部原allocation ID。
     */
    private String evidenceSnapshot;
    /**
     * 恢复证据原字符串的版本域SHA。
     */
    private String evidenceFingerprint;
    /**
     * MATERIAL_RESTORED；只表示耗料账已全额恢复。
     */
    private String status;
    /**
     * 实际工厂归属，客户端期望值不作为执行上下文。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
