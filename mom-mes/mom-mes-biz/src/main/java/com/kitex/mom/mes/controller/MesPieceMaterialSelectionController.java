package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPieceMaterialSelectionDTO.*;
import com.kitex.mom.mes.service.impl.MesPieceMaterialSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 现场报工中的只读用料核对，与实际报工共用权限，不增加独立执行授权。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/report/material-selection")
@PreAuthorize("@pms.hasPermission('mesTaskReport:report')")
public class MesPieceMaterialSelectionController {
	private final MesPieceMaterialSelectionService service;

	@GetMapping
	public R<Preparation> prepare(@RequestParam String arrangeCode, @RequestParam String identifier) {
		return R.ok(service.prepare(arrangeCode, identifier));
	}

	@PostMapping("/check")
	public R<Checked> check(@RequestBody Input input) {
		return R.ok(service.check(input));
	}
}
