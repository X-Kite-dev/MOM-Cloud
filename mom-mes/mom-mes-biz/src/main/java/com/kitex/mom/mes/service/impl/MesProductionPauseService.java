package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesProductionPauseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 暂停原因只读分析，复用原生产时间真实组织授权和完整来源合同。
 */
@Service
@RequiredArgsConstructor
public class MesProductionPauseService {

	private final MesProductionTimeService sources;

	/**
	 * 在同一可重复读事务内核原范围、观察时刻与全部来源，再做原因下钻。
	 * 原因键只接受原64位指纹；不把查询失败、缺依据或旧服务返回当作零暂停。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MesProductionPauseDTO.View analyze(MesProductionPauseDTO.Query query) {
		String key = query == null ? null : query.getPauseReasonKey();
		if (key != null && !key.matches("[0-9a-f]{64}")) {
			throw new CheckedException("请使用原暂停原因键查看来源");
		}
		return new MesProductionPauseCalculator().calculate(sources.capture(query), key);
	}
}
