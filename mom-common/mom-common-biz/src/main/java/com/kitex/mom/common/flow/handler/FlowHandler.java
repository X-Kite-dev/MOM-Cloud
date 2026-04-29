package com.kitex.mom.common.flow.handler;

import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;

/**
 * 入库单处理器接口
 *
 * @author kitex
 * @date 2024/12/16 14:17
 */
public interface FlowHandler {

    /**
     * 判断当前处理器是否支持指定的流程类型
     *
     * @param flowType 流程类型编码
     * @return true-支持，false-不支持
     */
    Boolean support(String flowType);

    /**
     * 启动流程
     *
     * @param startFlowData 启动流程的数据
     * @return 流程实例id
     */
    String startFlow(StartFlowDataDTO startFlowData);

    /**
     * 流程结束保存数据
     *
     * @param startFlowData
     */
    Boolean backProcessCompleteSaveData(FlowInstanceBizRecordEntity startFlowData);

}
