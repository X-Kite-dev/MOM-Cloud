package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesDispatchAllocationDTO;
import com.kitex.mom.mes.service.impl.MesDispatchAllocationService;
import com.kitex.mom.mes.vo.MesDispatchAllocationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 派工配额权威余额、永久受理回执及未受理原号作废入口；菜单权限沿原派工权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/arrange/allocation")
public class MesDispatchAllocationController {

	private final MesDispatchAllocationService service;

	/** 查询真实冻结工序余额；未知历史明确返回不可新分配原因。 */
	@GetMapping("/balance")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<List<MesDispatchAllocationVO.Balance>> balance(@RequestParam String taskCode) {
		return R.ok(service.balance(taskCode));
	}

	/** 按稳定原号与完整正文实际出生逐行派工，重复提交回原永久结果。 */
	@PostMapping("/execute")
	@SysLog("按稳定配额原命令派工")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:arrange')")
	public R<MesDispatchAllocationVO.Receipt> execute(@RequestBody MesDispatchAllocationDTO.Command command) {
		return R.ok(service.execute(command));
	}

	/** 同组织原操作人查证原号；ABSENT不能作为换号重投依据。 */
	@GetMapping("/receipt/{requestId}")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<MesDispatchAllocationVO.Receipt> receipt(@PathVariable String requestId) {
		return R.ok(service.receipt(requestId));
	}

	/** 持完整原命令永久作废未受理原号；已受理时只回原真实派工结果。 */
	@PostMapping("/cancel")
	@SysLog("永久作废未受理派工原命令")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:arrange')")
	public R<MesDispatchAllocationVO.Receipt> cancel(@RequestBody MesDispatchAllocationDTO.Cancel command) {
		return R.ok(service.cancel(command));
	}
}
