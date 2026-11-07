package com.kitex.mom.qms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO;
import com.kitex.mom.qms.agent.service.QmsRootCauseImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已保存质量假设的人工引用入口；原QMS负责接受，查询和取消不调用AI。 */
@RestController
@RequestMapping("/agent/root-cause")
@RequiredArgsConstructor
public class QmsRootCauseImportController {

	private final QmsRootCauseImportService service;

	/** 原工单同源CAPA真实分页，不创建案件。 */
	@GetMapping("/candidates")
	@PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('capa:analyze')")
	public R<?> candidates(@RequestParam String runId, @RequestParam String analysisArtifactId,
			@RequestParam String analysisContentHash, @RequestParam String hypothesisId,
			@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) String capaNo) {
		return R.ok(service.candidates(new QmsRootCauseImportDTO.Source(runId, analysisArtifactId,
				analysisContentHash, hypothesisId), current, size, capaNo));
	}

	/** 提出原QMS待验证根因，不执行验证或阶段推进。 */
	@PostMapping("/import")
	@PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('capa:analyze')")
	public R<?> importCause(@Valid @RequestBody QmsRootCauseImportDTO.Command command) {
		return R.ok(service.importCause(command));
	}

	/** 原人原组织只读核账，无记录返回null，不能推断原请求未发送。 */
	@GetMapping("/request-receipt")
	@PreAuthorize("@pms.hasPermission('capa:show')")
	public R<?> requestReceipt(@RequestParam String requestId) {
		return R.ok(service.requestReceipt(requestId));
	}

	/** 原完整命令永久取消未受理原号；已受理根因仍返回原回执。 */
	@PostMapping("/cancel")
	@PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('capa:analyze')")
	public R<?> cancel(@Valid @RequestBody QmsRootCauseImportDTO.Command command) {
		return R.ok(service.cancel(command));
	}

	/** 精确案件根因的本地冻结来源；普通人工根因返回null。 */
	@GetMapping("/source")
	@PreAuthorize("@pms.hasPermission('capa:show')")
	public R<?> source(@RequestParam String causeId, @RequestParam String capaId) {
		return R.ok(service.source(causeId, capaId));
	}
}
