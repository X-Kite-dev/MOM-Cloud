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
 * 实物转换传输原正文永久账；独立于预检证据及后续库存/位置领域账。
 * 本阶段仅REGISTERED与SEALED，不能将登记当成工艺资格、库存占用或执行成功。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_physical_transform_command")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换永久原命令")
public class MesPhysicalTransformCommandEntity extends BaseEntity<MesPhysicalTransformCommandEntity> {
    /**
     * 实际认证工厂。
     */
    @Schema(description = "实际认证工厂")
    private String factoryCode;
    /**
     * 租户内永久唯一原号，封存/归档不释放。
     */
    @Schema(description = "永久请求号")
    private String requestId;
    /**
     * 原正文算法版本。
     */
    @Schema(description = "原正文算法版本")
    private String commandVersion;
    /**
     * 版本域加完整传输字符串的SHA-256。
     */
    @Schema(description = "原正文指纹")
    private String requestFingerprint;
    /**
     * 原JSON字符串保持全部字符，不用当前DTO重新序列化旧命令。
     */
    @Schema(description = "完整原始命令正文")
    private String commandSnapshot;
    /**
     * 原登记的真实用户ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "实际原登记用户ID")
    private Long actorId;
    /**
     * 原登记的真实账号，不能仅凭ID重新解释改名后旧原号。
     */
    @Schema(description = "实际原登记账号")
    private String actorCode;
    /**
     * REGISTERED/SEALED/APPLIED；APPLIED必须由正式执行事务及数据库提交证明产生。
     */
    @Schema(description = "永久原号状态")
    private String status;
    /**
     * 原命令首次登记时间，不因重投修改。
     */
    @Schema(description = "首次登记时间")
    private LocalDateTime registeredTime;
    /**
     * 本人显式封存时间，未封存时为空。
     */
    @Schema(description = "永久封存时间")
    private LocalDateTime sealedTime;
    /**
     * 原封存原因；不覆盖原业务原因。
     */
    @Schema(description = "原封存原因")
    private String sealReason;

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
