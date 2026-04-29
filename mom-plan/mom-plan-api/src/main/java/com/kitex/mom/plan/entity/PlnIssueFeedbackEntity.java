package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 计划下达反馈表
 *
 * @author kitex
 * @date 2025-02-08 17:13:23
 */
@Data
@TenantTable
@TableName("pln_issue_feedback")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "计划下达反馈表")
public class PlnIssueFeedbackEntity extends BaseEntity<PlnIssueFeedbackEntity> {

    /**
     * 生产计划下达号
     */
    @Schema(description = "生产计划下达号")
    private String plnIssueNo;

    /**
     * 反馈时间
     */
    @Schema(description = "反馈时间")
    private LocalDateTime feedbackTime;

    /**
     * 反馈内容
     */
    @Schema(description = "反馈内容")
    private String feedbackContent;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}