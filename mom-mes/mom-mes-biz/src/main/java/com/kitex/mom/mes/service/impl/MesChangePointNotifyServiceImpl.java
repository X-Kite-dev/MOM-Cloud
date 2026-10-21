package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.admin.api.entity.SysRoleEntity;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteRoleService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.mes.dto.ChangePointNotifyResult;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.service.MesChangePointNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MesChangePointNotifyServiceImpl implements MesChangePointNotifyService {

    private final RemoteRoleService remoteRoleService;
    private final RemoteUserService remoteUserService;
    private final RemoteMessagePushService remoteMessagePushService;

    @Override
    public ChangePointNotifyResult notifyHandlers(MesChangePointConfigEntity config,
                                                  MesChangePointHandleEntity handle) {
        String message = String.format("变化点待接收：[%s] %s；工单[%s] 工序[%s] 等级[%s]",
                handle.getEventCode(), handle.getConfigName(), StrUtil.nullToEmpty(handle.getTaskCode()),
                StrUtil.nullToEmpty(handle.getRouteCode()), StrUtil.nullToEmpty(handle.getAlertLevel()));
        return notify(config.getNotifyRoles(), config.getNotifyChannels(), message, handle.getEventCode(), "HANDLER");
    }

    @Override
    public ChangePointNotifyResult notifyVerifiers(MesChangePointConfigEntity config,
                                                   MesChangePointHandleEntity handle) {
        String message = String.format("变化点待验证：[%s] %s；处置人[%s]；预计关闭[%s]",
                handle.getEventCode(), handle.getConfigName(), StrUtil.nullToEmpty(handle.getHandlePersonName()),
                handle.getExpectedCloseTime());
        return notify(StrUtil.blankToDefault(handle.getHandleRole(), config.getVerifyRole()),
                config.getNotifyChannels(), message, handle.getEventCode(), "VERIFIER");
    }

    @Override
    public ChangePointNotifyResult notifyReleaseApprovers(MesChangePointConfigEntity config,
                                                          MesChangePointHandleEntity handle) {
        String message = String.format("变化点待恢复生产审批：[%s] %s；工单[%s]；验证人[%s]；审批期限[%s]",
                handle.getEventCode(), handle.getConfigName(), StrUtil.nullToEmpty(handle.getTaskCode()),
                StrUtil.nullToEmpty(handle.getVerifierName()), handle.getReleaseDeadline());
        return notify(handle.getProductionReleaseRole(), config.getNotifyChannels(), message,
                handle.getEventCode(), "PRODUCTION_RELEASE");
    }

    @Override
    public ChangePointNotifyResult notifyEscalation(MesChangePointConfigEntity config,
                                                    MesChangePointHandleEntity handle) {
        String roles = Arrays.stream(new String[]{config.getNotifyRoles(),
                        StrUtil.blankToDefault(handle.getHandleRole(), config.getVerifyRole()),
                        handle.getProductionReleaseRole()})
                .filter(StrUtil::isNotBlank).collect(Collectors.joining(","));
        String message = String.format("变化点SLA升级：[%s] %s；状态[%s]；接收期限[%s]；处置期限[%s]；复产审批期限[%s]；累计升级[%s]",
                handle.getEventCode(), handle.getConfigName(), handle.getStatus(), handle.getResponseDeadline(),
                handle.getHandleDeadline(), handle.getReleaseDeadline(),
                handle.getEscalationCount() == null ? 1 : handle.getEscalationCount() + 1);
        return notify(roles, config.getNotifyChannels(), message, handle.getEventCode(), "ESCALATION");
    }

    private ChangePointNotifyResult notify(String roleCodes, String channels, String message,
                                           String eventCode, String stage) {
        LinkedHashSet<String> requestedChannels = Arrays.stream(
                        StrUtil.blankToDefault(channels, "SYSTEM").split(","))
                .map(String::trim).map(String::toUpperCase).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        requestedChannels.add("SYSTEM");
        ChangePointNotifyResult result = new ChangePointNotifyResult()
                .setChannels(String.join(",", requestedChannels));
        try {
            List<Long> userIds = resolveUserIds(roleCodes);
            if (userIds.isEmpty()) {
                return result.setResult("FAILED").setDetail("配置角色未解析到用户");
            }
            R<List<SysUserEntity>> response = remoteUserService.getUserByIdInner(userIds);
            List<SysUserEntity> users = response == null || response.getData() == null
                    ? Collections.emptyList() : response.getData();
            result.setRecipientCodes(userIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .setRecipientNames(users.stream().map(SysUserEntity::getName)
                            .filter(StrUtil::isNotBlank).collect(Collectors.joining(",")));

            MessageDTO dto = new MessageDTO();
            dto.setCode("MES");
            dto.setMessageType(MessageTypeEnum.TEXT.getType());
            dto.setMessage(message);
            dto.setUserList(userIds);
            dto.setUserIds(users.stream().map(SysUserEntity::getWxDingUserid)
                    .filter(StrUtil::isNotBlank).collect(Collectors.joining(",")));
            dto.setRequestedChannels(new ArrayList<>(requestedChannels));
            R<Boolean> push = remoteMessagePushService.pushPersonMsg(dto);
            boolean success = push != null && push.getCode() == 0 && Boolean.TRUE.equals(push.getData());
            return result.setResult(success ? "SUCCESS" : "PARTIAL")
                    .setDetail(success ? "请求通道投递成功" : "至少一个请求通道未配置或投递失败");
        } catch (Exception exception) {
            log.error("变化点通知失败, eventCode={}, stage={}", eventCode, stage, exception);
            return result.setResult("FAILED").setDetail(StrUtil.subPre(
                    StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName()), 500));
        }
    }

    private List<Long> resolveUserIds(String roleCodes) {
        if (StrUtil.isBlank(roleCodes)) {
            return Collections.emptyList();
        }
        Set<String> codes = Arrays.stream(roleCodes.split(",")).map(String::trim)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        R<List<SysRoleEntity>> roleResponse = remoteRoleService.getAllRole();
        List<SysRoleEntity> roles = roleResponse == null || roleResponse.getData() == null
                ? Collections.emptyList() : roleResponse.getData();
        List<Long> roleIds = roles.stream().filter(role -> codes.contains(role.getRoleCode()))
                .map(SysRoleEntity::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        R<List<Long>> userResponse = remoteUserService.getUserIdListByRoleIdList(roleIds);
        return userResponse == null || userResponse.getData() == null ? Collections.emptyList()
                : userResponse.getData().stream().distinct().toList();
    }
}
