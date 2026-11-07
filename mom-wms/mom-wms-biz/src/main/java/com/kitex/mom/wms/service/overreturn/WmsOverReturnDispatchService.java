package com.kitex.mom.wms.service.overreturn;

import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;
import com.kitex.mom.wms.service.WmsTransferVarianceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * 派出事务入口，协议裁决与原差异处置职责分离。
 */
@Service
@RequiredArgsConstructor
public class WmsOverReturnDispatchService {
	private final WmsOverReturnDispatchProtocol protocol;
	private final WmsTransferVarianceService variance;

	/**
	 * 同原号同快照优先回原终态，首次派出仅执行一次原处置。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public DispatchResult execute(DispatchCommand command, String action) {
		var result = protocol.prepare(command, action);
		if (!"dispatch".equals(action) || !"NOT_FOUND".equals(result.outcome())) {
			return result;
		}
		variance.dispatchReturn(command);
		return protocol.prepare(command, "lookup");
	}
}
