package com.kitex.mom.common.message.handler;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiGettokenRequest;
import com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.response.OapiGettokenResponse;
import com.dingtalk.api.response.OapiMessageCorpconversationAsyncsendV2Response;
import com.dingtalk.api.response.OapiRobotSendResponse;
import com.kitex.common.core.constant.SecurityConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageBusinessConfigGroupAddDTO;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigFieldEntity;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 钉钉消息推送handler
 *
 * @author kitex
 * @date 2025-01-14 10:27:35
 */
@Slf4j
@Component("DINGDING")
@AllArgsConstructor
public class DingTalkMessageHandler implements MessageHandler {

    @SneakyThrows
    @Override
    public Boolean push(MessageDTO messageDTO) {
        MessageBusinessConfigEntity messageBusinessConfig = messageDTO.getMessageBusinessConfig();
        MessageConfigEntity messageConfig = messageDTO.getMessageConfig();
        List<MessageConfigFieldEntity> messageConfigFieldList = messageConfig.getMessageConfigFieldList();
        if (messageConfigFieldList == null || messageConfigFieldList.isEmpty()) {
            throw new CheckedException("消息推送配置数据查询为空");
        }
        Map<String, String> fieldMap = messageConfigFieldList.stream().collect(Collectors.toMap(MessageConfigFieldEntity::getCode, MessageConfigFieldEntity::getParam));
        if (fieldMap.isEmpty()) {
            throw new CheckedException("通知url为空");
        }

        // 发送工作通知
        Boolean dingTalkStatus = Boolean.TRUE;
        if (messageBusinessConfig.getPersonalStatus()) {
            dingTalkStatus = this.dingTalk(messageDTO, messageConfig, fieldMap);
        }

        Boolean robotStatus = Boolean.TRUE;
        // 发送群消息
        if (messageBusinessConfig.getGroupStatus()) {
            robotStatus = this.robot(messageDTO, messageBusinessConfig);
        }

        if (dingTalkStatus && robotStatus) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * 工作通知
     */
    @SneakyThrows
    private Boolean dingTalk(MessageDTO messageDTO, MessageConfigEntity messageConfig, Map<String, String> fieldMap) {
        DingTalkClient client = new DefaultDingTalkClient(fieldMap.get("asyncsend_v2"));

        OapiMessageCorpconversationAsyncsendV2Request request = new OapiMessageCorpconversationAsyncsendV2Request();
        request.setAgentId(Long.parseLong(fieldMap.get("agent_id")));
        request.setUseridList(messageDTO.getUserIds());
        request.setToAllUser(false);

        // 发送普通消息
        OapiMessageCorpconversationAsyncsendV2Request.Msg msg = new OapiMessageCorpconversationAsyncsendV2Request.Msg();
        msg.setMsgtype(messageDTO.getMessageType());
        if (MessageTypeEnum.TEXT.getType().equals(messageDTO.getMessageType())) {
            msg.setText(new OapiMessageCorpconversationAsyncsendV2Request.Text());
            msg.getText().setContent(messageDTO.getMessage());
        } else if (MessageTypeEnum.LINK.getType().equals(messageDTO.getMessageType())) {
            msg.setLink(new OapiMessageCorpconversationAsyncsendV2Request.Link());
            JSONObject jsonObject = JSONUtil.parseObj(messageDTO.getMessage());
            msg.getLink().setTitle(jsonObject.getStr("title"));
            msg.getLink().setText(jsonObject.getStr("text"));
            msg.getLink().setMessageUrl(jsonObject.getStr("messageUrl"));
            msg.getLink().setPicUrl(jsonObject.getStr("messageUrl"));
        }
        request.setMsg(msg);
        OapiMessageCorpconversationAsyncsendV2Response rsp = client.execute(request, getDingAccessToken(messageConfig));
        JSONObject jsonObject = JSONUtil.parseObj(rsp);
        if (jsonObject.get("errcode", Integer.class) == 0) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * 钉钉群机器人
     */
    @SneakyThrows
    private Boolean robot(MessageDTO messageDTO, MessageBusinessConfigEntity messageBusinessConfig) {
        List<MessageBusinessConfigGroupAddDTO> list = JSONUtil.toList(messageBusinessConfig.getGroupMessage(), MessageBusinessConfigGroupAddDTO.class);
        Boolean status = Boolean.FALSE;
        for (MessageBusinessConfigGroupAddDTO group : list) {
            Long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + group.getGroupSecret();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(group.getGroupSecret().getBytes("UTF-8"), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes("UTF-8"));
            String sign = URLEncoder.encode(new String(Base64.encodeBase64(signData)), "UTF-8");

            DingTalkClient client = new DefaultDingTalkClient(group.getGroupHook() + "&timestamp=" + timestamp + "&sign=" + sign);
            OapiRobotSendRequest req = new OapiRobotSendRequest();
            req.setMsgtype(messageDTO.getMessageType());
            if (MessageTypeEnum.TEXT.getType().equals(messageDTO.getMessageType())) {
                //定义文本内容
                OapiRobotSendRequest.Text text = new OapiRobotSendRequest.Text();
                text.setContent(messageDTO.getMessage());
                req.setText(text);
            } else if (MessageTypeEnum.LINK.getType().equals(messageDTO.getMessageType())) {
                OapiRobotSendRequest.Link link = new OapiRobotSendRequest.Link();
                JSONObject jsonObject = JSONUtil.parseObj(messageDTO.getMessage());
                link.setMessageUrl(jsonObject.getStr("messageUrl"));
                link.setPicUrl(jsonObject.getStr("messageUrl"));
                link.setTitle(jsonObject.getStr("title"));
                link.setText(jsonObject.getStr("text"));
                req.setLink(link);
            }

            OapiRobotSendResponse rsp = client.execute(req);
            JSONObject jsonObject = JSONUtil.parseObj(rsp);
            if (jsonObject.get("errcode", Integer.class) == 0) {
                status = Boolean.TRUE;
            }
        }
        if (status) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * 钉钉access_token
     */
    private String getDingAccessToken(MessageConfigEntity messageConfig) {
        DingTalkClient client = new DefaultDingTalkClient(SecurityConstants.DING_OLD_GET_TOKEN);
        OapiGettokenRequest request = new OapiGettokenRequest();
        request.setAppkey(messageConfig.getAppId());
        request.setAppsecret(messageConfig.getAppSecret());
        request.setHttpMethod(HttpMethod.GET.name());
        try {
            OapiGettokenResponse response = client.execute(request);
            return response.getAccessToken();
        } catch (Exception e) {
            log.error("调用钉钉异常", e);
        }
        return null;
    }
}
