package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 工作日历
 *
 * @author kitex
 * @date 2025-02-17 11:58:28
 */
@Data
@TableName("mdm_calendar")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工作日历")
public class MdmCalendarEntity extends BaseEntity<MdmCalendarEntity> {
    /**
     * 时间
     */
    @Schema(description = "时间")
    private LocalDate time;

    /**
     * 状态 0 上班 1 休息
     */
    @Schema(description = "状态 0 上班 1 休息")
    private String status;
}