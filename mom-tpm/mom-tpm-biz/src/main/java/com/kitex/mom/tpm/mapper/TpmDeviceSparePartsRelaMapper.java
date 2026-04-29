package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmDeviceSparePartsRelaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TpmDeviceSparePartsRelaMapper extends MomBaseMapper<TpmDeviceSparePartsRelaEntity> {


    Page<TpmDeviceSparePartsRelaEntity> getTpmDeviceSparePartsRelaPage(@Param("page") Page page,
                                                                       @Param("query") TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela);

    TpmDeviceSparePartsRelaEntity getRelaById(@Param("id") Long id);
}