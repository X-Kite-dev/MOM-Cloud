package com.kitex.mom.common.message.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.message.handler.business.MessageBusinessHandler;
import com.kitex.mom.common.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 消息推送
 *
 * @author kitex
 * @date 2025-01-13 14:45:39
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/push")
@Tag(description = "push", name = "消息推送管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MessageController {

    private final MessageService messageService;
    private final Map<String, MessageBusinessHandler> messageBusinessHandlerMap;

    /**
     * 业务模块消息推送
     *
     * @param messageBusinessDTO 业务消息发送DTO
     */
    @Inner
    @Operation(summary = "消息推送", description = "消息推送")
    @PostMapping("/businessPush")
    public R<Boolean> businessPush(@RequestBody @Valid MessageBusinessDTO messageBusinessDTO) {
        return R.ok(messageService.businessPush(messageBusinessDTO));
    }


    /**
     * 根据用户列表推送消息到个人
     */
    @Inner
    @Operation(summary = "根据用户列表推送消息到个人", description = "根据用户列表推送消息到个人")
    @PostMapping("/pushPersonMsg")
    public R<Boolean> pushPersonMsg(@RequestBody @Valid MessageDTO messageDTO) {
        return R.ok(messageService.personBusinessPush(messageDTO));
    }

}