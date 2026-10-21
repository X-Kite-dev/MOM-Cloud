package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 未执行采集原号的永久作废终态，不撤生产、库存或质量事实，也不可删除后重用原号。
 */
@Data
@TenantTable
@TableName("mes_production_ng_cancellation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产NG采集原号永久作废账")
public class MesProductionNgCancellationEntity extends BaseEntity<MesProductionNgCancellationEntity> {
    @Schema(description = "原实际工厂")
    private String factoryCode;
    @Schema(description = "被永久作废的原采集请求号")
    private String requestId;
    @Schema(description = "原采集认证操作人，仅本人可作废")
    private Long actorId;
    @Schema(description = "作废原因，最多500字符")
    private String reason;
    @Schema(description = "原采集协议版本")
    private String commandVersion;
    @Schema(description = "完整原采集字符串，不修改错误字段")
    private String commandSnapshot;
    @Schema(description = "原采集版本域SHA")
    private String commandFingerprint;
    @Schema(description = "永久作废证据版本")
    private String evidenceVersion;
    @Schema(description = "作废原证据完整字符串")
    private String evidenceSnapshot;
    @Schema(description = "作废原证据版本域SHA")
    private String evidenceFingerprint;
    @Schema(description = "固定CANCELLED，不代表生产或库存冲销")
    private String status;
}
