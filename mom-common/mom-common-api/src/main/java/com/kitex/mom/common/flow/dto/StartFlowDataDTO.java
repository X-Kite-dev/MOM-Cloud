package com.kitex.mom.common.flow.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 启动流程参数
 *
 * @author kitex
 * @date 2025/2/12 13:52
 */
@Data
@Accessors(chain = true)
public class StartFlowDataDTO {
    /**
     * 流程名称
     */
    private String flowName;
    /**
     * 业务模块服务名
     */
    private String bizService;
    /**
     * 流程结束回调地址（接口相对路径）
     */
    private String backUrl;
    /**
     * 业务表单信息
     */
    private Map<String, Object> formData;
}
