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
import com.kitex.mom.mes.dto.WipTimeoutNotifyResult;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.service.MesWipTimeoutNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MesWipTimeoutNotifyServiceImpl implements MesWipTimeoutNotifyService {

    private final RemoteRoleService remoteRoleService;
    private final RemoteUserService remoteUserService;
    private final RemoteMessagePushService remoteMessagePushService;

    @Override
    public WipTimeoutNotifyResult notifyLevel(MesWipTimeoutConfigEntity config, MesWipTimeoutHandleEntity handle, int level) {
        String roles = level == 1 ? config.getLevel1NotifyRoles() : (level == 2 ? config.getLevel2NotifyRoles() : config.getLevel3NotifyRoles());
        String channels = level == 1 ? config.getLevel1NotifyChannels() : (level == 2 ? config.getLevel2NotifyChannels() : config.getLevel3NotifyChannels());
        String message = String.format(
                "在制超时告警：配置[%s] 流转码[%s] 工单[%s] 工序[%s] 当前等级[L%s] 超时[%s秒]",
                config.getName(), handle.getTransferCode(), StrUtil.nullToEmpty(handle.getTaskCode()),
                StrUtil.nullToEmpty(handle.getRouteCode()), level, handle.getOvertimeSec());
        return notifyUsers(handle, roles, channels, message, "L" + level);
    }

    @Override
    public WipTimeoutNotifyResult notifyVerification(MesWipTimeoutConfigEntity config,
                                                     MesWipTimeoutHandleEntity handle) {
        String message = String.format(
                "在制超时处置待验证：配置[%s] 流转码[%s] 工单[%s] 工序[%s] 处置人[%s] 承诺解除时间[%s]",
                config.getName(), handle.getTransferCode(), StrUtil.nullToEmpty(handle.getTaskCode()),
                StrUtil.nullToEmpty(handle.getRouteCode()), StrUtil.nullToEmpty(handle.getHandlePersonName()),
                handle.getExpectedResolveTime());
        return notifyUsers(handle, config.getVerifyRoles(), config.getLevel1NotifyChannels(), message, "VERIFY");
    }

    private WipTimeoutNotifyResult notifyUsers(MesWipTimeoutHandleEntity handle, String roles,
                                               String channels, String message, String stage) {
        LinkedHashSet<String> requestedChannels = Arrays.stream(StrUtil.blankToDefault(channels, "SYSTEM").split(","))
                .map(String::trim).map(String::toUpperCase).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        requestedChannels.add("SYSTEM");
        WipTimeoutNotifyResult notifyResult = new WipTimeoutNotifyResult()
                .setChannels(String.join(",", requestedChannels));
        try {
            List<Long> userIds = resolveUserIdsByRoleCodes(roles);
            if (userIds.isEmpty()) {
                return notifyResult.setResult("FAILED").setDetail("当前等级通知角色未解析到用户");
            }

            R<List<SysUserEntity>> userResponse = remoteUserService.getUserByIdInner(userIds);
            List<SysUserEntity> userList = userResponse == null || userResponse.getData() == null
                    ? Collections.emptyList() : userResponse.getData();
            String userThirdIds = userList.stream().map(SysUserEntity::getWxDingUserid)
                    .filter(StrUtil::isNotBlank).collect(Collectors.joining(","));
            notifyResult.setRecipientCodes(userIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            notifyResult.setRecipientNames(userList.stream().map(SysUserEntity::getName)
                    .filter(StrUtil::isNotBlank).collect(Collectors.joining(",")));

            MessageDTO dto = new MessageDTO();
            dto.setCode("MES");
            dto.setMessageType(MessageTypeEnum.TEXT.getType());
            dto.setMessage(message);
            dto.setUserList(userIds);
            dto.setUserIds(userThirdIds);
            dto.setRequestedChannels(new ArrayList<>(requestedChannels));

            R<Boolean> response = remoteMessagePushService.pushPersonMsg(dto);
            boolean success = response != null && response.getCode() == 0 && Boolean.TRUE.equals(response.getData());
            return notifyResult.setResult(success ? "SUCCESS" : "PARTIAL")
                    .setDetail(success ? "请求通道投递成功" : "至少一个请求通道未配置或投递失败，站内信结果以消息中心日志为准");
        } catch (Exception ex) {
            log.error("WIP超时通知失败, incidentKey={}, stage={}", handle.getIncidentKey(), stage, ex);
            return notifyResult.setResult("FAILED")
                    .setDetail(StrUtil.subPre(StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 500));
        }
    }

    private List<Long> resolveUserIdsByRoleCodes(String roleCodes) {
        if (StrUtil.isBlank(roleCodes)) {
            return Collections.emptyList();
        }
        Set<String> roleCodeSet = Arrays.stream(roleCodes.split(",")).map(String::trim).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        R<List<SysRoleEntity>> roleResponse = remoteRoleService.getAllRole();
        List<SysRoleEntity> allRoles = roleResponse == null || roleResponse.getData() == null
                ? Collections.emptyList() : roleResponse.getData();
        List<Long> roleIds = allRoles.stream().filter(r -> roleCodeSet.contains(r.getRoleCode())).map(SysRoleEntity::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        R<List<Long>> userResponse = remoteUserService.getUserIdListByRoleIdList(roleIds);
        List<Long> userIds = userResponse == null || userResponse.getData() == null
                ? Collections.emptyList() : userResponse.getData();
        return userIds.stream().distinct().toList();
    }
}
