package com.kitex.mom.common.message.handler.business;

import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.message.handler.MessageHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component("WMS")
@AllArgsConstructor
public class WmsHandler implements MessageBusinessHandler {

    private final Map<String, MessageHandler> messageHandlerMap;

    @Override
    public Boolean businessPush(MessageDTO messageDTO) {
        Boolean pushStatus = messageHandlerMap.get(messageDTO.getMessageConfig().getType()).push(messageDTO);
        return pushStatus;
    }
}
