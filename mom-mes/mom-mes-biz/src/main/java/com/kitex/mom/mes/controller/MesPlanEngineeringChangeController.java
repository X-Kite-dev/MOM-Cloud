package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO;
import com.kitex.mom.mes.service.impl.MesPlanEngineeringDecisionService;
import com.kitex.mom.plan.feign.RemotePlanEngineeringPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原工程页节点工时的预核、异人采用与原号恢复。先提交MES本地永久事实，再确认PLAN；
 * 远端失败不会被包装为本地回滚，也不能仅凭返回200宣称来源已解除保护。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPlanEngineeringChange")
@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
		+ "(@pms.hasPermission('mesInFlightSopChange:propose') || @pms.hasPermission('mesInFlightSopChange:review'))")
public class MesPlanEngineeringChangeController {

	private final MesPlanEngineeringDecisionService service;
	private final RemotePlanEngineeringPreparationService plans;

	/**
	 * 只读原永久决策，null表示未知，不解锁或新建请求。
	 */
	@GetMapping("/request")
	public R<MesPlanEngineeringDecisionDTO.Receipt> request(@RequestParam String requestId) {
		return R.ok(service.request(requestId));
	}

	/**
	 * 小型原/目标工时对照，仍须正式采用时锁内核原范围摘要。
	 */
	@GetMapping("/preview")
	public R<MesPlanEngineeringDecisionDTO.Preview> preview(@RequestParam String requestId) {
		return R.ok(service.preview(requestId));
	}

	/**
	 * 仅目标节点标准工时批准层；实际接收由原receive同事务冻结来源。
	 */
	@PostMapping("/apply")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && @pms.hasPermission('mesInFlightSopChange:review') && "
			+ "@pms.hasPermission('mesPlanEngineeringChange:adopt')")
	public R<MesPlanEngineeringDecisionDTO.Outcome> apply(@RequestBody MesPlanEngineeringDecisionDTO.Apply command) {
		var receipt = service.apply(command);
		try {
			var response = plans.finish(receipt.requestId());
			var actual = response == null ? null : response.getData();
			var resolution = actual == null ? null : actual.resolution();
			boolean acknowledged = response != null && response.isOk() && resolution != null
					&& receipt.requestId().equals(actual.original().requestId()) &&
					receipt.taskCode().equals(actual.original().taskCode())
					&& receipt.decision().equals(actual.status()) && receipt.id().equals(resolution.mesDecisionId())
					&& receipt.receiptFingerprint().equals(resolution.mesDecisionFingerprint());
			return R.ok(new MesPlanEngineeringDecisionDTO.Outcome(receipt, acknowledged,
					acknowledged ? "PLAN已确认同一MES永久终态" : "MES终态已保存，PLAN待沿原号确认"));
		} catch (Exception failure) {
			// 本地已提交，保留实际回执供原号恢复。不得因后续确认故障提示MES事实已回滚。
			return R.ok(new MesPlanEngineeringDecisionDTO.Outcome(receipt, false, "MES终态已保存，PLAN待沿原号确认"));
		}
	}
}
