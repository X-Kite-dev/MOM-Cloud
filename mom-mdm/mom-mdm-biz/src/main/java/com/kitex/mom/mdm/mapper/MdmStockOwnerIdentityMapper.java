package com.kitex.mom.mdm.mapper;

import com.kitex.mom.mdm.dto.MdmStockOwnerIdentityDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 最小货主投影查询；SQL显式限定可信租户、工厂和有效记录，不读取联系或账户字段。
 */
@Mapper
public interface MdmStockOwnerIdentityMapper {

	/**
	 * 按组织及编码读取最多两行用于去歧义，故意不接受预期主键作为过滤条件。
	 *
	 * @param tenantId    当前实名租户
	 * @param factoryCode 已核本人读权的实际工厂
	 * @param ownerType   服务已校验的外部货权类型
	 * @param ownerCode   原文精确货主编码
	 * @return 数值主键升序的最多两条最小投影
	 */
	List<MdmStockOwnerIdentityDTO> resolve(@Param("tenantId") Long tenantId,
	                                       @Param("factoryCode") String factoryCode,
	                                       @Param("ownerType") String ownerType,
	                                       @Param("ownerCode") String ownerCode);

	/**
	 * 用绑定参数搜索字面子串，固定最多20行，不接受客户端表名、排序或组织。
	 *
	 * @param tenantId    当前实名租户
	 * @param factoryCode 已核本人读权的实际工厂
	 * @param ownerType   服务已校验的外部货权类型
	 * @param keyword     已校验非空关键词
	 * @return 数值主键升序的最多20条最小投影
	 */
	List<MdmStockOwnerIdentityDTO> search(@Param("tenantId") Long tenantId,
	                                      @Param("factoryCode") String factoryCode,
	                                      @Param("ownerType") String ownerType,
	                                      @Param("keyword") String keyword);
}
