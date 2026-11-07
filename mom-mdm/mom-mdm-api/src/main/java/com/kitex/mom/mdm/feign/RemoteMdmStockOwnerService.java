package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmStockOwnerIdentityDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerLookupDTO;
import com.kitex.mom.mdm.dto.MdmStockOwnerSearchDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 保留当前人员原Bearer及实际租户、工厂上下文的最小货主查询，不使用内部免认证调用。
 */
@FeignClient(contextId = "remoteMdmStockOwnerService", value = Constants.Name)
public interface RemoteMdmStockOwnerService {

	/**
	 * 在当前真实工厂内先确认编码唯一，再校验可选预期主键；无权、缺失或歧义时拒绝。
	 *
	 * @param query 外部货权类型、精确编码与可选主键，不接收组织或人员声明
	 * @return 最小真实身份及启用、审核资格，不含联系方式
	 */
	@PostMapping("/mdmStockOwnerIdentity/resolve")
	R<MdmStockOwnerIdentityDTO> resolve(@RequestBody MdmStockOwnerLookupDTO query);

	/**
	 * 按非空关键词查询当前真实工厂内的最小候选，数值主键升序且最多20条。
	 *
	 * @param query 外部货权类型和编码或名称的字面子串关键词
	 * @return 最小候选；查询失败不解释为空结果
	 */
	@PostMapping("/mdmStockOwnerIdentity/search")
	R<List<MdmStockOwnerIdentityDTO>> search(@RequestBody MdmStockOwnerSearchDTO query);
}
