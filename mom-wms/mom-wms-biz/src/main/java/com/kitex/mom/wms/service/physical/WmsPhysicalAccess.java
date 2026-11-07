package com.kitex.mom.wms.service.physical;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.wms.service.WmsMobileWarehouseAccess;
import com.kitex.mom.wms.service.bill.WmsBillPermissionProofs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.kitex.mom.wms.service.physical.WmsPhysicalRules.*;

/**
 * 实名MDM工厂与真实quality仓菜单双层授权，不缓存跨请求结果。
 */
@Component
@RequiredArgsConstructor
public class WmsPhysicalAccess {
	private final WmsMobileWarehouseAccess factories;
	private final RemoteMdmFactoryAccessService mdm;
	private final WmsBillPermissionProofs permissions;

	/**
	 * 核原人员Bearer及真实MDM工厂读取权限。
	 */
	public void read() {
		bearer();
		factories.requireFactory(false);
	}

	/**
	 * 核原人员Bearer及真实MDM工厂作业权限，内部标记不绕过。
	 */
	public void write() {
		bearer();
		factories.requireFactory(true);
	}

	private void bearer() {
		require(SecurityUtils.getToken() != null && !SecurityUtils.getToken().isBlank(),
				"实物办理必须透传当前实名Bearer");
	}

	/**
	 * 明确MDM拒绝是只读；故障、缺data不能冒充正常只读结果。
	 */
	public boolean canWrite() {
		var r = mdm.check(new MdmFactoryAccessCheck().setUserId(SecurityUtils.getUser().getId())
				.setFactoryCode(FactoryContextHolder.getFactoryCode()).setRequiredPermission("write"));
		require(r != null && r.isOk() && r.getData() != null, "工厂写权限核验失败");
		return Boolean.TRUE.equals(r.getData());
	}

	/**
	 * 批量预热指定动作的真实仓quality菜单来源，返回明确通过的仓集合。
	 */
	public List<String> proved(Collection<String> warehouses, String action) {
		String p = "wmsQualityStatus:" + action;
		permissions.prefetch(
				warehouses.stream().map(w -> new WmsBillPermissionProofs.Check("quality", action, w, p))
						.toList());
		return warehouses.stream().filter(w -> permissions.hasProof("quality", action, w, p)).toList();
	}

	/**
	 * 完整原仓非空且逐仓动作均通过才允许；远端故障直接抛错。
	 */
	public boolean allowed(Collection<String> warehouses, String action) {
		return !warehouses.isEmpty() && proved(warehouses, action).size() == warehouses.size();
	}

	/**
	 * 要求完整原仓集合拥有当前动作来源，缺任何一仓即拒绝整笔。
	 */
	public void requireWarehouses(Collection<String> warehouses, String action) {
		require(allowed(warehouses, action), "当前账号没有全部原仓质量" + action + "权限");
	}
}
