package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDeviceGroupRelationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * IoT设备分组关联Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotDeviceGroupRelationMapper extends BaseMapper<IotDeviceGroupRelationEntity> {

    /**
     * 分页查询分组下的设备
     *
     * @param page    分页参数
     * @param groupId 分组ID
     * @param device  查询条件
     * @return 设备列表
     */
    IPage<IotDeviceEntity> selectDevicesByGroupId(Page<IotDeviceEntity> page, @Param("groupId") Long groupId, @Param("device") IotDeviceEntity device);

} 