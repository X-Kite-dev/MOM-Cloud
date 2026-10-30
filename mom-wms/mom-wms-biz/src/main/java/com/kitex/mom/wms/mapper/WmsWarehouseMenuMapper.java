package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsWarehouseMenuMapper extends MomBaseMapper<WmsWarehouseMenuEntity> {

	/**
	 * 同租户、工厂的保存与发布共用事务锁，也覆盖尚无配置记录的首次保存。
	 */
	@Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
	int lockConfiguration(@Param("key") String key);

}
