package com.kitex.mom.common.message.handler.business;

import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.message.handler.MessageHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component("TPMDELAY")
@AllArgsConstructor
public class TpmDelayHandler implements MessageBusinessHandler {

    private final Map<String, MessageHandler> messageHandlerMap;

    @Override
    public Boolean businessPush(MessageDTO messageDTO) {
        //拼接消息ID 钉钉重复消息不回重复通知
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        messageDTO.setMessage(messageDTO.getMessage() + "消息编码【" + now.format(formatter) + "】");
        Boolean pushStatus = messageHandlerMap.get(messageDTO.getMessageConfig().getType()).push(messageDTO);
        return pushStatus;
    }
}
