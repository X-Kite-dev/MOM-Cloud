package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.service.WmsWarehouseMenuService;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 仓库菜单配置
 *
 * @author kitex
 * @date 2025-01-13 14:41:50
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseMenu")
@Tag(description = "wmsWarehouseMenu", name = "仓库菜单配置管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsWarehouseMenuController {

    private final WmsWarehouseMenuService wmsWarehouseMenuService;

    /**
     * 返回树形菜单集合
     *
     * @param parentId 父节点ID
     * @param menuName 菜单名称
     * @param type     菜单类型 1 按钮、0 菜单
     * @return 树形菜单
     */
    @GetMapping(value = "/tree")
    public R getTree(Long parentId, String menuName, String type) {
        return R.ok(wmsWarehouseMenuService.treeMenu(parentId, menuName, type));
    }

    /**
     * 查找仓库业务选择集合
     */
    @GetMapping(value = "/listCheckBiz")
    public R listCheckBiz(String warehouseCode) {
        return R.ok(wmsWarehouseMenuService.listCheckBiz(warehouseCode));
    }

    /**
     * 查找一个仓库的业务选择集合-App
     */
    @GetMapping(value = "/checkBizByWarehouseApp")
    public R checkBizByWarehouseApp(@RequestParam String warehouseCode) {
        return R.ok(wmsWarehouseMenuService.checkBizByWarehouse(warehouseCode));
    }

    /**
     * 提交选择的功能
     */
    @PostMapping(value = "/commitCheckBiz")
    public R commitCheckBiz(@RequestBody List<WmsWarehouseBizCheckVO> list) {
        return R.ok(wmsWarehouseMenuService.commitCheckBiz(list));
    }

    /**
     * 同步系统菜单
     */
    @GetMapping(value = "/syncSysMenu")
    public R syncSysMenu() {
        return R.ok(wmsWarehouseMenuService.syncSysMenu());
    }

}