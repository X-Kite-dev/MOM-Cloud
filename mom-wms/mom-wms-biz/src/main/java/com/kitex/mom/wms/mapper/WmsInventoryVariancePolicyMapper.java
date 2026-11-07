package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 差异规则按租户、工厂和仓库隔离；复盘只读取原计划明细的最新记录。
 */
@Mapper
public interface WmsInventoryVariancePolicyMapper {
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT mode, quantity_threshold AS quantityThreshold, percent_threshold AS percentThreshold, version "
			+ "FROM mom_wms.wms_inventory_variance_policy WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND warehouse_code=#{warehouse}")
	Row find(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_inventory_variance_policy AS policy "
			+ "(tenant_id,factory_code,warehouse_code,mode,quantity_threshold,percent_threshold,version,updated_by) "
			+ "VALUES(#{tenant},#{factory},#{warehouse},#{mode},#{quantity},#{percent},1,#{actor}) "
			+ "ON CONFLICT(tenant_id,factory_code,warehouse_code) DO UPDATE SET mode=EXCLUDED.mode, "
			+ "quantity_threshold=EXCLUDED.quantity_threshold, percent_threshold=EXCLUDED.percent_threshold, "
			+ "version=policy.version+1,updated_at=now(),updated_by=EXCLUDED.updated_by "
			+ "WHERE policy.version=#{expectedVersion}")
	int upsert(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("warehouse") String warehouse,
	           @Param("mode") String mode, @Param("quantity") BigDecimal quantity, @Param("percent") BigDecimal percent,
	           @Param("expectedVersion") int expectedVersion, @Param("actor") String actor);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT id,tenant_id,factory_code,plan_id,plan_detail_id,request_id,previous_num,recount_num,reason, "
			+ "operator_code,operated_time,owner_count_json FROM mom_wms.wms_inventory_recount_record "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND plan_id=#{planId} "
			+ "AND plan_detail_id=#{detailId} AND del_flag='0' ORDER BY id DESC LIMIT 1")
	WmsInventoryRecountRecordEntity latestRecount(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                              @Param("planId") Long planId, @Param("detailId") Long detailId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT id,tenant_id,factory_code,plan_id,plan_detail_id,request_id,previous_num,recount_num,reason, "
			+ "operator_code,operated_time,owner_count_json FROM mom_wms.wms_inventory_recount_record "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND plan_detail_id=#{detailId} "
			+ "AND id=#{recordId} AND del_flag='0'")
	WmsInventoryRecountRecordEntity recountById(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                            @Param("detailId") Long detailId, @Param("recordId") Long recordId);

	record Row(String mode, BigDecimal quantityThreshold, BigDecimal percentThreshold, int version) {
	}
}
