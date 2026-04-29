package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.service.IotDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * MQTT 相关
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mqtt")
@Tag(name = "IoT MQTT消息管理")
@Slf4j
public class IotMqttController {

    private final IotDeviceService iotDeviceService;

    /**
     * 设备账号验证
     * 验证设备是否存在，以及设备的账号密码是否正确
     * 该接口提供给MQTT服务端使用
     *
     * @param params 包含clientIdentifier(设备编码)、username(用户名)、password(密码)、deviceStatus(设备状态)、protocolType(协议类型)
     * @return 验证结果，包含是否通过验证和相关信息
     */
    @PostMapping("/clientAuthentication")
    @Inner(value = false)
    @Operation(summary = "MQTT设备账号验证", description = "验证设备是否存在，以及设备的账号密码是否正确")
    public R<Map<String, Object>> clientAuthentication(@RequestBody Map<String, String> params) {
        log.info("MQTT设备认证请求: {}", params);

        // 获取请求参数
        final String clientIdentifier = params.get("clientid");
        final String username = params.get("username");
        final String password = params.get("password");
        final String deviceStatus = params.get("deviceStatus");
        final String protocolType = params.get("protocolType");

        // 构建返回结果
        Map<String, Object> result = new HashMap<>(8);
        result.put("success", false);

        // 参数校验
        if (StrUtil.isBlank(clientIdentifier)) {
            result.put("message", "设备标识符不能为空");
            return R.ok(result);
        }

        // 查询设备信息
        IotDeviceEntity device = iotDeviceService.getDeviceByCode(clientIdentifier);

        // 验证设备是否存在
        if (device == null) {
            log.warn("设备不存在: {}", clientIdentifier);
            result.put("message", "设备不存在");
            return R.ok(result);
        }

        // 验证设备状态
        if (device.getStatus() != null && device.getStatus() == 0) {
            log.warn("设备已禁用: {}", clientIdentifier);
            result.put("message", "设备已禁用");
            return R.ok(result);
        }

        // 验证用户名和密码
        if (StrUtil.isNotBlank(username) && StrUtil.isNotBlank(password)) {
            // 如果设备配置了用户名和密码，则需要验证
            if (StrUtil.isNotBlank(device.getDeviceCode()) && StrUtil.isNotBlank(device.getProductCode())) {
                if (!username.equals(device.getProductCode()) || !password.equals(device.getDeviceCode())) {
                    log.warn("设备认证失败，用户名或密码错误: {}", clientIdentifier);
                    result.put("message", "用户名或密码错误");
                    return R.ok(result);
                }
            }
            // 如果设备没有配置用户名和密码，但请求提供了，则使用产品默认的认证方式
            else {
                // TODO: 如果需要，可以添加产品级别的认证逻辑
            }
        }

        // 认证通过，更新设备状态
        try {
            // 更新设备最后上线时间和状态
            device.setLastOnlineTime(LocalDateTime.now());

            // 如果有设备状态参数，则更新设备状态
            if (StrUtil.isNotBlank(deviceStatus)) {
                device.setStatus(Integer.parseInt(deviceStatus));
            } else {
                // 默认设置为在线状态
                device.setStatus(2);
            }

            // 更新设备信息
            iotDeviceService.updateById(device);

            // 构建成功响应
            result.put("success", true);
            result.put("message", "认证成功");
            result.put("deviceId", device.getId());
            result.put("productId", device.getProductId());
            result.put("productCode", device.getProductCode());

            log.info("设备认证成功: {}", clientIdentifier);
            return R.ok(result);
        } catch (Exception e) {
            log.error("更新设备状态失败: {}", e.getMessage(), e);
            result.put("message", "认证成功，但更新设备状态失败");
            result.put("success", true);  // 仍然允许连接
            return R.ok(result);
        }
    }

    /**
     * MQTT发布消息授权
     * 验证设备是否有权限发布指定主题的消息
     *
     * @param params 包含clientIdentifier(设备编码)、topic(主题)、action(动作)
     * @return 授权结果
     */
    @PostMapping("/publishAuthorization")
    @Operation(summary = "MQTT发布消息授权", description = "验证设备是否有权限发布指定主题的消息")
    public R<Map<String, Object>> publishAuthorization(@RequestBody Map<String, String> params) {
        log.info("MQTT发布授权请求: {}", params);

        String clientIdentifier = params.get("clientIdentifier");
        String topic = params.get("topic");
        String action = params.get("action");

        Map<String, Object> result = new HashMap<>(4);
        result.put("success", false);

        // 参数校验
        if (StrUtil.isBlank(clientIdentifier) || StrUtil.isBlank(topic) || StrUtil.isBlank(action)) {
            result.put("message", "参数不完整");
            return R.ok(result);
        }

        // 查询设备信息
        IotDeviceEntity device = iotDeviceService.getDeviceByCode(clientIdentifier);

        // 验证设备是否存在
        if (device == null) {
            log.warn("设备不存在: {}", clientIdentifier);
            result.put("message", "设备不存在");
            return R.ok(result);
        }

        // 验证设备状态
        if (device.getStatus() != null && device.getStatus() == 0) {
            log.warn("设备已禁用: {}", clientIdentifier);
            result.put("message", "设备已禁用");
            return R.ok(result);
        }

        // 验证主题权限
        // 这里可以根据业务需求实现更复杂的主题权限控制
        // 例如：只允许设备发布自己的主题，格式为：/产品编码/设备编码/xxx
        String expectedTopicPrefix = "/" + device.getProductCode() + "/" + device.getDeviceCode() + "/";
        if (!topic.startsWith(expectedTopicPrefix)) {
            log.warn("设备 {} 无权发布主题: {}", clientIdentifier, topic);
            result.put("message", "无权发布该主题");
            return R.ok(result);
        }

        // 授权通过
        result.put("success", true);
        result.put("message", "授权成功");
        log.info("设备 {} 发布主题 {} 授权成功", clientIdentifier, topic);
        return R.ok(result);
    }

    /**
     * MQTT订阅主题授权
     * 验证设备是否有权限订阅指定主题
     *
     * @param params 包含clientIdentifier(设备编码)、topic(主题)
     * @return 授权结果
     */
    @PostMapping("/subscribeAuthorization")
    @Operation(summary = "MQTT订阅主题授权", description = "验证设备是否有权限订阅指定主题")
    public R<Map<String, Object>> subscribeAuthorization(@RequestBody Map<String, String> params) {
        log.info("MQTT订阅授权请求: {}", params);

        String clientIdentifier = params.get("clientIdentifier");
        String topic = params.get("topic");

        Map<String, Object> result = new HashMap<>(4);
        result.put("success", false);

        // 参数校验
        if (StrUtil.isBlank(clientIdentifier) || StrUtil.isBlank(topic)) {
            result.put("message", "参数不完整");
            return R.ok(result);
        }

        // 查询设备信息
        IotDeviceEntity device = iotDeviceService.getDeviceByCode(clientIdentifier);

        // 验证设备是否存在
        if (device == null) {
            log.warn("设备不存在: {}", clientIdentifier);
            result.put("message", "设备不存在");
            return R.ok(result);
        }

        // 验证设备状态
        if (device.getStatus() != null && device.getStatus() == 0) {
            log.warn("设备已禁用: {}", clientIdentifier);
            result.put("message", "设备已禁用");
            return R.ok(result);
        }

        // 验证主题权限
        // 这里可以根据业务需求实现更复杂的主题权限控制
        // 例如：设备可以订阅自己的下行主题，格式为：/产品编码/设备编码/command
        String expectedTopicPrefix = "/" + device.getProductCode() + "/" + device.getDeviceCode() + "/";
        if (!topic.startsWith(expectedTopicPrefix) && !topic.equals("$SYS/#")) {
            log.warn("设备 {} 无权订阅主题: {}", clientIdentifier, topic);
            result.put("message", "无权订阅该主题");
            return R.ok(result);
        }

        // 授权通过
        result.put("success", true);
        result.put("message", "授权成功");
        log.info("设备 {} 订阅主题 {} 授权成功", clientIdentifier, topic);
        return R.ok(result);
    }
}