package com.kitex.mom.wms.service.bill;

import com.kitex.common.core.constant.ServiceNameConstants;
import com.kitex.common.core.util.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * WMS按钮来源的窄只读证明接口，正常传递当前实名Bearer及租户，不使用内部调用标记代替身份。
 * 不依赖共享Admin API版本，不接收外部人员、角色或菜单ID。
 */
@FeignClient(contextId = "wmsBillPermissionProof", value = ServiceNameConstants.ADMIN_SERVICE)
public interface WmsBillPermissionProofClient {

	/**
	 * 按输入顺序证明当前人员获授菜单的真实来源，单批不超过1000项。
	 *
	 * @param items 固定入口生成的族、动作、实际仓及工厂、精确权限码
	 * @return 当前真实租户、人员及与输入等长的证明结果
	 */
	@PostMapping("/menu/wmsBillPermissionProof")
	R<Proof> prove(@RequestBody List<Item> items);

	/**
	 * @param family        warehouse表示通用仓库按钮，sales/produce表示固定业务族
	 * @param action        固定单据动作词
	 * @param warehouseCode 已核实际仓库编码
	 * @param factoryCode   当前已授权工厂编码
	 * @param permission    当前人员实际具有的精确权限码，须由菜单祖先证明来源
	 */
	record Item(String family, String action, String warehouseCode, String factoryCode, String permission) {
	}

	/**
	 * @param tenantId 服务端实际认证租户
	 * @param userId   服务端实际认证人员主键
	 * @param allowed  按输入顺序返回的证明结果，不能有null或缺项
	 */
	record Proof(Long tenantId, Long userId, List<Boolean> allowed) {
	}
}
