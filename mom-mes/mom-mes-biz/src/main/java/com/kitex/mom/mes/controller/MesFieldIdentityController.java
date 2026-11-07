package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesFieldIdentityDTO;
import com.kitex.mom.mes.service.impl.MesFieldIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 现场附加认证入口，沿用原交接动作权限，不提供匿名刷卡登录。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/field-identity/handover")
public class MesFieldIdentityController {
	private final MesFieldIdentityService service;

	/**
	 * 为实际本人原命令生成短期挑战；动作权限与既有交接入口相同。
	 */
	@PostMapping("/challenge")
	@PreAuthorize("@pms.hasPermission(#command.action == 'SUBMIT' || #command.action == 'WITHDRAW' ? 'mesShiftHandover:submit' : 'mesShiftHandover:receive')")
	public R<?> challenge(@RequestBody MesFieldIdentityDTO.ChallengeCommand command) {
		return R.ok(service.challenge(command));
	}

	/**
	 * 认证受理后复用原交接服务，返回真实业务回执，不把认证状态当完成。
	 */
	@PostMapping("/authenticate")
	@PreAuthorize("@pms.hasPermission(#command.action == 'SUBMIT' || #command.action == 'WITHDRAW' ? 'mesShiftHandover:submit' : 'mesShiftHandover:receive')")
	public R<?> authenticate(@RequestBody MesFieldIdentityDTO.AuthenticateCommand command) {
		return R.ok(service.authenticate(command));
	}
}
