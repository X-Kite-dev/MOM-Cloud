package com.kitex.mom.mdm.feign;

import com.kitex.mom.mdm.Constants;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(contextId = "remoteWorkshopService", value = Constants.Name)
public interface RemoteWorkshopService {
//
//	/**
//	 * 分页查询车间信息
//	 * @param current 当前页码
//	 * @param size 每页大小
//	 * @param mdmWorkshop 车间实体
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkshop/page")
//	R<Page<MdmWorkshopEntity>> getMdmWorkshopPage(@RequestParam("current") Integer current,
//			@RequestParam("size") Integer size, @SpringQueryMap MdmWorkshopEntity mdmWorkshop);
//
//	/**
//	 * 获取车间列表
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkshop/list")
//	R<List<MdmWorkshopEntity>> getList();
//
//	/**
//	 * 新增车间信息
//	 * @param mdmWorkshop 车间实体
//	 * @return R
//	 */
//	@PostMapping("/mdmWorkshop")
//	R<Boolean> save(@RequestBody MdmWorkshopEntity mdmWorkshop);
//
//	/**
//	 * 更新车间信息
//	 * @param mdmWorkshop 车间实体
//	 * @return R
//	 */
//	@PutMapping("/mdmWorkshop")
//	R<Boolean> updateById(@RequestBody MdmWorkshopEntity mdmWorkshop);
//
//	/**
//	 * 删除车间信息
//	 * @param ids 车间 ID数组
//	 * @return R
//	 */
//	@DeleteMapping("/mdmWorkshop")
//	R<Boolean> removeById(@RequestBody Long[] ids);
//
//	/**
//	 * 查询车间详情
//	 * @param id 车间 ID
//	 * @return R
//	 */
//	@GetMapping("/mdmWorkshop/{id}")
//	R<MdmWorkshopEntity> getDetails(@PathVariable Long id);

}