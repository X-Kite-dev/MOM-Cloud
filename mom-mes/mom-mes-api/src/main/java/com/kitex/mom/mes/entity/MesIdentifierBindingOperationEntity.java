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
 * 与真实报工和原正文原子提交的不可变节点操作账。不存在单独绑定、使用或释放的CRUD入口。
 * 一次USE是一笔正式节点证据，不会改写实例生命周期或先前历史。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_identifier_binding_operation")
@Schema(description = "动态外码不可变节点操作")
public class MesIdentifierBindingOperationEntity extends BaseEntity<MesIdentifierBindingOperationEntity> {

    /**
     * 当前实际授权工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 不可变的具体占用实例主键，不能用外码反查后覆盖旧事件。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "占用实例ID文本")
    private Long bindingId;

    /**
     * 操作核定依据的永久区段主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "永久区段ID文本")
    private Long bindingGroupId;

    /**
     * 同一原号对应的成功报工。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "报工ID文本")
    private Long reportId;

    /**
     * 正向是报工原号，补偿是审批冻结的逐报工冲销号；二者均永久保留。
     */
    @Schema(description = "本次不可变操作永久请求号")
    private String requestId;

    /**
     * 本次原正文算法，不以当前算法解释历史。
     */
    @Schema(description = "原正文版本")
    private String commandVersion;

    /**
     * 解析前正文SHA-256，不是解析后的单个SN摘要。
     */
    @Schema(description = "原正文SHA-256")
    private String requestFingerprint;

    /**
     * 从冻结契约核定的节点动作。
     */
    @Schema(description = "节点动作：BIND/USE/RELEASE及REVERSE_BIND/REVERSE_USE/REVERSE_RELEASE")
    private String action;

    /**
     * 正式派工编码，具体节点由永久图核定。
     */
    @Schema(description = "派工单号")
    private String arrangeCode;

    /**
     * 当前具体工序编码。
     */
    @Schema(description = "工序编码")
    private String routeCode;

    /**
     * 本次实际生命周期，不能把恢复活动3的补偿倒写成原正向版本1。
     */
    @Schema(description = "操作前生命周期版本")
    private Integer versionBefore;

    /**
     * 生命周期变动单调递增；USE及其逆向保持本次版本。
     */
    @Schema(description = "操作后生命周期版本")
    private Integer versionAfter;

    /**
     * 服务端核定的原扫描、实例与永久依据快照。
     */
    @Schema(description = "节点动作核定依据JSON")
    private String evidenceSnapshot;

    /**
     * 补偿引用的唯一原正向操作，原操作永不改写；正向为空。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "原正向操作ID文本")
    private Long originalOperationId;

    /**
     * 异岗审批申请主键，不接受客户端声明已批准。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "受控冲销申请ID文本")
    private Long reverseRequestId;

    /**
     * 申请冻结的完整事实摘要；原requestFingerprint仍保存原报工正文摘要。
     */
    @Schema(description = "冲销审批冻结事实SHA-256")
    private String approvalEvidenceFingerprint;

    /**
     * 补偿独立算法MES_BINDING_INVERSE_V1的完整原始正文，不覆盖原报工命令。
     */
    @Schema(description = "不可变逆向命令JSON")
    private String inverseCommandSnapshot;

    /**
     * SHA-256(MES_BINDING_INVERSE_V1:原始JSON)，独立于原报工摘要。
     */
    @Schema(description = "逆向命令SHA-256")
    private String inverseCommandFingerprint;

    /**
     * 真实认证发起人，设备实际人员仍由原报工保存。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "发起人员ID文本")
    private Long actorId;

    /**
     * 真实认证发起账号。
     */
    @Schema(description = "发起账号编码")
    private String actorCode;

    /**
     * 本地事务执行时间。
     */
    @Schema(description = "操作时间")
    private LocalDateTime operationTime;

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
