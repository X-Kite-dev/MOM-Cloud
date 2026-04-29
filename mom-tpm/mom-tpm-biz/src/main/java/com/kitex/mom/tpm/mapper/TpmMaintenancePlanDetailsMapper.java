package com.kitex.mom.tpm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanDetailsEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TpmMaintenancePlanDetailsMapper extends MomBaseMapper<TpmMaintenancePlanDetailsEntity> {


    /**
     * 根据计划id获取保养明细
     *
     * @param planId 计划ID
     */
    List<TpmMaintenancePlanDetailsEntity> getInfoList(@Param("planId") Long planId);

    /**
     * 查询所有保养项明细
     */
    List<TpmMaintenancePlanDetailsEntity> listAllInfos();
}