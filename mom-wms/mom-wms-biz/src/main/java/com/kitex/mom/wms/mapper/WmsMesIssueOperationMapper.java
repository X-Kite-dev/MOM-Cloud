package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsMesIssueOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsMesIssueOperationMapper extends MomBaseMapper<WmsMesIssueOperationEntity> {
	/**
	 * 与现有租户+原号唯一索引保持一致，不能按工厂拆分请求锁。
	 */
	@Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_MES_ISSUE_COMMAND:', #{tenantId}, ':', #{requestId}), 0))")
	int lockRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
}

