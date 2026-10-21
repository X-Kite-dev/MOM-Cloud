package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 换型归属永久命令及原结果；仅追加，不以浏览器重试覆盖历史。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_changeover_attribution_operation")
@Schema(description = "换型归属永久命令及原结果；仅追加，不以浏览器重试覆盖历史")
public class MesChangeoverAttributionOperationEntity extends BaseEntity<MesChangeoverAttributionOperationEntity> {
    /**
     * 本命令对应案件ID。
     */
    @Schema(description = "本命令对应案件ID")
    private Long caseId;

    /**
     * 当前实际组织内永久命令号。
     */
    @Schema(description = "当前实际组织内永久命令号")
    private String requestId;

    /**
     * 含组织、实名、动作及规范完整载荷的SHA-256。
     */
    @Schema(description = "含组织、实名、动作及规范完整载荷的SHA-256")
    private String requestFingerprint;

    /**
     * SUBMIT/REVIEW/CANCEL/REVOKE。
     */
    @Schema(description = "SUBMIT/REVIEW/CANCEL/REVOKE")
    private String action;

    /**
     * 真实操作人ID。
     */
    @Schema(description = "真实操作人ID")
    private Long actorUserId;

    /**
     * 真实操作人用户名。
     */
    @Schema(description = "真实操作人用户名")
    private String actorCode;

    /**
     * 真实操作人姓名。
     */
    @Schema(description = "真实操作人姓名")
    private String actorName;

    /**
     * 完整规范请求JSON，原号恢复仅重发该载荷。
     */
    @Schema(description = "完整规范请求JSON，原号恢复仅重发该载荷")
    private String requestSnapshot;

    /**
     * 不可变原命令回执JSON。
     */
    @Schema(description = "不可变原命令回执JSON")
    private String resultSnapshot;

    /**
     * 实际工厂编码。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}

