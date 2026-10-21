package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 独立超时例外主账；原观察不可改，仅受控动作流转，不提供通用CRUD入口。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_timing_exception")
@Schema(description = "独立超时例外主账；原观察不可改，仅受控动作流转，不提供通用CRUD入口。")
public class MesTimingExceptionEntity extends BaseEntity<MesTimingExceptionEntity> {
    /**
     * 实际授权工厂
     */
    @Schema(description = "实际授权工厂")
    private String factoryCode;

    /**
     * GOOD合格或NG不良，来源域不可混用
     */
    @Schema(description = "GOOD合格或NG不良，来源域不可混用")
    private String reportKind;

    /**
     * 原报工请求号，许可只能由此号消费
     */
    @Schema(description = "原报工请求号，许可只能由此号消费")
    private String reportRequestId;

    /**
     * 原报工实名用户ID
     */
    @Schema(description = "原报工实名用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorId;

    /**
     * 原报工实名账号
     */
    @Schema(description = "原报工实名账号")
    private String actorCode;

    /**
     * 原生产工单
     */
    @Schema(description = "原生产工单")
    private String taskCode;

    /**
     * 原派工单
     */
    @Schema(description = "原派工单")
    private String arrangeCode;

    /**
     * 原工序任务
     */
    @Schema(description = "原工序任务")
    private String produceTaskCode;

    /**
     * 冻结对象模式
     */
    @Schema(description = "冻结对象模式")
    private String reportMode;

    /**
     * 规范生产标识，数量模式为空
     */
    @Schema(description = "规范生产标识，数量模式为空")
    private String identifier;

    /**
     * 原报工数量，许可不可借给其他数量
     */
    @Schema(description = "原报工数量，许可不可借给其他数量")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;

    /**
     * 原报工身份及输入指纹的不可变正文
     */
    @Schema(description = "原报工身份及输入指纹的不可变正文")
    private String contextJson;

    /**
     * 原规则和来源事件的不可变依赖正文
     */
    @Schema(description = "原规则和来源事件的不可变依赖正文")
    private String dependencyJson;

    /**
     * 首次超时观察证据，不代表生产已成功
     */
    @Schema(description = "首次超时观察证据，不代表生产已成功")
    private String evidenceJson;

    /**
     * 服务器首次超时观察时刻
     */
    @Schema(description = "服务器首次超时观察时刻")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime observedAt;

    /**
     * 冻结工程允许的最晚例外截止，缺省不允许例外
     */
    @Schema(description = "冻结工程允许的最晚例外截止，缺省不允许例外")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime limitUntil;

    /**
     * DETECTED/SUBMITTED/APPROVED/REJECTED/WITHDRAWN/REVOKED/CONSUMED
     */
    @Schema(description = "DETECTED/SUBMITTED/APPROVED/REJECTED/WITHDRAWN/REVOKED/CONSUMED")
    private String status;

    /**
     * 乐观版本，每个受控操作增加一次
     */
    @Schema(description = "乐观版本，每个受控操作增加一次")
    private Integer versionNo;

    /**
     * 申请明确延长至此服务器本地时间
     */
    @Schema(description = "申请明确延长至此服务器本地时间")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime proposedUntil;

    /**
     * 异人批准的本次截止
     */
    @Schema(description = "异人批准的本次截止")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime approvedUntil;

    /**
     * 申请的现场处置原因
     */
    @Schema(description = "申请的现场处置原因")
    private String requestReason;

    /**
     * 审核或撤销意见
     */
    @Schema(description = "审核或撤销意见")
    private String reviewReason;

    /**
     * 批准人实名ID
     */
    @Schema(description = "批准人实名ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long approvedBy;

    /**
     * 批准人实名账号
     */
    @Schema(description = "批准人实名账号")
    private String approvedByCode;

    /**
     * 已消费的真实报告ID，由reportKind明确GOOD或NG域
     */
    @Schema(description = "已消费的真实报告ID，由reportKind明确GOOD或NG域")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long consumedReportId;

    /**
     * 同报工事务的一次消费时刻
     */
    @Schema(description = "同报工事务的一次消费时刻")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime consumedAt;

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
