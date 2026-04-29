package com.kitex.mom.common.flow.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.resolver.ParamResolver;
import com.kitex.flow.task.dto.ProcessCompleteNotifyDTO;
import com.kitex.flow.task.entity.ProcessInstanceRecord;
import com.kitex.flow.task.vo.FormItemVO;
import com.kitex.flow.task.vo.ProcessVO;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.common.flow.enums.FlowResultEnum;
import com.kitex.mom.common.flow.enums.FlowTypeEnum;
import com.kitex.mom.common.flow.handler.FlowFactory;
import com.kitex.mom.common.flow.handler.FlowHandler;
import com.kitex.mom.common.flow.service.FlowInstanceBizRecordService;
import com.kitex.mom.common.flow.service.ProcessAdapterService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 流程管理
 *
 * @author kitex
 * @date 2025-2-8 15:33:08
 */
@Service
@AllArgsConstructor
public class ProcessAdapterServiceImpl implements ProcessAdapterService {

    private final FlowInstanceBizRecordService flowInstanceBizRecordService;

    /**
     * 启动任务流程
     * 根据配置决定使用内置还是钉钉审批
     */
    @Override
    @Transactional
    public Boolean startFlow(StartFlowDataDTO startFlowDataDTO) {
        // 检查数据
        Optional.ofNullable(startFlowDataDTO.getFlowName()).orElseThrow(() -> new CheckedException("流程名称为空"));
        Optional.ofNullable(startFlowDataDTO.getBizService()).orElseThrow(() -> new CheckedException("业务模块服务名为空"));
        Optional.ofNullable(startFlowDataDTO.getBackUrl()).orElseThrow(() -> new CheckedException("流程结束回调地址为空"));
        Map<String, Object> formData = Optional.ofNullable(startFlowDataDTO.getFormData()).orElseThrow(() -> new CheckedException("表单数据为空"));
        String billId = String.valueOf(Optional.ofNullable(formData.get("billId")).orElseThrow(() -> new CheckedException("业务单据ID为空")));
        // 获取流程类型参数
        String flowType = ParamResolver.getStr("FLOW_TYPE", FlowTypeEnum.FLOWABLE.getCode());

        // 保存到数据库
        FlowInstanceBizRecordEntity record = new FlowInstanceBizRecordEntity()
                .setFlowType(flowType)
                .setResult(FlowResultEnum.CHECKING.getCode())
                .setBizService(startFlowDataDTO.getBizService())
                .setBackUrl(startFlowDataDTO.getBackUrl())
                .setBillId(billId);
        // 决定handler
        FlowHandler handler = FlowFactory.getHandler(flowType);
        String instanceId = handler.startFlow(startFlowDataDTO);
        // 保存流程实例id
        record.setInstanceId(instanceId);
        flowInstanceBizRecordService.save(record);
        return true;
    }

    @Override
    public Boolean backProcessComplete(ProcessCompleteNotifyDTO params) {
        // 审核结果，是否通过
        boolean approveRes = false;
        String approveData = params.getApproveData();
        // 审核信息
        Map<String, Object> approveMap = JSONUtil.toBean(approveData, Map.class);
        // 找到记录审核结果的字段
        List<String> conditionKeyList = approveMap.keySet().stream().filter(Objects::nonNull).filter(key -> key.endsWith("_approve_condition")).toList();
        if (conditionKeyList.size() > 0) {
            String key = conditionKeyList.get(0);
            approveRes = (boolean) Optional.ofNullable(approveMap.get(key)).orElse(false);
        }
        /* 根据流程名称从审核信息中找到单据号，获取此流程对应哪个类型的单据，拿着单据号和审核结果去对应模块修改单据的信息 */
        // 获取流程实例
        ProcessInstanceRecord instanceRecord = Optional.ofNullable(params.getInstanceRecord()).orElseThrow(() -> new CheckedException("返回流程实例为空"));
        // 获取流程信息
        ProcessVO processVO = Optional.ofNullable(params.getProcessVO()).orElseThrow(() -> new CheckedException("返回流程信息为空"));
        // 流程表单信息
        String formItems = Optional.ofNullable(processVO.getFormItems()).orElse("[]");
        List<FormItemVO> formItemVOS = JSONUtil.toList(formItems, FormItemVO.class); // 流程的字段信息
        // 转换表单key键
        Map<String, Object> paramMap = new HashMap<>();
        for (FormItemVO itemVO : formItemVOS) {
            paramMap.put(itemVO.getName(), approveMap.get(itemVO.getId()));
        }
        // 审批原因
        paramMap.put("审批意见", approveMap.get("approveDesc"));
        FlowInstanceBizRecordEntity record = new FlowInstanceBizRecordEntity();
        record.setInstanceId(instanceRecord.getProcessInstanceId())
                .setResult(approveRes ? "agree" : "refuse")
                .setEndType("finish")
                .setFormData(paramMap)
                .setFlowType(FlowTypeEnum.FLOWABLE.getCode());

        // 决定handler
        FlowHandler handler = FlowFactory.getHandler(FlowTypeEnum.FLOWABLE.getCode());
        Boolean res = handler.backProcessCompleteSaveData(record);
        return true;
    }


}