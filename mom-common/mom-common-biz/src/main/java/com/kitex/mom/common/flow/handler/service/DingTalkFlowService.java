package com.kitex.mom.common.flow.handler.service;

import cn.hutool.core.collection.CollUtil;
import com.aliyun.dingtalkworkflow_1_0.Client;
import com.aliyun.dingtalkworkflow_1_0.models.StartProcessInstanceHeaders;
import com.aliyun.dingtalkworkflow_1_0.models.StartProcessInstanceRequest;
import com.aliyun.dingtalkworkflow_1_0.models.StartProcessInstanceResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;
import com.dingtalk.api.response.OapiV2UserGetResponse;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.flow.config.DingTalkPropsConfig;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import com.kitex.mom.common.flow.enums.FlowTypeEnum;
import com.kitex.mom.common.flow.handler.AbstractFlowHandler;
import com.kitex.mom.common.flow.utils.DingTalkUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class DingTalkFlowService extends AbstractFlowHandler {

    private final DingTalkUtil dingTalkUtil;
    private final DingTalkPropsConfig dingTalkPropsConfig;

    private final RemoteDictService remoteDictService;


    /**
     * 支持的流程类型
     *
     * @param flowType 流程类型
     */
    @Override
    public Boolean support(String flowType) {
        if (FlowTypeEnum.DING_TALK.getCode().equals(flowType)) {
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
        Client client = dingTalkUtil.createClient();
        // 获取token
        String accessToken = dingTalkUtil.getAccessToken();
        StartProcessInstanceHeaders headers = new StartProcessInstanceHeaders();
        headers.xAcsDingtalkAccessToken = accessToken;
        // 表单数据
        Map<String, Object> formData = startFlowData.getFormData();
        List<StartProcessInstanceRequest.StartProcessInstanceRequestFormComponentValues> formComps = new ArrayList<>();
        for (String key : formData.keySet()) {
            StartProcessInstanceRequest.StartProcessInstanceRequestFormComponentValues formValues = new StartProcessInstanceRequest.StartProcessInstanceRequestFormComponentValues()
                    .setName(key)
                    .setValue(String.valueOf(formData.get(key)));
            formComps.add(formValues);
        }
        // 当前用户的钉钉id
        String wxDingUserid = dingTalkUtil.getDingUserid();
        if (wxDingUserid == null) {
            throw new CheckedException("当前用户未绑定钉钉");
        }
        // 获取部门id
        Long deptId = -1L;
        OapiV2UserGetResponse.UserGetResponse dingUserInfo = dingTalkUtil.getDingUserInfo(wxDingUserid, accessToken);
        if (dingUserInfo == null) {
            throw new CheckedException("当前用户绑定的钉钉不属于公司成员");
        }
        List<Long> deptIdList = dingUserInfo.getDeptIdList();
        if (CollUtil.isNotEmpty(deptIdList)) {
            deptId = deptIdList.get(0);
        }

        // 获取钉钉流程id
        List<SysDictItemEntity> flow_type_config = remoteDictService.getDictByType("ding_talk_process_code").getData();
        List<SysDictItemEntity> dictItems = flow_type_config.stream().filter(it -> startFlowData.getFlowName().equals(it.getLabel())).toList();
        if (dictItems.size() == 0) {
            throw new CheckedException(String.format("请配置[%s]的钉钉审批流程ID", startFlowData.getFlowName()));
        }
        String processCode = dictItems.get(0).getDescription();

        StartProcessInstanceRequest startProcessInstanceRequest = new StartProcessInstanceRequest()
                .setFormComponentValues(formComps)
                .setOriginatorUserId(wxDingUserid)
                .setProcessCode(processCode)
                .setDeptId(deptId)
                .setMicroappAgentId(dingTalkPropsConfig.getAppAgentId());
//				.setApprovers(java.util.Arrays.asList(
//						approvers0
//				))
//				.setCcList(java.util.Arrays.asList(
//						"26652461xxxx5992"
//				))
//				.setCcPosition("START")
//				.setTargetSelectActioners(java.util.Arrays.asList(
//						targetSelectActioners0
//				));
        try {
            StartProcessInstanceResponse response = client.startProcessInstanceWithOptions(startProcessInstanceRequest, headers, new RuntimeOptions());
            return response.getBody().getInstanceId();
        } catch (TeaException err) {
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
                log.error(err.message);
            }

        } catch (Exception _err) {
            TeaException err = new TeaException(_err.getMessage(), _err);
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
                log.error(err.message);
            }

        }
        return null;
    }
}
