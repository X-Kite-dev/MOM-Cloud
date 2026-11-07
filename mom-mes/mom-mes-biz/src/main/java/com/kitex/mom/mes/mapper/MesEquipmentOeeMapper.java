package com.kitex.mom.mes.mapper;

import com.kitex.mom.mes.entity.MesEquipmentOeeBatchEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 只读原件及受控插入映射；每条业务查询显式限定实际租户和工厂。
 */
@Mapper
public interface MesEquipmentOeeMapper {
	/**
	 * 按实际租户/工厂/批次号精确读取来源；缺失返回空，不跨组织找首条。
	 */
	@Select("SELECT * FROM mom_mes.mes_equipment_oee_batch WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND batch_id=#{batchId}")
	MesEquipmentOeeBatchEntity find(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                @Param("batchId") String batchId);

	/**
	 * 插入原始来源并保留永久同号唯一性；调用方必须核影响行数及首次回读。
	 */
	@Insert("INSERT INTO mom_mes.mes_equipment_oee_batch(tenant_id,factory_code,batch_id,device_code,source_kind,"
			+ "period_start,period_end,source_json,fingerprint,actor_id,accepted_at) VALUES(#{tenantId},#{factoryCode},"
			+
			"#{batchId},#{deviceCode},#{sourceKind},#{periodStart},#{periodEnd},#{sourceJson},#{fingerprint},#{actorId},"
			+ "#{acceptedAt}) ON CONFLICT DO NOTHING")
	int insert(MesEquipmentOeeBatchEntity row);

	/**
	 * 返回同一实际组织与设备条件的目录总数。
	 */
	@Select("<script>SELECT COUNT(*) FROM mom_mes.mes_equipment_oee_batch WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
			+ "<if test='device != null'> AND device_code=#{device}</if></script>")
	long count(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("device") String device);

	/**
	 * 按实际组织、设备条件和有界分页读取目录，不省略组织过滤。
	 */
	@Select("<script>SELECT * FROM mom_mes.mes_equipment_oee_batch WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
			+ "<if test='device != null'> AND device_code=#{device}</if>"
			+ " ORDER BY accepted_at DESC,batch_id DESC LIMIT #{size} OFFSET #{offset}</script>")
	List<MesEquipmentOeeBatchEntity> page(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                      @Param("device") String device, @Param("size") int size,
	                                      @Param("offset") long offset);
}
