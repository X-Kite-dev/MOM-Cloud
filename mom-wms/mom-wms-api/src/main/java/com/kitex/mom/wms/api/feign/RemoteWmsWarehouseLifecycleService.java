package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** 仓库生命周期查询与配置清理；失败不得视作无依赖或清理成功。 */
@FeignClient(contextId = "remoteWmsWarehouseLifecycleService", value = Constants.Name)
public interface RemoteWmsWarehouseLifecycleService {

	@InternalCall
	@GetMapping("/wmsWarehouseLifecycle/references")
	R<List<String>> references(@RequestParam("warehouseCode") String warehouseCode);

	/** 仅业务及历史依赖；配置和预览由受控清理入口处理。 */
	@InternalCall
	@GetMapping("/wmsWarehouseLifecycle/businessReferences")
	R<List<String>> businessReferences(@RequestParam("warehouseCode") String warehouseCode);

	@InternalCall
	@PostMapping("/wmsWarehouseLifecycle/cleanup")
	R<Boolean> cleanup(@RequestParam("warehouseCode") String warehouseCode);
}
