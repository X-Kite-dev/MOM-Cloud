package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 独立手工工单的原下达来源校验入口；资格仍由原手工业务服务负责，纯工程重建委托公共政策。
 */
@Component
@RequiredArgsConstructor
public class MesManualLaborIssuedSource {
	private final MesIssuedEngineeringSource issuedSource;

	/**
	 * 从原批准联合包校验合法下达投影；不修改原工程，也不放宽独立手工来源资格。
	 */
	public void validate(MesTaskEntity task) {
		issuedSource.validate(task);
	}
}
