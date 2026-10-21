package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工程联合发布永久命令账，原回执不可覆盖。 */
@Data
@TenantTable
@TableName("mdm_route_engineering_command")
@EqualsAndHashCode(callSuper = true)
public class MdmRouteEngineeringCommandEntity extends BaseEntity<MdmRouteEngineeringCommandEntity> {
    /** 主键以文本出网，避免前端精度损失。 */
    @Override
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Long getId() {
        return super.getId();
    }
    /** 实际租户以文本出网。 */
    @Override
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Long getTenantId() {
        return super.getTenantId();
    }
    /** 实际授权工厂编码。 */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /** 同租户工厂内永久请求号。 */
    private String requestId;

    /** 完整命令原文UTF-8字节SHA256，不通过JSON重排计算。 */
    private String payloadHash;

    /** 完整命令原文，包含当次依据，不能用不可反推的hash替代审计。 */
    private String commandSnapshot;

    /** 执行命令的实名用户ID。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long actorUserId;

    /** 执行命令的用户名称。 */
    private String actorName;

    /** SUBMIT/APPROVE/REJECT/RETIRE/WITHDRAW。 */
    private String action;

    /** SUCCESS为工程执行结果，SEALED为尚未执行原号的永久墓碑。 */
    private String outcome;

    /** 成功动作的精确工程版本ID；SEALED不指向/变更工程版本。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long releaseId;

    /** 该命令完成时固定回执原文。 */
    private String receiptSnapshot;
}
