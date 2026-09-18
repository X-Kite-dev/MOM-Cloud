package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.entity.IotDeviceEventEntity;

import java.util.List;
import java.util.Map;

/**
 * IoT设备事件数据服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotDeviceEventService extends IService<IotDeviceEventEntity> {
    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceEventEntity> scoped(Long id,String code,String permission);
    IotDeviceEventEntity authorizedEvent(Long id,String permission);

    /**
     * 分页查询设备事件数据
     *
     * @param page  分页参数
     * @param event 查询条件
     * @return 分页结果
     */
    IPage<IotDeviceEventEntity> getDeviceEventPage(Page<IotDeviceEventEntity> page, IotDeviceEventEntity event);

    /**
     * 保存设备事件数据
     *
     * @param event 事件数据
     * @return 操作结果
     */
    Boolean saveDeviceEvent(IotDeviceEventEntity event);

    /**
     * 批量保存设备事件数据
     *
     * @param events 事件数据列表
     * @return 操作结果
     */
    Boolean batchSaveDeviceEvent(List<IotDeviceEventEntity> events);

    /**
     * 根据设备ID查询最近的事件数据
     *
     * @param deviceId 设备ID
     * @param limit    限制数量
     * @return 事件数据列表
     */
    List<IotDeviceEventEntity> getRecentEventsByDeviceId(Long deviceId, Integer limit);

    /**
     * 根据设备编码查询最近的事件数据
     *
     * @param deviceCode 设备编码
     * @param limit      限制数量
     * @return 事件数据列表
     */
    List<IotDeviceEventEntity> getRecentEventsByDeviceCode(String deviceCode, Integer limit);

    /**
     * 根据设备ID查询事件数据
     *
     * @param deviceId 设备ID
     * @return 事件数据列表
     */
    List<IotDeviceEventEntity> getByDeviceId(Long deviceId);

    /**
     * 根据设备编码查询事件数据
     *
     * @param deviceCode 设备编码
     * @return 事件数据列表
     */
    List<IotDeviceEventEntity> getByDeviceCode(String deviceCode);

    /**
     * 保存设备事件数据
     *
     * @param deviceId    设备ID
     * @param deviceCode  设备编码
     * @param productId   产品ID
     * @param productCode 产品编码
     * @param identifier  事件标识符
     * @param name        事件名称
     * @param eventType   事件类型
     * @param eventData   事件数据
     * @param factoryCode 工厂编码
     * @return 是否成功
     */
    boolean saveEvent(Long deviceId, String deviceCode, Long productId, String productCode,
                      String identifier, String name, String eventType, Map<String, Object> eventData, String factoryCode);

    /** 对已持久化但转发失败/忽略的生产产量事件进行幂等补偿。 */
    IotDeviceEventEntity retryMesReportForward(Long eventId, com.kitex.mom.iot.dto.IotMesRetryCommand command);
}
