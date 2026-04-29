package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.iot.entity.IotDeviceGroupEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IoT设备分组Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotDeviceGroupMapper extends BaseMapper<IotDeviceGroupEntity> {

    /**
     * 查询分组下的设备数量
     *
     * @param groupId 分组ID
     * @return 设备数量
     */
    Integer countDevicesByGroupId(@Param("groupId") Long groupId);

    /**
     * 查询分组树
     *
     * @param parentId 父分组ID
     * @return 分组树
     */
    List<IotDeviceGroupEntity> selectGroupTree(@Param("parentId") Long parentId);
} 