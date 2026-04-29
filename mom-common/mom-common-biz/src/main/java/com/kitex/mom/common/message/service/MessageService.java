package com.kitex.mom.common.message.service;

import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.common.message.dto.MessageDTO;

public interface MessageService {

    /**
     * 业务模块消息推送
     *
     * @param messageBusinessDTO 业务消息发送DTO
     */
    Boolean businessPush(MessageBusinessDTO messageBusinessDTO);

    /**
     * 根据用户列表推送消息到个人
     */
    Boolean personBusinessPush(MessageDTO messageDTO);
}
