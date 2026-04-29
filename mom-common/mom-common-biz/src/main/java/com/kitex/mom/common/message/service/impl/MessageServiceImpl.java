package com.kitex.mom.common.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.feign.RemoteMessageService;
import com.kitex.admin.api.vo.OrgTreeVO;
import com.kitex.admin.api.vo.SysMessageVO;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
import com.kitex.mom.common.message.entity.MessageLogEntity;
import com.kitex.mom.common.message.handler.business.MessageBusinessHandler;
import com.kitex.mom.common.message.service.MessageBusinessConfigService;
import com.kitex.mom.common.message.service.MessageConfigService;
import com.kitex.mom.common.message.service.MessageLogService;
import com.kitex.mom.common.message.service.MessageService;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class MessageServiceImpl implements MessageService {


    private final MessageLogService messageLogService;

    private final Map<String, MessageBusinessHandler> messageBusinessHandlerMap;

    private final MessageBusinessConfigService messageBusinessConfigService;

    private final MessageConfigService messageConfigService;

    private final RemoteMessageService remoteMessageService;

    /**
     * 业务模块消息推送
     *
     * @param messageBusinessDTO 业务消息发送DTO
     */
    @Override
    public Boolean businessPush(MessageBusinessDTO messageBusinessDTO) {
        // 获取消息配置信息
        MessageBusinessConfigEntity messageBusinessConfig = Optional.ofNullable(
                        messageBusinessConfigService.getOne(new LambdaQueryWrapper<MessageBusinessConfigEntity>()
                                .eq(MessageBusinessConfigEntity::getCode, messageBusinessDTO.getCode()), false))
                .orElseThrow(() -> new CheckedException("业务消息配置不存在"));

        if (messageBusinessConfig.getPersonalStatus() && messageBusinessConfig.getPersonalMessage() == null) {
            throw new CheckedException("请选择推送用户");
        }

        if (!messageBusinessConfig.getGroupStatus() && !messageBusinessConfig.getPersonalStatus()) {
            return Boolean.TRUE;
        }

        MessageConfigEntity messageConfig = messageConfigService.getByIdDeep(messageBusinessConfig.getMessageConfigId());

        // 组装DTO
        MessageDTO messageDTO = new MessageDTO();
        BeanUtils.copyProperties(messageBusinessDTO, messageDTO);
        messageDTO.setMessageBusinessConfig(messageBusinessConfig);
        messageDTO.setMessageConfig(messageConfig);
        messageDTO.setMessageType(messageBusinessConfig.getMessageType());
        if (messageBusinessConfig.getPersonalMessage() != null) {
            String userIds = String.join(",", messageBusinessConfig.getPersonalMessage());
            messageDTO.setUserIds(userIds);
        }

        // 根据业务类型获取对应的handler进行第三方消息推送
        Boolean pushStatus = messageBusinessHandlerMap.get(messageBusinessDTO.getCode()).businessPush(messageDTO);

        // 推送站内信
        String[] userIds = messageBusinessConfig.getUserIds();
        List<Long> userIdList = Arrays.stream(userIds)
                .map(Long::valueOf) // 将每个字符串转换为 Long
                .collect(Collectors.toList());
        String title = String.format("【%s】消息推送", messageBusinessConfig.getName());
        Boolean systemMsgStatus = sendSystemMsg(title, messageBusinessDTO.getMessage(), userIdList, messageBusinessConfig.getModuleCode());
        // 日志保存
        this.saveMessageLog(messageDTO, title, pushStatus && systemMsgStatus, messageConfig.getType(),
                messageBusinessConfig.getCode(), messageBusinessConfig.getModuleCode());
        return Boolean.TRUE;
    }

    /**
     * 根据用户列表推送消息到个人
     */
    @Override
    public Boolean personBusinessPush(MessageDTO messageDTO) {
        // 查询业务模块配置表
        MessageBusinessConfigEntity messageBusinessConfig = messageBusinessConfigService.getOne(new LambdaQueryWrapper<MessageBusinessConfigEntity>()
                .eq(MessageBusinessConfigEntity::getCode, messageDTO.getCode()), false);
        // 查询消息推送配置表
        MessageConfigEntity messageConfig = messageConfigService.getConfigByCode(messageDTO.getCode());

        messageDTO.setMessageConfig(messageConfig);
        messageDTO.setMessageBusinessConfig(messageBusinessConfig);

        // 第三方消息推送
        MessageBusinessHandler messageBusinessHandler = messageBusinessHandlerMap.get(messageDTO.getCode());
        Boolean pushBoolean = messageBusinessHandler.businessPush(messageDTO);

        // 推送站内信
        String title = String.format("【%s】消息推送", messageBusinessConfig.getName());
        Boolean systemMsgStatus = sendSystemMsg(title, messageDTO.getMessage(), messageDTO.getUserList(),
                messageBusinessConfig.getModuleCode());
        // 日志保存
        this.saveMessageLog(messageDTO, title, pushBoolean && systemMsgStatus, messageConfig.getType(),
                messageBusinessConfig.getCode(), messageBusinessConfig.getModuleCode());
        return Boolean.TRUE;
    }

    /**
     * 推送站内信
     *
     * @param title      标题
     * @param msg        内容
     * @param userList   用户id列表
     * @param moduleCode 所属模块
     */
    private Boolean sendSystemMsg(String title, String msg, List<Long> userList, String moduleCode) {
        // 推送站内信
        SysMessageVO sysMessageVO = new SysMessageVO();
        sysMessageVO.setAllFlag("0");
        sysMessageVO.setCategory("1");
        sysMessageVO.setContent(msg);
        sysMessageVO.setSort(0);
        sysMessageVO.setSendFlag(YesOrNoStateEnum.YES.getCode());
        sysMessageVO.setTitle(title);
        sysMessageVO.setModuleCode(moduleCode);
        List<OrgTreeVO> OrgTreeVOList = new ArrayList<>();
        for (Long userId : userList) {
            OrgTreeVO orgTreeVO = new OrgTreeVO();
            orgTreeVO.setId(userId);
            OrgTreeVOList.add(orgTreeVO);
        }
        sysMessageVO.setUserList(OrgTreeVOList);
        sysMessageVO.setCreateBy("admin");
        sysMessageVO.setCreateTime(LocalDateTime.now());
        R r = remoteMessageService.senMsg(sysMessageVO);
        if (r.getCode() != 0) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * 保存推送记录
     *
     * @param messageDTO 消息DTO
     * @param title      标题
     * @param pushStatus 推送状态
     * @param type       第三方消息类型(钉钉、企业微信等)
     * @param code       业务编码
     * @param moduleCode 所属模块
     */
    private void saveMessageLog(MessageDTO messageDTO, String title, Boolean pushStatus, String type, String code, String moduleCode) {
        // 推送记录
        MessageLogEntity messageLogEntity = new MessageLogEntity();
        messageLogEntity.setType(type);
        messageLogEntity.setTitle(title);
        messageLogEntity.setModuleCode(moduleCode);
        messageLogEntity.setBusinessType(code);
        messageLogEntity.setMessageType(messageDTO.getMessageType());
        messageLogEntity.setPushUser(messageDTO.getUserIds());
        messageLogEntity.setTime(LocalDateTime.now());
        messageLogEntity.setContent(messageDTO.getMessage());
        messageLogEntity.setResult(Boolean.FALSE);

        if (pushStatus) {
            messageLogEntity.setResult(Boolean.TRUE);
        }

        messageLogService.save(messageLogEntity);
    }
}
