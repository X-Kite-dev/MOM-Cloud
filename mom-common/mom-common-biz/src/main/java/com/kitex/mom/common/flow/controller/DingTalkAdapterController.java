package com.kitex.mom.common.flow.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceResponseBody;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.flow.config.DingTalkPropsConfig;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.common.flow.enums.DingTalkInstanceEventTypeEnum;
import com.kitex.mom.common.flow.enums.FlowTypeEnum;
import com.kitex.mom.common.flow.handler.FlowFactory;
import com.kitex.mom.common.flow.handler.FlowHandler;
import com.kitex.mom.common.flow.utils.DingTalkUtil;
import com.kitex.mom.common.flow.vo.dingTalk.DingCallbackCrypto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 钉钉审批
 *
 * @author kitex
 * @since 2025-2-11 11:08:14
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/dingTalk")
@Tag(description = "dingTalk", name = "钉钉审批")
//@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class DingTalkAdapterController {

    private final DingTalkUtil dingTalkUtil;
    private final DingTalkPropsConfig dingTalkPropsConfig;

//	private String AES_TOKEN = "REDACTED_TOKEN";
//	private String AES_KEY = "ezhtU0ahN1bKK87kzvBOeYnKeNdkMAIK1XWLF7UyBsV";
//	private String OWNER_KEY = "dingjglhp2v7174nhkk1";

    /**
     * 接收流程审核信息
     */
    @Inner(value = false)
    @Operation(summary = "接收流程审核信息", description = "接收流程审核信息")
    @PostMapping("/backEvent")
    public Map<String, String> callBack(
            @RequestParam(value = "msg_signature", required = false) String msg_signature,
            @RequestParam(value = "timestamp", required = false) String timeStamp,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestBody(required = false) JSONObject json) {
        try {
            // 1. 从http请求中获取加解密参数

            // 2. 使用加解密类型
            // Constant.OWNER_KEY 说明：
            // 1、开发者后台配置的订阅事件为应用级事件推送，此时OWNER_KEY为应用的APP_KEY。
            // 2、调用订阅事件接口订阅的事件为企业级事件推送，
            //      此时OWNER_KEY为：企业的appkey（企业内部应用）或SUITE_KEY（三方应用）
            DingCallbackCrypto callbackCrypto = new DingCallbackCrypto(dingTalkPropsConfig.getAesToken(), dingTalkPropsConfig.getAesKey(), dingTalkPropsConfig.getOwnerKey());
            String encryptMsg = json.getString("encrypt");
            String decryptMsg = callbackCrypto.getDecryptMsg(msg_signature, timeStamp, nonce, encryptMsg);

            // 3. 反序列化回调事件json数据
            JSONObject eventJson = JSON.parseObject(decryptMsg);
            String eventType = eventJson.getString("EventType");

            // 4. 根据EventType分类处理
            if ("check_url".equals(eventType)) {
                // 测试回调url的正确性
                log.info("测试回调url的正确性");
            } else if ("user_add_org".equals(eventType)) {
                // 处理通讯录用户增加事件
                log.info("发生了：" + eventType + "事件");
            } else if ("bpms_instance_change".equals(eventType)) {
                // 审批实例变化事件 start开始 finish结束 terminate终止
                log.info("发生了：" + eventType + "事件");
                String type = eventJson.getString("type");
                String processInstanceId = eventJson.getString("processInstanceId");
                String result = eventJson.getString("result");
                log.info("审批实例" + DingTalkInstanceEventTypeEnum.getByCode(type).getName() + "：" + processInstanceId);
                if ("finish".equals(type) || "terminate".equals(type)) {
                    // 获取表单数据
                    String accessToken = dingTalkUtil.getAccessToken();
                    GetProcessInstanceResponseBody.GetProcessInstanceResponseBodyResult dingInstanceInfo = dingTalkUtil.getDingInstanceInfo(processInstanceId, accessToken);
                    List<GetProcessInstanceResponseBody.GetProcessInstanceResponseBodyResultFormComponentValues> formComponentValues = dingInstanceInfo.getFormComponentValues();
                    Map<String, Object> formData = formComponentValues.stream().filter(it -> it.getValue() != null).collect(Collectors.toMap(it -> it.getName(), it -> it.getValue()));

                    List<GetProcessInstanceResponseBody.GetProcessInstanceResponseBodyResultOperationRecords> operationRecords = dingInstanceInfo.getOperationRecords();

                    // 过滤掉result为NONE的记录，并按时间倒序排序
                    List<GetProcessInstanceResponseBody.GetProcessInstanceResponseBodyResultOperationRecords> filteredRecords = operationRecords.stream()
                            .filter(record -> !"NONE".equals(record.getResult()))
                            .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
                            .collect(Collectors.toList());

                    // 如果过滤后的列表不为空，只取最后一条记录
                    if (!filteredRecords.isEmpty()) {
                        GetProcessInstanceResponseBody.GetProcessInstanceResponseBodyResultOperationRecords lastRecord = filteredRecords.get(0);
                        formData.put("审批意见", lastRecord.getRemark());
                    }
                    FlowInstanceBizRecordEntity record = new FlowInstanceBizRecordEntity();
                    record.setInstanceId(processInstanceId)
                            .setResult(result)
                            .setEndType(type)
                            .setFormData(formData)
                            .setFlowType(FlowTypeEnum.DING_TALK.getCode());

                    // 决定handler
                    FlowHandler handler = FlowFactory.getHandler(FlowTypeEnum.DING_TALK.getCode());
                    Boolean res = handler.backProcessCompleteSaveData(record);
                }
            } else {
                // 添加其他已注册的
                log.info("发生了：" + eventType + "事件");
            }

            // 5. 返回success的加密数据
            Map<String, String> successMap = callbackCrypto.getEncryptedMap("success");
            return successMap;

        } catch (DingCallbackCrypto.DingTalkEncryptException e) {
            e.printStackTrace();
        }
        return null;
    }
}