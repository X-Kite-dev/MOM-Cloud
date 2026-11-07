package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.QmsInspectionPieceFirstPassDTO.*;
import com.kitex.mom.qms.service.impl.QmsInspectionPieceFirstPassService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 已有检验读取许可下的单件原首轮资格和原FULL样本判定入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/pieces/first-pass")
public class QmsInspectionPieceFirstPassController {
	private final QmsInspectionPieceFirstPassService service;

	/**
	 * 在已有检验读取许可内查询单件原首轮资格和实测数量，未证来源不推定合格。
	 *
	 * @param taskId 当前实际工厂检验任务ID字符串
	 * @param pieceId 该任务原单件ID字符串
	 * @return 首轮资格、原来源证据与可空登记件数
	 */
	@GetMapping
	@PreAuthorize("@pms.hasPermission('procedureTask:show')")
	public R<View> inspect(@RequestParam String taskId, @RequestParam String pieceId) {
		return R.ok(service.inspect(taskId, pieceId));
	}
}
