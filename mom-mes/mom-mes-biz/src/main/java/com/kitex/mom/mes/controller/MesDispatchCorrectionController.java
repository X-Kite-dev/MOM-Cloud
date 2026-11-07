package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesDispatchCorrectionDTO;
import com.kitex.mom.mes.service.impl.MesDispatchCorrectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 受控派工修正的正式日期班次预检、异人审批和永久原号入口。
 * 申请明确所属日期及实例编码，审批只处理冻结目标，不允许夹带新值。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesDispatchCorrection")
public class MesDispatchCorrectionController {

	private final MesDispatchCorrectionService service;

	/**
	 * 只读核对明确日期的正式班次实例及授权工位；缺日期时返回阻断，不猜测所属日。
	 */
	@GetMapping("/preview")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<MesDispatchCorrectionDTO.Preview> preview(@RequestParam String arrangeId,
	                                                   @RequestParam(required = false) String shiftCode,
	                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate shiftDate,
	                                                   @RequestParam(required = false) String worksiteCodes) {
		return R.ok(service.preview(arrangeId, shiftCode, shiftDate, worksiteCodes));
	}

	/**
	 * 按实际组织分页读取原申请，保留无日期依据的历史待办。
	 */
	@GetMapping("/page")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<MesDispatchCorrectionDTO.Page> page(@RequestParam(defaultValue = "1") long current,
	                                             @RequestParam(defaultValue = "20") long size,
	                                             @RequestParam(required = false) String status,
	                                             @RequestParam(required = false) String arrangeId) {
		return R.ok(service.page(current, size, status, arrangeId));
	}

	/**
	 * 读取精确原申请、冻结日期目标及当前资格；历史未办申请仍可查询和终止。
	 */
	@GetMapping("/{id:[0-9]+}")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<MesDispatchCorrectionDTO.Detail> detail(@PathVariable String id) {
		return R.ok(service.detail(id));
	}

	/**
	 * 只读核对本人永久原请求号，不重建原文或以当前事实覆盖回执。
	 */
	@GetMapping("/requests/{requestId}")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<MesDispatchCorrectionDTO.Resolution> receipt(@PathVariable String requestId) {
		return R.ok(service.receipt(requestId));
	}

	/**
	 * 保留申请/独立审核权限及原号语义，只有完整日期目标可产生新的替代派工。
	 */
	@PostMapping("/commands")
	@SysLog("办理受控派工修正")
	@PreAuthorize("#command != null && ((#command.action == 'SUBMIT' && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:apply')) || ((#command.action == 'APPROVE' || #command.action == 'REJECT') && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:review')) || (#command.action == 'WITHDRAW' && @pms.hasPermission('mesDispatchReturn:apply')))")
	public R<MesDispatchCorrectionDTO.Resolution> command(@RequestBody MesDispatchCorrectionDTO.Command command) {
		return R.ok(service.command(command));
	}

	/**
	 * 按原命令封存未受理请求；旧无日期原文仍可取消，已接受原号返回原回执。
	 */
	@PostMapping("/cancel-original")
	@SysLog("取消未受理派工修正原号")
	@PreAuthorize("#command != null && ((#command.action == 'SUBMIT' && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:apply')) || ((#command.action == 'APPROVE' || #command.action == 'REJECT') && @pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesDispatchReturn:review')) || (#command.action == 'WITHDRAW' && @pms.hasPermission('mesDispatchReturn:apply')))")
	public R<MesDispatchCorrectionDTO.Resolution> cancel(@RequestBody MesDispatchCorrectionDTO.Command command) {
		return R.ok(service.cancel(command));
	}
}
