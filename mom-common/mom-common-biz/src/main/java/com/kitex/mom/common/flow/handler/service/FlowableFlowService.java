package com.kitex.mom.common.flow.handler.service;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.flow.task.api.feign.RemoteProcessInstanceService;
import com.kitex.flow.task.api.feign.RemoteProcessService;
import com.kitex.flow.task.dto.ProcessInstanceParamDto;
import com.kitex.flow.task.vo.FormItemVO;
import com.kitex.flow.task.vo.ProcessVO;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import com.kitex.mom.common.flow.enums.FlowTypeEnum;
import com.kitex.mom.common.flow.handler.AbstractFlowHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FlowableFlowService extends AbstractFlowHandler {


    private final RemoteProcessService remoteProcessService;

    private final RemoteProcessInstanceService remoteProcessInstanceService;

    /**
     * 支持的流程类型
     *
     * @param flowType 流程类型
     */
    @Override
    public Boolean support(String flowType) {
        if (FlowTypeEnum.FLOWABLE.getCode().equals(flowType)) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * 启动流程
     *
     * @param startFlowData 启动流程的数据
     * @return 流程实例id
     */
    @Override
    public String startFlow(StartFlowDataDTO startFlowData) {
        Map<String, Object> formData = startFlowData.getFormData();
        String flowName = startFlowData.getFlowName();
        // 根据流程名称获取流程
        R<ProcessVO> detailR = remoteProcessService.getDetailByName(flowName);
        if (!detailR.isOk()) {
            throw new CheckedException("获取任务流程失败->" + detailR.getMsg());
        }
        ProcessVO processVO = detailR.getData();
        String flowId = processVO.getFlowId();
        String formItems = processVO.getFormItems();
        List<FormItemVO> formItemVOS = JSONUtil.toList(formItems, FormItemVO.class); // 流程的字段信息
        Map<String, Object> paramMap = new HashMap<>(); // 启动流程的参数集合
        // 由  中文字段名：字段值  转为  流程的字段名：字段值
        for (FormItemVO itemVO : formItemVOS) {
            paramMap.put(itemVO.getId(), formData.get(itemVO.getName()));
        }
        ProcessInstanceParamDto paramDto = new ProcessInstanceParamDto();
        paramDto.setParamMap(paramMap);
        paramDto.setFlowId(flowId);
        R<String> instanceR = remoteProcessInstanceService.startProcessInstance(paramDto);
        if (!instanceR.isOk()) {
            throw new CheckedException("启动任务流程失败->" + instanceR.getMsg());
        }
        return instanceR.getData();
    }
}
