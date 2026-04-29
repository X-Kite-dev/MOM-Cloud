package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.iot.entity.IotDeviceEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IoT设备事件数据Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotDeviceEventMapper extends BaseMapper<IotDeviceEventEntity> {

    /**
     * 根据设备ID查询事件数据
     *
     * @param deviceId 设备ID
     * @return 事件数据列表
     */
    List<IotDeviceEventEntity> selectByDeviceId(@Param("deviceId") Long deviceId);

    /**
     * 根据设备编码查询事件数据
     *
     * @param deviceCode 设备编码
     * @return 事件数据列表
     */
    List<IotDeviceEventEntity> selectByDeviceCode(@Param("deviceCode") String deviceCode);
} 