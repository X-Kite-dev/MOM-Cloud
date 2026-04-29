package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IoT设备Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotDeviceMapper extends BaseMapper<IotDeviceEntity> {

    /**
     * 根据产品ID查询设备列表
     *
     * @param productId 产品ID
     * @return 设备列表
     */
    List<IotDeviceEntity> selectByProductId(@Param("productId") Long productId);

    /**
     * 更新设备状态
     *
     * @param deviceId 设备ID
     * @param status   状态
     * @return 影响行数
     */
    int updateStatus(@Param("deviceId") Long deviceId, @Param("status") Integer status);
} 