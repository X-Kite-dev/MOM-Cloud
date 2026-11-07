package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.req.QmsNcrReworkDTO.*;
import com.kitex.mom.qms.service.impl.QmsNcrReworkService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 仓储NCR原位返工入口；岗位权限之外服务和WMS分别重验实际工厂及原仓范围。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/bad/detail/{id}/rework")
public class QmsNcrReworkController {
	private final QmsNcrReworkService service;

	/**
	 * 受权只读进度，不因打开弹窗发命令或落账。
	 */
	@GetMapping("/context")
	@PreAuthorize("@pms.hasPermission('bad:show')")
	public R<Progress> context(@PathVariable long id) {
		return R.ok(service.context(id));
	}

	/**
	 * 记录实名、整量实作及凭证。
	 */
	@PostMapping("/complete")
	@PreAuthorize("@pms.hasPermission('bad:rework:execute')")
	public R<Progress> complete(@PathVariable long id, @RequestBody Complete input) {
		return R.ok(service.complete(id, input));
	}

	/**
	 * 原人有据作废完成登记。
	 */
	@PostMapping("/void-completion")
	@PreAuthorize("@pms.hasPermission('bad:rework:execute')")
	public R<Progress> voidCompletion(@PathVariable long id, @RequestBody VoidCompletion input) {
		return R.ok(service.voidCompletion(id, input));
	}

	/**
	 * 冻结并提交独立复核意图，再办理原库存。
	 */
	@PostMapping("/prepare")
	@PreAuthorize("@pms.hasPermission('bad:verify')")
	public R<PreparationOutcome> prepare(@PathVariable long id, @RequestBody Prepare input) {
		return R.ok(service.prepare(id, input));
	}

	/**
	 * 只查询原回执并对账，未找到时不自动发送。
	 */
	@PostMapping("/lookup")
	@PreAuthorize("@pms.hasPermission('bad:verify')")
	public R<Progress> lookup(@PathVariable long id, @RequestBody Recover input) {
		return R.ok(service.recover(id, input, "lookup"));
	}

	/**
	 * 显式续办原人原文，只有权威NOT_FOUND才能重发。
	 */
	@PostMapping("/resume")
	@PreAuthorize("@pms.hasPermission('bad:verify')")
	public R<Progress> resume(@PathVariable long id, @RequestBody Recover input) {
		return R.ok(service.recover(id, input, "resume"));
	}

	/**
	 * 只有WMS永久CANCELLED墓碑才释放本意图；已生效则回收原结果。
	 */
	@PostMapping("/cancel")
	@PreAuthorize("@pms.hasPermission('bad:verify')")
	public R<Progress> cancel(@PathVariable long id, @RequestBody Recover input) {
		return R.ok(service.recover(id, input, "cancel"));
	}
}
