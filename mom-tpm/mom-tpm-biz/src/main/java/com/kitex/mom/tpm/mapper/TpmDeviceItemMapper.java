package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TpmDeviceItemMapper extends MomBaseMapper<TpmDeviceItemEntity> {

    Page<TpmDeviceItemEntity> pageList(Page toMpPage, @Param("query") TpmDeviceItemEntity tpmDeviceItem);

    List<TpmDeviceItemEntity> getDeviceItemByDeviceCode(@Param("deviceCode") String deviceCode,
                                                        @Param("type") String type);
}