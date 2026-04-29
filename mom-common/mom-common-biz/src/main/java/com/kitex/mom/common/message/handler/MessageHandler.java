package com.kitex.mom.common.message.handler;

import com.kitex.mom.common.message.dto.MessageDTO;

public interface MessageHandler {

    Boolean push(MessageDTO messageDTO);
}
