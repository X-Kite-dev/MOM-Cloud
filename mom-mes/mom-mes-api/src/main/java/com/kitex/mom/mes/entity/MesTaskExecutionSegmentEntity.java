package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 派工任务每次连续生产区间；暂停与复工必须切段，供实际工时校准追溯。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_task_execution_segment")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "派工任务连续生产时间段")
public class MesTaskExecutionSegmentEntity extends BaseEntity<MesTaskExecutionSegmentEntity> {
    private Long arrangeId;
    private String arrangeCode;
    private Integer segmentNo;
    @Schema(description = "RUN；预留SETUP/DISASSEMBLY扩展")
    private String segmentType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationSeconds;
    @Schema(description = "OPEN/CLOSED/CANCELLED")
    private String status;
    @Schema(description = "PAUSE/FINISH/CLOSE/LEGACY_GAP")
    private String closeReason;
    private Boolean sampleEligible;
    private String exclusionReason;
    private String taskCode;
    private String produceTaskCode;
    private String processCode;
    private String materialCode;
    private String stationCode;
    private String deviceCode;
    private String workerCodes;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
