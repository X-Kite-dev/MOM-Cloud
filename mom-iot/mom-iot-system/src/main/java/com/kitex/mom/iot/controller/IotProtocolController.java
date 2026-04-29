package com.kitex.mom.iot.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.ProtocolSupports;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 协议管理
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/protocol")
@Tag(name = "IoT设备事件数据管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotProtocolController {

    private final ProtocolSupports protocolSupports;

    /**
     * 查询协议列表
     */
    @Operation(summary = "查询协议列表", description = "查询协议列表")
    @GetMapping("/list")
    public R getProtocolList(ProtocolSupport protocol) {
        List<ProtocolSupport> protocols = protocolSupports.getProtocols();
        String name = protocol.getName();
        if (name != null && !name.trim().isEmpty()) {
            protocols = protocols.stream()
                    .filter(p -> p.getName() != null && p.getName().toLowerCase().contains(name.toLowerCase()))
                    .collect(Collectors.toList());
        }
        return R.ok(protocols);
    }


    /**
     * 查看协议详情
     */
    @Operation(summary = "查看协议详情", description = "查看协议详情")
    @GetMapping("/details")
    public R getProtocolDetails(String protocol) {
        ProtocolSupport protocolSupport = protocolSupports.getProtocol(protocol);
        return R.ok(protocolSupport);
    }

}
