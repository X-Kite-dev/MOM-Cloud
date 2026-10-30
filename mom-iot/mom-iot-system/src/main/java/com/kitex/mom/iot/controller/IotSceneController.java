package com.kitex.mom.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.iot.dto.IotSceneDTO;
import com.kitex.mom.iot.entity.IotSceneEntity;
import com.kitex.mom.iot.service.IotSceneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 场景联动控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/scene")
@Tag(name = "场景联动管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotSceneController {

	private final IotSceneService sceneService;

	/**
	 * 分页查询场景列表
	 *
	 * @param page  分页对象
	 * @param scene 查询参数
	 * @return 分页结果
	 */
	@GetMapping("/page")
	@Operation(summary = "分页查询场景列表")
	public R<IPage<IotSceneEntity>> getScenePage(Page<IotSceneEntity> page, IotSceneEntity scene) {
		return R.ok(sceneService.getScenePage(page, scene));
	}

	/**
	 * 获取场景详情
	 *
	 * @param id 场景ID
	 * @return 场景详情
	 */
	@GetMapping("/{id}")
	@Operation(summary = "获取场景详情")
	public R<IotSceneDTO> getSceneDetail(@PathVariable Long id) {
		return R.ok(sceneService.getSceneDetail(id));
	}

	/**
	 * 新增场景
	 *
	 * @param scene 场景数据
	 * @return 操作结果
	 */
	@PostMapping
	@Operation(summary = "新增场景")
	@SysLog("新增场景")
	// @PreAuthorize("@pms.hasPermission('iot_scene_add')")
	public R<Boolean> addScene(@RequestBody IotSceneDTO scene) {
		return R.ok(sceneService.addScene(scene));
	}

	/**
	 * 修改场景
	 *
	 * @param scene 场景数据
	 * @return 操作结果
	 */
	@PutMapping
	@Operation(summary = "修改场景")
	@SysLog("修改场景")
	// @PreAuthorize("@pms.hasPermission('iot_scene_edit')")
	public R<Boolean> updateScene(@RequestBody IotSceneDTO scene) {
		return R.ok(sceneService.updateScene(scene));
	}

	/**
	 * 删除场景
	 *
	 * @param ids 场景ID列表
	 * @return 操作结果
	 */
	@DeleteMapping
	@Operation(summary = "删除场景")
	@SysLog("删除场景")
	// @PreAuthorize("@pms.hasPermission('iot_scene_del')")
	public R<Boolean> deleteScene(@RequestBody List<Long> ids) {
		return R.ok(sceneService.deleteSceneByIds(ids));
	}

	/**
	 * 启用/禁用场景
	 *
	 * @param id     场景ID
	 * @param status 状态：0-禁用，1-启用
	 * @return 操作结果
	 */
	@PutMapping("/status")
	@Operation(summary = "启用/禁用场景")
	@SysLog("更新场景状态")
	// @PreAuthorize("@pms.hasPermission('iot_scene_edit')")
	public R<Boolean> updateSceneStatus(@RequestParam Long id, @RequestParam Integer status) {
		return R.ok(sceneService.updateSceneStatus(id, status));
	}

	/**
	 * 执行场景
	 *
	 * @param id 场景ID
	 * @return 操作结果
	 */
	@PostMapping("/execute/{id}")
	@Operation(summary = "执行场景")
	@SysLog("执行场景")
	@PreAuthorize("@pms.hasPermission('iot_scene_execute')")
	public R<Boolean> executeScene(@PathVariable Long id) {
		return sceneService.executeScene(id) ? R.ok(true)
				: R.failed(false, "场景未执行成功，请查看执行历史中的失败原因");
	}

	/**
	 * 远程调用执行场景
	 *
	 * @param id 场景ID
	 * @return 操作结果
	 */
	@PostMapping("/remote/execute/{id}")
	@Operation(summary = "远程调用执行场景")
	@Inner(value = false)
	@PreAuthorize("@pms.hasPermission('iot_scene_execute')")
	public R<Boolean> remoteExecuteScene(@PathVariable Long id) {
		return sceneService.executeScene(id) ? R.ok(true)
				: R.failed(false, "场景未执行成功，请查看执行历史中的失败原因");
	}

	/**
	 * 保存场景设计
	 *
	 * @param scene 场景数据
	 * @return 操作结果
	 */
	@PostMapping("/design")
	@Operation(summary = "保存场景设计")
	@SysLog("保存场景设计")
	// @PreAuthorize("@pms.hasPermission('iot_scene_edit')")
	public R<Boolean> saveSceneDesign(@RequestBody IotSceneDTO scene) {
		return R.ok(sceneService.saveSceneDesign(scene));
	}
}
