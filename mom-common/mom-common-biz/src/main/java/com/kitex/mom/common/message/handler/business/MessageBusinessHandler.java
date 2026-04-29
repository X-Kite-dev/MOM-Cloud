package com.kitex.mom.common.message.handler.business;

import com.kitex.mom.common.message.dto.MessageDTO;

public interface MessageBusinessHandler {

    Boolean businessPush(MessageDTO messageDTO);
}

