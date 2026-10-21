package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 智能 APS 正式排程下达 MES 的事务 Outbox。
 *
 * <p>发布事务只写入本表，异步工作器按永久请求号和载荷指纹重试；已经成功的逐项回执不会被回滚或重复建单。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_aps_mes_dispatch_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "智能 APS 正式排程下达 MES 的事务 Outbox")
public class PlnApsMesDispatchOutboxEntity extends BaseEntity<PlnApsMesDispatchOutboxEntity> {

    @Schema(description = "所属 APS 案件主键")
    private Long caseId;

    @Schema(description = "已发布的 APS 多候选情景主键")
    private Long scenarioId;

    @Schema(description = "已发布的 APS 候选任务主键")
    private Long selectedTaskId;

    @Schema(description = "正式发布结果指纹")
    private String publicationFingerprint;

    @Schema(description = "MES 下达永久幂等请求号")
    private String requestId;

    @Schema(description = "MES 下达请求内容指纹")
    private String requestFingerprint;

    @JsonIgnore
    @Schema(description = "发送给 MES 的不可变载荷（JSON），默认不直接输出")
    private String payloadJson;

    @Schema(description = "下达状态")
    private String status;

    @Schema(description = "已经执行的发送次数")
    private Integer attemptCount;

    @Schema(description = "网络或服务异常后的下次重试时间")
    private LocalDateTime nextRetryTime;

    @Schema(description = "工作器租约令牌")
    private String leaseToken;

    @Schema(description = "工作器租约到期时间")
    private LocalDateTime leaseUntil;

    @Schema(description = "最近一次失败原因")
    private String lastError;

    @JsonIgnore
    @Schema(description = "MES 最近一次逐项回执快照（JSON），默认不直接输出")
    private String responseSnapshot;

    @Schema(description = "MES 最近一次逐项回执指纹")
    private String responseFingerprint;

    @Schema(description = "已成功下达的计划或工序项数")
    private Integer dispatchedCount;

    @Schema(description = "仍失败的计划或工序项数")
    private Integer failedCount;

    @Schema(description = "Outbox 入账时间")
    private LocalDateTime queuedTime;

    @Schema(description = "最近一次发送时间")
    private LocalDateTime sentTime;

    @Schema(description = "全部下达完成时间")
    private LocalDateTime completedTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "工厂编码")
    private String factoryCode;
}
