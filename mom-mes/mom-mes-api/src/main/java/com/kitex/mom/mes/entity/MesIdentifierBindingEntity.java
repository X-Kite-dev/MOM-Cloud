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
 * 外码一次真实占用的实例。释放后再次使用同一外码必须产生新的ID，历史实例永久保留。
 * 内部SN始终是生产追溯主键，外码不是新产品、静态别名或可编辑主数据。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_identifier_binding")
@Schema(description = "动态外码占用实例")
public class MesIdentifierBindingEntity extends BaseEntity<MesIdentifierBindingEntity> {

    /**
     * 认证链路核定的实际工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 首次派工已冻结的永久区段，而非当前模板。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "永久区段ID文本")
    private Long bindingGroupId;

    /**
     * 所属物理生产工单主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "生产工单ID文本")
    private Long taskId;

    /**
     * 占用时核定的原生产工单号。
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 同一工厂内外码占用的业务命名空间。
     */
    @Schema(description = "外码命名空间，最长100字符")
    private String externalNamespace;

    /**
     * 字面规范化的实物外码，生命周期结束也不删除。
     */
    @Schema(description = "外码，最长200字符")
    private String externalCode;

    /**
     * 实际内部单件SN，所有WIP、质量和库存仍用此标识。
     */
    @Schema(description = "内部规范单件SN，最长200字符")
    private String canonicalIdentifier;

    /**
     * 首版只支持正常生产路线版本1，不继承退回/返工占用。
     */
    @Schema(description = "内部标识的实际路线版本")
    private Integer identifierRouteVersion;

    /**
     * ACTIVE占用、RELEASED正常释放、REVERSED已受控撤回原绑定；历史实例均永久保留。
     */
    @Schema(description = "占用状态：ACTIVE/RELEASED/REVERSED")
    private String status;

    /**
     * BIND从1开始；释放/恢复/撤回单调递增，USE及其逆向不递增。活动版本为奇数。
     */
    @Schema(description = "占用生命周期版本")
    private Integer versionNo;

    /**
     * 实际成功绑定节点报工，不接受独立预占。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "绑定报工ID文本")
    private Long bindReportId;

    /**
     * 最近真实成功释放报工；受控恢复活动后仍保留，不抹去释放来源。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "释放报工ID文本")
    private Long releaseReportId;

    /**
     * 服务器成功绑定时刻，不使用设备未核定时间。
     */
    @Schema(description = "绑定时间")
    private LocalDateTime bindTime;

    /**
     * 服务器成功释放时刻，失败报工绝不能提前释放。
     */
    @Schema(description = "释放时间")
    private LocalDateTime releaseTime;

    /**
     * 数据库在标识业务锁内生成的占用先后序号；升级前未知顺序不按ID或时钟补造。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "真实占用顺序文本；历史未知可为空", accessMode = Schema.AccessMode.READ_ONLY)
    private Long occupationSequence;

    /**
     * 最近一次改变生命周期的不可变补偿操作；USE逆向不会修改此引用。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "最近生命周期逆向操作ID文本", accessMode = Schema.AccessMode.READ_ONLY)
    private Long lastInverseOperationId;

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
