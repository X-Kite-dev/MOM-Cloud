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

import java.time.LocalDateTime;

/**
 * 派工状态永久事件；只由真实状态变化捕获，应用不提供通用编辑或删除入口。
 */
@Data
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("mes_arrange_timing_event")
@Schema(description = "派工暂停计时原事件")
public class MesArrangeTimingEventEntity extends BaseEntity<MesArrangeTimingEventEntity> {
    /**
     * 实际工厂，不从当前其他派工推定
     */
    @Schema(description = "实际工厂，不从当前其他派工推定")
    private String factoryCode;

    /**
     * 实际目标派工主键
     */
    @Schema(description = "实际目标派工主键")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;

    /**
     * 原派工号
     */
    @Schema(description = "原派工号")
    private String arrangeCode;

    /**
     * 原生产工单
     */
    @Schema(description = "原生产工单")
    private String taskCode;

    /**
     * 原工序任务
     */
    @Schema(description = "原工序任务")
    private String produceTaskCode;

    /**
     * 该派工连续状态事件版本
     */
    @Schema(description = "该派工连续状态事件版本")
    private Integer eventVersion;

    /**
     * CREATED初建、BASELINE历史观察或STATE_CHANGE真实迁移
     */
    @Schema(description = "CREATED初建、BASELINE历史观察或STATE_CHANGE真实迁移")
    private String eventKind;

    /**
     * 上次观察的业务状态，首条为空
     */
    @Schema(description = "上次观察的业务状态，首条为空")
    private String previousStatus;

    /**
     * 此次实际派工业务状态
     */
    @Schema(description = "此次实际派工业务状态")
    private String status;

    /**
     * 数据库观察到状态改变的微秒时刻
     */
    @Schema(description = "数据库观察到状态改变的微秒时刻")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime eventTime;

    /**
     * 此次暂停或复工是否有同事务真实RUN开闭依据
     */
    @Schema(description = "此次暂停或复工是否有同事务真实RUN开闭依据")
    private Boolean proven;

    /**
     * 真实闭合或开启的RUN段，不是推测的间隙
     */
    @Schema(description = "真实闭合或开启的RUN段，不是推测的间隙")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long segmentId;

    /**
     * 来源段理由或明确未证明原因
     */
    @Schema(description = "来源段理由或明确未证明原因")
    private String reason;

    /**
     * 当次真实派工必要身份及状态快照
     */
    @Schema(description = "当次真实派工必要身份及状态快照")
    private String stateJson;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
