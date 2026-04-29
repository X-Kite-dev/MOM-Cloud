package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * IoT设备属性数据服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotDevicePropertyService extends IService<IotDevicePropertyEntity> {

    /**
     * 分页查询设备属性数据列表
     *
     * @param page     分页参数
     * @param property 查询条件
     * @return 分页结果
     */
    IPage<IotDevicePropertyEntity> getDevicePropertyPage(Page<IotDevicePropertyEntity> page, IotDevicePropertyEntity property);

    /**
     * 根据设备ID查询最新的属性数据
     *
     * @param deviceId 设备ID
     * @return 属性数据列表
     */
    List<IotDevicePropertyEntity> getLatestPropertiesByDeviceId(Long deviceId);

    /**
     * 根据设备ID和属性标识符查询最新的属性数据
     *
     * @param deviceId   设备ID
     * @param identifier 属性标识符
     * @return 属性数据
     */
    IotDevicePropertyEntity getLatestPropertyByDeviceIdAndIdentifier(Long deviceId, String identifier);

    /**
     * 根据设备编码查询最新的属性数据
     *
     * @param deviceCode 设备编码
     * @return 属性数据列表
     */
    List<IotDevicePropertyEntity> getLatestPropertiesByDeviceCode(String deviceCode);

    /**
     * 根据设备id 和属性查询历史记录
     *
     * @param deviceId
     * @param identifier
     * @return
     */
    List<IotDevicePropertyEntity> getPropertyHistory(Long deviceId, String identifier);

    /**
     * 获取设备属性一个月内的历史数据用于图表展示
     *
     * @param deviceId   设备ID
     * @param identifier 属性标识符
     * @return 图表数据，包含时间和值
     */
    Map<String, Object> getPropertyChartData(Long deviceId, String identifier);

    /**
     * 获取设备属性历史数据用于图表展示
     *
     * @param deviceId   设备ID
     * @param identifier 属性标识符
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 图表数据，包含时间和值
     */
    Map<String, Object> getPropertyChartData(Long deviceId, String identifier, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 更新设备属性
     *
     * @param deviceId   设备ID
     * @param properties 属性值映射
     * @return 是否更新成功
     */
    boolean updateDeviceProperties(String deviceId, Map<String, Object> properties);

    /**
     * 获取设备属性
     *
     * @param productId 产品ID
     * @param deviceId  设备ID
     * @return 设备属性映射
     */
    Map<String, Object> getDeviceProperties(String productId, String deviceId);
}