package com.kitex.mom.common.flow.utils;

import cn.hutool.core.collection.CollUtil;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenRequest;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponse;
import com.aliyun.dingtalkworkflow_1_0.Client;
import com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceResponse;
import com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiV2UserGetRequest;
import com.dingtalk.api.response.OapiV2UserGetResponse;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.taobao.api.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * DingTalkUtil
 *
 * @author kitex
 * @date 2025/2/13 16:27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkUtil {
    private final RemoteUserService remoteUserService;
    private String appKey = "xxxx";
    private String appSecret = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    public String getAccessToken() {
        com.aliyun.dingtalkoauth2_1_0.Client client = createClient_2_1_0();
        GetAccessTokenRequest getAccessTokenRequest = new GetAccessTokenRequest()
                .setAppKey(appKey)
                .setAppSecret(appSecret);
        try {
            GetAccessTokenResponse tokenResponse = client.getAccessToken(getAccessTokenRequest);
            return tokenResponse.getBody().getAccessToken();
        } catch (TeaException err) {
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
                log.error(err.message);
                throw new CheckedException("获取钉钉token失败");
            }

        } catch (Exception _err) {
            TeaException err = new TeaException(_err.getMessage(), _err);
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
                log.error(err.message);
                throw new CheckedException("获取钉钉token失败");
            }

        }
        return null;
    }

    public com.aliyun.dingtalkoauth2_1_0.Client createClient_2_1_0() {
        Config config = new Config();
        config.protocol = "https";
        config.regionId = "central";
        try {
            return new com.aliyun.dingtalkoauth2_1_0.Client(config);
        } catch (Exception e) {
            throw new CheckedException("初始化账号Client2_1_0失败", e);
        }
    }

    /**
     * 使用 Token 初始化账号Client
     *
     * @return Client
     */
    public Client createClient() {
        Config config = new Config();
        config.protocol = "https";
        config.regionId = "central";
        try {
            return new Client(config);
        } catch (Exception e) {
            throw new CheckedException("初始化账号Client失败", e);
        }
    }

    /**
     * 获取用户钉钉id
     */
    public String getDingUserid() {
        MomUser user = SecurityUtils.getUser();
        R<List<SysUserEntity>> usersR = remoteUserService.getUserById(Collections.singletonList(user.getId()));
        if (!usersR.isOk()) {
            throw new CheckedException("获取用户失败！");
        }
        List<SysUserEntity> userList = usersR.getData();
        if (CollUtil.isNotEmpty(userList)) {
            return userList.get(0).getWxDingUserid();
        } else {
            throw new CheckedException("获取用户失败！列表为空！");
        }
    }

    /**
     * 获取钉钉用户信息
     *
     * @param userId      钉钉用户id
     * @param accessToken token
     */
    public OapiV2UserGetResponse.UserGetResponse getDingUserInfo(String userId, String accessToken) {
        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/v2/user/get");
        OapiV2UserGetRequest req = new OapiV2UserGetRequest();
        req.setUserid(userId);
        try {
            OapiV2UserGetResponse rsp = client.execute(req, accessToken);
            return rsp.getResult();
        } catch (ApiException e) {
            throw new CheckedException("获取钉钉用户信息失败！", e);
        }
    }

    /**
     * 获取钉钉单个实例信息
     *
     * @param instanceId  实例id
     * @param accessToken token
     */
    public GetProcessInstanceResponseBody.GetProcessInstanceResponseBodyResult getDingInstanceInfo(String instanceId, String accessToken) {

        com.aliyun.dingtalkworkflow_1_0.Client client = createClient();
        com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceHeaders getProcessInstanceHeaders = new com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceHeaders();
        getProcessInstanceHeaders.xAcsDingtalkAccessToken = accessToken;
        com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceRequest getProcessInstanceRequest = new com.aliyun.dingtalkworkflow_1_0.models.GetProcessInstanceRequest()
                .setProcessInstanceId(instanceId);
        try {
            GetProcessInstanceResponse instanceResponse = client.getProcessInstanceWithOptions(getProcessInstanceRequest, getProcessInstanceHeaders, new RuntimeOptions());
            return instanceResponse.getBody().getResult();
        } catch (TeaException err) {
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
                throw new CheckedException("获取钉钉单个实例信息失败！", err);
            }
        } catch (Exception _err) {
            TeaException err = new TeaException(_err.getMessage(), _err);
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
                throw new CheckedException("获取钉钉单个实例信息失败！", err);
            }
        }
        return null;
    }
}
