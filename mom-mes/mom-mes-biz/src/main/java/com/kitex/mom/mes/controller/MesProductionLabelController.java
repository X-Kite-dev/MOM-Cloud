package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesProductionLabelDTO.*;
import com.kitex.mom.mes.service.impl.MesProductionLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工单及可信制造单件标签入口；沿真实组织/本人权限办理原件及打印意图。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-label")
public class MesProductionLabelController {
	private final MesProductionLabelService service;

	/**
	 * 按原派工、当前本人及实际接收工位读取真实标签来源；归属或可信制造出生不符时拒绝。
	 */
	@GetMapping("/context")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<Context> context(Query query) {
		return R.ok(service.context(query));
	}

	/**
	 * 读取当前实际工厂、指定业务类型的启用模板；跨厂、停用或类型不符时拒绝。
	 */
	@GetMapping("/template")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<Template> template(Query query, @RequestParam String templateId) {
		return R.ok(service.template(query, templateId));
	}

	/**
	 * 只读核对完整来源并返回预览；来源结构、原单位或模板指纹不符时拒绝，不产生成功保存事实。
	 */
	@PostMapping("/preview")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<Preview> preview(@RequestBody IssueCommand command) {
		return R.ok(service.preview(command));
	}

	/**
	 * 锁定原派工并按原请求号发放不可变标签原件；来源/模板变化或同证据换号时拒绝。
	 */
	@PostMapping("/issue")
	@PreAuthorize("@pms.hasPermission('mesProduceTask:start')")
	public R<Original<Issue>> issue(@RequestBody IssueCommand command) {
		return R.ok(service.issue(command));
	}

	/**
	 * 锁定标签原件后登记首次或重打意图；须本人原工位、原来源、原模板及完整重打原因/前件，不调用硬件。
	 */
	@PostMapping("/intent")
	@PreAuthorize("@pms.hasPermission('mesProduceTask:start')")
	public R<Original<Intent>> intent(@RequestBody IntentCommand command) {
		return R.ok(service.intent(command));
	}

	/**
	 * 读取同一来源及模板的首次标签原件；未找到返回ABSENT，不能推断从未执行。
	 */
	@GetMapping("/existing")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<Resolution<Issue>> existing(Query query, @RequestParam String templateId) {
		return R.ok(service.findIssue(query, templateId));
	}

	/**
	 * 按稳定原请求号读取本人原发放回执；ABSENT保持未知。
	 */
	@GetMapping("/issue-request")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<Resolution<Issue>> issueRequest(@RequestParam String requestId) {
		return R.ok(service.inspectIssue(requestId));
	}

	/**
	 * 按稳定原请求号读取本人打印意图回执；恢复结果不会自动再次打印。
	 */
	@GetMapping("/intent-request")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<Resolution<Intent>> intentRequest(@RequestParam String requestId) {
		return R.ok(service.inspectIntent(requestId));
	}

	/**
	 * 读取本人原件的完整连续意图链；超过1000条或原件关联损坏时拒绝截断展示。
	 */
	@GetMapping("/history")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:show')")
	public R<List<Original<Intent>>> history(@RequestParam String issueId) {
		return R.ok(service.history(issueId));
	}
}
