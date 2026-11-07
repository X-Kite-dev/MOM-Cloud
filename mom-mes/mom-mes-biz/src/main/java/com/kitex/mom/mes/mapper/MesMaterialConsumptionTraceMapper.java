package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO.Query;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Permanent source tables only; every join is explicitly scoped to the authenticated organization.
 */
@Mapper
public interface MesMaterialConsumptionTraceMapper {
	long total(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("q") Query query);

	List<Map<String, Object>> page(@Param("tenant") Long tenant, @Param("factory") String factory,
	                               @Param("q") Query query, @Param("offset") long offset, @Param("limit") int limit);

	Map<String, Object> original(@Param("tenant") Long tenant, @Param("factory") String factory,
	                             @Param("q") Query query, @Param("kind") String kind, @Param("id") Long id);

	Map<String, Object> root(Map<String, Object> scope);

	Map<String, Object> rawSource(Map<String, Object> scope);

	Map<String, Object> feedOrStock(Map<String, Object> scope);

	/**
	 * 读取同组织、原NG报告、请求、分配与库存主键全部相符的真实OUT；缺失不借GOOD域补挂。
	 */
	Map<String, Object> nativePolicyOut(Map<String, Object> scope);

	/**
	 * 读取原分配恢复对应的真实IN；恢复主凭据、原请求、库存、物料及单位须精确一致。
	 */
	List<Map<String, Object>> nativePolicyRestores(Map<String, Object> scope);

	List<Map<String, Object>> attributions(Map<String, Object> scope);

	List<Map<String, Object>> restores(Map<String, Object> scope);

	List<Map<String, Object>> reversals(Map<String, Object> scope);

	List<Map<String, Object>> corrections(Map<String, Object> scope);
}
