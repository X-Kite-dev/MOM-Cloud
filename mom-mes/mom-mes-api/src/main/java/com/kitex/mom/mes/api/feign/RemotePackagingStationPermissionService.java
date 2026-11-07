package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 包装工位命令复用MDM已有的精确节点授权查询；人员主键只来自认证身份。
 * 不授予权限，不把工厂读写授权推断成全部工位读写授权。
 */
@FeignClient(value = Constants.Name, contextId = "remotePackagingStationPermissionService")
public interface RemotePackagingStationPermissionService {

	/**
	 * @param userId 当前已认证人员主键
	 * @param nodeId 已核对组织、编码和启用状态的真实工位主键
	 * @param permission 原授权级别write或admin，旧接口按精确级别查询
	 * @return 当前节点该级别的有效绑定是否存在
	 */
	@GetMapping("/mdmUserFactoryModelBind/hasPermission/{userId}/{nodeId}/{permission}")
	R<Boolean> hasPermission(@PathVariable("userId") Long userId, @PathVariable("nodeId") Long nodeId,
			@PathVariable("permission") String permission);
}
