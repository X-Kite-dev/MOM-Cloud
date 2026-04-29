package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotDeviceDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;

import java.util.List;

/**
 * IoT设备服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotDeviceService extends IService<IotDeviceEntity> {

    /**
     * 分页查询设备列表
     *
     * @param page   分页参数
     * @param device 查询条件
     * @return 分页结果
     */
    IPage<IotDeviceEntity> getDevicePage(Page<IotDeviceEntity> page, IotDeviceEntity device);

    /**
     * 根据产品ID查询设备列表
     *
     * @param productId 产品ID
     * @return 设备列表
     */
    List<IotDeviceEntity> getDevicesByProductId(Long productId);

    /**
     * 根据设备编码查询设备
     *
     * @param deviceCode 设备编码
     * @return 设备信息
     */
    IotDeviceEntity getDeviceByCode(String deviceCode);

    /**
     * 创建设备
     *
     * @param deviceDTO 设备信息
     * @return 是否成功
     */
    boolean createDevice(IotDeviceDTO deviceDTO);

    /**
     * 批量创建设备
     *
     * @param deviceDTOList 设备信息列表
     * @return 是否成功
     */
    boolean batchCreateDevice(List<IotDeviceDTO> deviceDTOList);

    /**
     * 更新设备
     *
     * @param deviceDTO 设备信息
     * @return 是否成功
     */
    boolean updateDevice(IotDeviceDTO deviceDTO);

    /**
     * 删除设备
     *
     * @param id 设备ID
     * @return 是否成功
     */
    boolean removeDevice(Long id);

    /**
     * 更新设备状态
     *
     * @param deviceId 设备ID
     * @param status   状态
     * @return 是否成功
     */
    boolean updateStatus(Long deviceId, Integer status);

    /**
     * 生成设备秘钥
     *
     * @return 设备秘钥
     */
    String generateDeviceSecret();
} 