package com.kitex.mom.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.entity.IotNetworkComponentEntity;
import com.kitex.mom.iot.service.IotNetworkComponentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IoT网络组件控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/network")
@Tag(name = "IoT网络组件管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotNetworkComponentController {

    private final IotNetworkComponentService networkComponentService;

    /**
     * 分页查询网络组件列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询网络组件列表")
    public R<IPage<IotNetworkComponentEntity>> getNetworkComponentPage(SearchPage page, IotNetworkComponentEntity networkComponent) {
        return R.ok(networkComponentService.queryPage(page.toMpPage(), networkComponent));
    }

    /**
     * 查询可用的网络组件
     */
    @GetMapping("/list")
    @Operation(summary = "查询网络组件列表")
    public R<List<IotNetworkComponentEntity>> list(IotNetworkComponentEntity networkComponent) {
        return R.ok(networkComponentService.list(Wrappers.query(networkComponent)));
    }

    /**
     * 根据类型获取网络组件列表
     */
    @GetMapping("/list/{type}")
    @Operation(summary = "根据类型获取网络组件列表")
    @PreAuthorize("@pms.hasPermission('iot_network_component_view')")
    public R<List<IotNetworkComponentEntity>> getNetworkComponentsByType(@PathVariable("type") String type) {
        return R.ok(networkComponentService.getNetworkComponentsByType(type));
    }

    /**
     * 根据ID获取网络组件详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取网络组件详情")
    @PreAuthorize("@pms.hasPermission('iot_network_component_view')")
    public R<IotNetworkComponentEntity> getNetworkComponentById(@PathVariable("id") Long id) {
        return R.ok(networkComponentService.getNetworkComponentById(id));
    }

    /**
     * 创建网络组件
     */
    @PostMapping
    @SysLog("创建网络组件")
    @Operation(summary = "创建网络组件")
    @PreAuthorize("@pms.hasPermission('iot_network_component_add')")
    public R<Boolean> createNetworkComponent(@RequestBody IotNetworkComponentEntity networkComponent) {
        return R.ok(networkComponentService.saveNetworkComponent(networkComponent));
    }

    /**
     * 更新网络组件
     */
    @PutMapping
    @SysLog("更新网络组件")
    @Operation(summary = "更新网络组件")
    @PreAuthorize("@pms.hasPermission('iot_network_component_edit')")
    public R<Boolean> updateNetworkComponent(@RequestBody IotNetworkComponentEntity networkComponent) {
        return R.ok(networkComponentService.updateNetworkComponent(networkComponent));
    }

    /**
     * 删除网络组件
     */
    @DeleteMapping
    @SysLog("删除网络组件")
    @Operation(summary = "删除网络组件")
    @PreAuthorize("@pms.hasPermission('iot_network_component_del')")
    public R<Boolean> deleteNetworkComponent(@RequestBody List<Long> ids) {
        return R.ok(networkComponentService.deleteNetworkComponent(ids));
    }

} 