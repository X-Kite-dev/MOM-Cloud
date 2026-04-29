package com.kitex.mom.iot.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.ProtocolSupports;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.entity.IotGatewayEntity;
import com.kitex.mom.iot.protocol.TransportDetail;
import com.kitex.mom.iot.service.IotGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/gateway")
@Tag(name = "IoT网关管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotGatewayController {

    private final IotGatewayService gatewayService;

    private final ProtocolSupports protocolSupports;


    /**
     * 分页查询网关列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询网关列表")
    public R<IPage<IotGatewayEntity>> getGatewayPage(SearchPage page, IotGatewayEntity gateway) {
        return R.ok(gatewayService.queryPage(page.toMpPage(), gateway));
    }


    @GetMapping("/list")
    @Operation(summary = "分页查询网关列表")
    public R<List<IotGatewayEntity>> list(IotGatewayEntity gateway) {
        return R.ok(gatewayService.queryList(gateway));
    }


    /**
     * 根据ID获取网关详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取网关详情")
    @PreAuthorize("@pms.hasPermission('iot_gateway_view')")
    public R<IotGatewayEntity> getGatewayById(@PathVariable("id") Long id) {
        return R.ok(gatewayService.getById(id));
    }

    /**
     * 创建网关
     */
    @PostMapping
    @SysLog("创建网关")
    @Operation(summary = "创建网关")
    @PreAuthorize("@pms.hasPermission('iot_gateway_add')")
    public R<Boolean> createGateway(@RequestBody IotGatewayEntity gateway) {
        return R.ok(gatewayService.createGateway(gateway));
    }

    /**
     * 更新网关
     */
    @PutMapping
    @SysLog("更新网关")
    @Operation(summary = "更新网关")
    @PreAuthorize("@pms.hasPermission('iot_gateway_edit')")
    public R<Boolean> updateGateway(@RequestBody IotGatewayEntity gateway) {
        return R.ok(gatewayService.updateGateway(gateway));
    }

    /**
     * 删除网关
     */
    @DeleteMapping()
    @SysLog("删除网关")
    @Operation(summary = "删除网关")
    public R<Boolean> removeGateway(@RequestBody String[] ids) {
        return R.ok(gatewayService.removeGateway(CollUtil.toList(ids)));
    }

    /**
     * 更新网关状态
     */
    @PutMapping("/status/{id}/{status}")
    @SysLog("更新网关状态")
    @Operation(summary = "更新网关状态")
    @PreAuthorize("@pms.hasPermission('iot_gateway_edit')")
    public R<Boolean> updateGatewayStatus(@PathVariable("id") Long id, @PathVariable("status") String status) {
        return R.ok(gatewayService.updateStatus(id, status));
    }


    /**
     * 根据网络组件ID获取网关列表
     */
    @GetMapping("/network-component/{networkComponentId}")
    @Operation(summary = "根据网络组件ID获取网关列表")
    @PreAuthorize("@pms.hasPermission('iot_gateway_view')")
    public R<List<IotGatewayEntity>> getGatewaysByNetworkComponentId(@PathVariable("networkComponentId") Long networkComponentId) {
        return R.ok(gatewayService.getByNetworkComponentId(networkComponentId));
    }

    /**
     * 启动网关
     */
    @PostMapping("/{id}/start")
    @SysLog("启动网关")
    @Operation(summary = "启动网关")
    @PreAuthorize("@pms.hasPermission('iot_gateway_control')")
    public R<Map<String, Object>> startGateway(@PathVariable("id") Long id) {
        gatewayService.startGateway(id);

        return R.ok();
    }

    /**
     * 停止网关
     */
    @PostMapping("/{id}/stop")
    @SysLog("停止网关")
    @Operation(summary = "停止网关")
    @PreAuthorize("@pms.hasPermission('iot_gateway_control')")
    public R<Map<String, Object>> stopGateway(@PathVariable("id") Long id) {
        gatewayService.stopGateway(id);
        return R.ok();
    }

    /**
     * 获取网关运行状态
     */
    @GetMapping("/{id}/status/runtime")
    @Operation(summary = "获取网关运行状态")
    @PreAuthorize("@pms.hasPermission('iot_gateway_view')")
    public R<Map<String, Object>> getGatewayRuntimeStatus(@PathVariable("id") Long id) {
        boolean gatewayRunning = gatewayService.isGatewayRunning(id);
        if (!gatewayRunning) {
            return R.failed("网关未启动");
        }
        return R.ok();
    }

    /**
     * 批量获取网关运行状态
     */
    @GetMapping("/status/runtime")
    @Operation(summary = "批量获取网关运行状态")
    @PreAuthorize("@pms.hasPermission('iot_gateway_view')")
    public R<Map<String, Boolean>> getAllGatewayRuntimeStatus() {
        return R.ok(gatewayService.getAllGatewayStatus());
    }


    /**
     * 获取网关对应的协议内容
     */
    @GetMapping("/{id}/protocol")
    @Operation(summary = "获取网关对应的协议内容")
    public R<TransportDetail> getGatewayProtocol(@PathVariable("id") Long id) {
        IotGatewayEntity gateway = gatewayService.getById(id);
        if (gateway == null) {
            return R.failed("网关不存在");
        }
        ProtocolSupport protocol = protocolSupports.getProtocol(gateway.getProtocol());
        String transport = gateway.getTransport();

        TransportDetail transportDetail = TransportDetail.of(protocol, TransportType.getByName(transport.toUpperCase()));

        return R.ok(transportDetail);
    }
} 