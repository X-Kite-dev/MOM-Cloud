package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.controller.special.WmsSpecialMovementEndpoints;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wmsSpecialMovement")
public class WmsSpecialMovementController extends WmsSpecialMovementEndpoints {
	public WmsSpecialMovementController(WmsSpecialMovementService service, WmsSpecialPolicyService policies,
	                                    WmsSpecialDirectoryService directory) {
		super(service, policies, directory);
	}

	@Override
	protected boolean mobile() {
		return false;
	}

	@PutMapping("/policy")
	@SysLog("维护WMS特殊收发规则")
	public R<PolicySnapshot> savePolicy(@RequestBody PolicySave command) {
		return R.ok(policies.save(command));
	}
}
