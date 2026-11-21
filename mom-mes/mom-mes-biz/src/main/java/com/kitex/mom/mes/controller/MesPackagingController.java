package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesPackagingDTO;
import com.kitex.mom.mes.service.impl.MesPackagingExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标准包装工作区：现场封箱沿报工权限；历史任务采用使用独立管理权限，不自动授给作业员。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPackaging")
@Tag(name = "标准生产包装", description = "真实待装、初次封箱、原号结果与历史任务采用")
public class MesPackagingController {

	private final MesPackagingExecutionService service;

	/** @return 指定派工真实待装数量、冻结容量及最近箱的当前状态。 */
	@GetMapping("/progress")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:report','mesWrap:show')")
	@Operation(summary = "包装工作区", description = "不以历史装箱记录总数推算当前箱进度")
	public R<MesPackagingDTO.Progress> progress(@RequestParam String arrangeCode) {
		return R.ok(service.progress(arrangeCode));
	}

	/** 从原外部容器码核本厂待封数量、质量进度和原派工。 */
	@GetMapping("/pending-container")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:report','mesWrap:show')")
	@Operation(summary = "核对数量包装的待封容器")
	public R<MesPackagingDTO.PendingContainer> pendingContainer(@RequestParam String containerCode) {
		return R.ok(service.pendingContainer(containerCode));
	}

	/** 按箱码和可选的精确成员码核当前或初封内容，每页三十条。 */
	@GetMapping("/box")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:report','mesWrap:show')")
	@Operation(summary = "按箱核对原封箱与当前成员")
	public R<MesPackagingDTO.BoxContents> box(@RequestParam String wrapCode,
			@RequestParam(defaultValue = "CURRENT") MesPackagingDTO.ContentView view,
			@RequestParam(defaultValue = "") String identifier, @RequestParam(defaultValue = "1") int page) {
		return R.ok(service.boxContents(wrapCode, view, identifier, page));
	}

	/**
	 * @return 当前工厂本人永久原封箱结果；未查到返回null不证明未执行，仍须原号原文续投或正式封存。
	 */
	@GetMapping("/receipt")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:report','mesWrap:show')")
	@Operation(summary = "封箱原号回执", description = "原成功与后来当前箱状态分列，缺回执不证明未执行")
	public R<MesPackagingDTO.Receipt> receipt(@RequestParam String requestId) {
		return R.ok(service.receipt(requestId));
	}

	/** @return 同派工显式选择的真实待装成员封箱结果；不增加报工产量或库存。 */
	@PostMapping("/seal")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:report')")
	@SysLog("生产尾箱封箱")
	@Operation(summary = "待装内容封箱", description = "同派工逐件尾箱或一条完整数量包装，须有原因及实际工位权限")
	public R<MesPackagingDTO.Receipt> seal(@Valid @RequestBody MesPackagingDTO.Seal command) {
		return R.ok(service.seal(command));
	}

	/** @return 已受理原结果或永久未执行封存结果，不冲销任何已有封箱。 */
	@PostMapping("/close-unaccepted")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:report')")
	@SysLog("封存未受理封箱原号")
	@Operation(summary = "封存未受理封箱原号", description = "仅在正式确认CANCELLED后可重新采集，已有成功只回原结果")
	public R<MesPackagingDTO.Receipt> closeUnaccepted(@Valid @RequestBody MesPackagingDTO.Close command) {
		return R.ok(service.closeUnaccepted(command));
	}

	/** @return 历史派工显式采用的永久依据；独立权限不包含角色授权或修改历史事实。 */
	@PostMapping("/adopt")
	@PreAuthorize("@pms.hasPermission('mesWrap:adopt')")
	@SysLog("历史派工采用包装依据")
	@Operation(summary = "历史派工采用包装依据", description = "选择同工厂启用规则；已有非空规则和容量不能更换")
	public R<MesPackagingDTO.Adoption> adopt(@Valid @RequestBody MesPackagingDTO.Adopt command) {
		return R.ok(service.adopt(command));
	}
}
