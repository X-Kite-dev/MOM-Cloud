package com.kitex.mom.common.message.handler;

import com.kitex.mom.common.message.dto.MessageDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 微信消息推送handler
 *
 * @author kitex
 * @date 2025-01-14 10:28:49
 */
@Slf4j
@Component("WECHAT")
@AllArgsConstructor
public class WeChatMessageHandler implements MessageHandler {

    @Override
    public Boolean push(MessageDTO messageDTO) {
        return Boolean.FALSE;
    }
}
