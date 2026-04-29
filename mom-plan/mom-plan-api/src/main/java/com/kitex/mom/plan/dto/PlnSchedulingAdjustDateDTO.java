package com.kitex.mom.plan.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class PlnSchedulingAdjustDateDTO {

    private Long issueId;

    private LocalDate scheduledDate;

    /**
     * 排产结束日期（可选）。
     * 不传或为 null 时，由系统根据产品产率+工作日历自动计算；
     * 传入时以手动值为准（需 >= scheduledDate）。
     */
    private LocalDate scheduledEndDate;
}
