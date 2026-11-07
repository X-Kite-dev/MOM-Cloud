package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmStockOwnerIdentityDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerLookupDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerSearchDTO;
import com.kitex.mom.mdm.service.MdmStockOwnerIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 普通实名认证的货主最小身份入口；实际工厂本人读权由服务逐次核验，不授予全局MDM查看权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmStockOwnerIdentity")
@PreAuthorize("isAuthenticated()")
public class MdmStockOwnerIdentityController {
	private final MdmStockOwnerIdentityService service;

	/**
	 * 精确核对货主编码唯一性与可选主键；停用或未审核仍返回真实资格状态。
	 *
	 * @param query 不含客户端组织或人员的精确查询
	 * @return 当前实际工厂内唯一货主的最小身份
	 */
	@PostMapping("/resolve")
	public R<MdmStockOwnerIdentityDTO> resolve(@RequestBody MdmStockOwnerLookupDTO query) {
		return R.ok(service.resolve(query));
	}

	/**
	 * 在本人有读权的实际工厂搜索候选；空关键词或越界输入拒绝。
	 *
	 * @param query 货权类型与非空关键词
	 * @return 最多20个最小身份，按数值主键升序
	 */
	@PostMapping("/search")
	public R<List<MdmStockOwnerIdentityDTO>> search(@RequestBody MdmStockOwnerSearchDTO query) {
		return R.ok(service.search(query));
	}
}
