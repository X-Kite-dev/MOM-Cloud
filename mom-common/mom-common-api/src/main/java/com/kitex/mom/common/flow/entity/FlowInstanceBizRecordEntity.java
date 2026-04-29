package com.kitex.mom.common.flow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 流程实例与业务关联记录
 *
 * @author kitex
 * @date 2025-02-12 11:24:23
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("flow_instance_biz_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "流程实例与业务关联记录")
public class FlowInstanceBizRecordEntity extends BaseEntity<FlowInstanceBizRecordEntity> {

    /**
     * 业务单据号
     */
    @Schema(description = "业务单据号")
    private String billId;

    /**
     * 审批流实例id
     */
    @Schema(description = "审批流实例id")
    private String instanceId;

    /**
     * 业务模块服务名
     */
    @Schema(description = "业务模块服务名")
    private String bizService;

    /**
     * 流程结束回调地址
     */
    @Schema(description = "流程结束回调地址")
    private String backUrl;

    /**
     * 审批流类型
     */
    @Schema(description = "审批流类型")
    private String flowType;

    /**
     * 结束类型
     */
    @Schema(description = "结束类型")
    private String endType;

    /**
     * 审批结果
     */
    @Schema(description = "审批结果")
    private String result;

    /**
     * 表单数据
     */
    @TableField(exist = false)
    @Schema(description = "表单数据")
    private Map<String, Object> formData;
}