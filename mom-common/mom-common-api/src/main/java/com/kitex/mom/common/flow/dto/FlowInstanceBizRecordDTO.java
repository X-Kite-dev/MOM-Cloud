package com.kitex.mom.common.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程实例与业务关联记录DTO
 *
 * @author kitex
 * @date 2025-03-31 08:41:23
 */
@Data
@Schema(description = "流程实例与业务关联记录DTO")
public class FlowInstanceBizRecordDTO {

    /**
     * 业务单据号
     */
    @Schema(description = "业务单据号")
    private String billId;

    /**
     * 业务模块服务名
     */
    @Schema(description = "业务模块服务名")
    private String bizService;

    /**
     * 审批流类型
     */
    @Schema(description = "审批流类型")
    private String flowType;

    /**
     * 审批结果
     */
    @Schema(description = "审批结果")
    private String result;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 创建者id
     */
    @Schema(description = "创建人")
    private String createBy;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime[] createTime;
}