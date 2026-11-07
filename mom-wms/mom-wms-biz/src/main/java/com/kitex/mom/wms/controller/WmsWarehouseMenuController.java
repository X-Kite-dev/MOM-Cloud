package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.WmsWarehouseMenuService;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import com.kitex.mom.wms.vo.WmsWarehouseConfigurationSnapshot;
import com.kitex.mom.wms.vo.WmsWarehouseConfigurationCommand;
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
	private final WmsWarehouseBusinessGuard warehouseBusinessGuard;

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
	 * 与仓库写入口同源的只读准入试算。
	 */
	@GetMapping(value = "/previewBusiness")
	public R<WmsWarehouseBusinessGuard.Evaluation> previewBusiness(@RequestParam String warehouseCode,
	                                                               @RequestParam String billType, @RequestParam boolean inbound) {
		return R.ok(warehouseBusinessGuard.evaluate(warehouseCode, billType, inbound));
	}

	/**
	 * 当前仓库的标准业务类型覆盖情况；与单项试算和写入口共用规则。
	 */
	@GetMapping(value = "/previewMatrix")
	@HasPermission("wms_wmsWarehouseMenu_view")
	public R<List<WmsWarehouseBusinessGuard.MatrixRow>> previewMatrix(@RequestParam String warehouseCode) {
		return R.ok(warehouseBusinessGuard.previewMatrix(warehouseCode));
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
	@HasPermission("wms_wmsWarehouseMenu_add")
	public R<Boolean> commitCheckBiz(@RequestBody List<WmsWarehouseBizCheckVO> list,
	                                 @RequestHeader("If-Match") String expectedRevision) {
		wmsWarehouseMenuService.commitConfiguration(new WmsWarehouseConfigurationCommand(expectedRevision, list));
		return R.ok(true);
	}

	@GetMapping("/configuration")
	@HasPermission("wms_wmsWarehouseMenu_view")
	public R<WmsWarehouseConfigurationSnapshot> configuration() {
		return R.ok(wmsWarehouseMenuService.configurationSnapshot());
	}

	@PutMapping("/configuration")
	@HasPermission("wms_wmsWarehouseMenu_add")
	public R<WmsWarehouseConfigurationSnapshot> saveConfiguration(@RequestBody WmsWarehouseConfigurationCommand command) {
		return R.ok(wmsWarehouseMenuService.commitConfiguration(command));
	}

	/**
	 * 同步系统菜单
	 */
	@PostMapping(value = "/syncSysMenu")
	@HasPermission("wms_wmsWarehouseMenu_add")
	public R<Boolean> syncSysMenu(@RequestBody List<Long> expectedIds) {
		return R.ok(wmsWarehouseMenuService.syncSysMenu(expectedIds));
	}

	/**
	 * 兼容旧客户端；新增页面使用带预览ID校验的POST。
	 */
	@GetMapping(value = "/syncSysMenu")
	@HasPermission("wms_wmsWarehouseMenu_add")
	public R syncSysMenu() {
		return R.ok(wmsWarehouseMenuService.syncSysMenu());
	}

}
