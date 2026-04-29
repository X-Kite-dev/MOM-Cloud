package com.kitex.mom.mdm.feign;

import com.kitex.mom.mdm.Constants;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(contextId = "remoteWorkCenterService", value = Constants.Name)
public interface RemoteWorkCenterService {
//
//	/**
//	 * 分页查询工作中心信息
//	 * @param current 当前页码
//	 * @param size 每页大小
//	 * @param mdmWorkCenter 工作中心实体
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkCenter/page")
//	R<Page<MdmWorkCenterEntity>> getMdmWorkCenterPage(@RequestParam("current") Integer current,
//			@RequestParam("size") Integer size, @SpringQueryMap MdmWorkCenterEntity mdmWorkCenter);
//
//	/**
//	 * 获取工作中心列表
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkCenter/list")
//	R<List<MdmWorkCenterEntity>> getList();
//
//	/**
//	 * 新增工作中心信息
//	 * @param mdmWorkCenter 工作中心实体
//	 * @return R
//	 */
//	@PostMapping("/mdmWorkCenter")
//	R<Boolean> save(@RequestBody MdmWorkCenterEntity mdmWorkCenter);
//
//	/**
//	 * 更新工作中心信息
//	 * @param mdmWorkCenter 工作中心实体
//	 * @return R
//	 */
//	@PutMapping("/mdmWorkCenter")
//	R<Boolean> updateById(@RequestBody MdmWorkCenterEntity mdmWorkCenter);
//
//	/**
//	 * 删除工作中心信息
//	 * @param ids 工作中心 ID数组
//	 * @return R
//	 */
//	@DeleteMapping("/mdmWorkCenter")
//	R<Boolean> removeById(@RequestBody Long[] ids);
//
//	/**
//	 * 查询工作中心详情
//	 * @param id 工作中心 ID
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkCenter/{id}")
//	R<MdmWorkCenterEntity> getDetails(@PathVariable Long id);
//
//	/**
//	 * 查询工作中心详情
//	 * @param code 工作中心 ID
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkCenter/details")
//	R<MdmWorkCenterEntity> detailsByCode(@RequestParam String code);

}
