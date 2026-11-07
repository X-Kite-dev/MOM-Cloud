package com.kitex.mom.wms.controller.app;

import com.kitex.mom.wms.controller.special.WmsSpecialMovementEndpoints;
import com.kitex.mom.wms.service.special.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/app/specialMovement")
public class AppSpecialMovementController extends WmsSpecialMovementEndpoints {
	public AppSpecialMovementController(WmsSpecialMovementService service, WmsSpecialPolicyService policies,
	                                    WmsSpecialDirectoryService directory) {
		super(service, policies, directory);
	}

	@Override
	protected boolean mobile() {
		return true;
	}
}
