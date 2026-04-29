package com.kitex.mom.iot.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.dto.IotSceneDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotSceneEntity;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;
import com.kitex.mom.iot.mapper.IotSceneMapper;
import com.kitex.mom.iot.service.IotDeviceService;
import com.kitex.mom.iot.service.IotSceneHistoryService;
import com.kitex.mom.iot.service.IotSceneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景联动Service实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotSceneServiceImpl extends ServiceImpl<IotSceneMapper, IotSceneEntity> implements IotSceneService {

    private final IotDeviceService deviceService;
    private final IotSceneHistoryService sceneHistoryService;

    @Override
    public IPage<IotSceneEntity> getScenePage(Page<IotSceneEntity> page, IotSceneEntity query) {
        return baseMapper.selectScenePage(page, query);
    }

    @Override
    public IotSceneDTO getSceneDetail(Long id) {
        IotSceneEntity entity = this.getById(id);
        if (entity == null) {
            return null;
        }
        IotSceneDTO dto = new IotSceneDTO();
        BeanUtil.copyProperties(entity, dto);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addScene(IotSceneDTO dto) {
        IotSceneEntity entity = new IotSceneEntity();
        BeanUtil.copyProperties(dto, entity);
        // 设置默认启用状态
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        return this.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateScene(IotSceneDTO dto) {
        IotSceneEntity entity = new IotSceneEntity();
        BeanUtil.copyProperties(dto, entity);
        return this.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSceneByIds(List<Long> ids) {
        if (CollectionUtil.isEmpty(ids)) {
            return false;
        }
        return this.removeByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSceneStatus(Long id, Integer status) {
        IotSceneEntity entity = new IotSceneEntity();
        entity.setId(id);
        entity.setStatus(status);
        return this.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean executeScene(Long id) {
        // 记录执行历史
        IotSceneHistoryEntity historyEntity = new IotSceneHistoryEntity();
        try {
            // 获取场景信息
            IotSceneEntity sceneEntity = this.getById(id);
            if (sceneEntity == null) {
                log.error("执行场景失败：场景不存在，sceneId={}", id);
                return false;
            }

            historyEntity.setSceneName(sceneEntity.getName());
            historyEntity.setTriggerType(sceneEntity.getTriggerType());

            // 执行动作
            if (CollectionUtil.isEmpty(sceneEntity.getActions())) {
                log.warn("场景没有配置执行动作，sceneId={}", id);
                historyEntity.setStatus(1);
                historyEntity.setResult(new HashMap<>());
                sceneHistoryService.addHistory(historyEntity);
                return true;
            }

            Map<String, Object> result = new HashMap<>();
            // 遍历执行动作
            for (Map<String, Object> action : sceneEntity.getActions()) {
                String actionType = (String) action.get("type");
                if (StrUtil.isBlank(actionType)) {
                    continue;
                }

                // 根据动作类型执行相应的操作
                switch (actionType) {
                    case "alarm":
                        // 触发告警
                        executeAlarmAction(action, result);
                        break;
                    case "clearAlarm":
                        // 解除告警
                        executeClearAlarmAction(action, result);
                        break;
                    case "notification":
                        // 消息通知
                        executeNotificationAction(action, result);
                        break;
                    case "deviceOutput":
                        // 设备输出
                        executeDeviceOutputAction(action, result);
                        break;
                    default:
                        log.warn("未知的动作类型：{}", actionType);
                        break;
                }
            }

            historyEntity.setStatus(1);
            historyEntity.setResult(result);
            sceneHistoryService.addHistory(historyEntity);
            return true;
        } catch (Exception e) {
            log.error("执行场景异常，sceneId={}，error={}", id, e.getMessage(), e);
            // 记录执行失败的历史
            historyEntity.setStatus(0);
            historyEntity.setErrorMessage(e.getMessage());
            sceneHistoryService.addHistory(historyEntity);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveSceneDesign(IotSceneDTO dto) {
        if (dto.getId() == null) {
            // 新增场景
            return addScene(dto);
        } else {
            // 更新场景
            return updateScene(dto);
        }
    }

    /**
     * 执行触发告警动作
     *
     * @param action 动作配置
     * @param result 执行结果
     */
    private void executeAlarmAction(Map<String, Object> action, Map<String, Object> result) {
        // TODO: 实现告警功能
        log.info("触发告警：{}", action);
        result.put("alarm", "触发告警成功");
    }

    /**
     * 执行解除告警动作
     *
     * @param action 动作配置
     * @param result 执行结果
     */
    private void executeClearAlarmAction(Map<String, Object> action, Map<String, Object> result) {
        // TODO: 实现解除告警功能
        log.info("解除告警：{}", action);
        result.put("clearAlarm", "解除告警成功");
    }

    /**
     * 执行消息通知动作
     *
     * @param action 动作配置
     * @param result 执行结果
     */
    private void executeNotificationAction(Map<String, Object> action, Map<String, Object> result) {
        // TODO: 实现消息通知功能
        log.info("发送通知：{}", action);
        result.put("notification", "发送通知成功");
    }

    /**
     * 执行设备输出动作
     *
     * @param action 动作配置
     * @param result 执行结果
     */
    private void executeDeviceOutputAction(Map<String, Object> action, Map<String, Object> result) {
        try {
            // 获取设备ID
            Long deviceId = Long.valueOf(action.get("deviceId").toString());
            // 获取设备信息
            IotDeviceEntity device = deviceService.getById(deviceId);
            if (device == null) {
                log.error("设备不存在，deviceId={}", deviceId);
                result.put("deviceOutput", "设备不存在");
                return;
            }

            // 获取输出类型：设置属性、功能调用、读取属性
            String outputType = (String) action.get("outputType");
            if (StrUtil.isBlank(outputType)) {
                log.error("输出类型为空");
                result.put("deviceOutput", "输出类型为空");
                return;
            }

            // 根据输出类型执行相应的操作
            switch (outputType) {
                case "property":
                    // 设置属性
                    String propertyId = (String) action.get("propertyId");
                    Object propertyValue = action.get("propertyValue");
                    // TODO: 调用设备服务设置属性
                    log.info("设置设备属性：deviceId={}，propertyId={}，propertyValue={}", deviceId, propertyId, propertyValue);
                    result.put("deviceOutput", "设置设备属性成功");
                    break;
                case "function":
                    // 功能调用
                    String functionId = (String) action.get("functionId");
                    Map<String, Object> functionParams = (Map<String, Object>) action.get("functionParams");
                    // TODO: 调用设备服务执行功能
                    log.info("调用设备功能：deviceId={}，functionId={}，params={}", deviceId, functionId, functionParams);
                    result.put("deviceOutput", "调用设备功能成功");
                    break;
                case "read":
                    // 读取属性
                    String readPropertyId = (String) action.get("propertyId");
                    // TODO: 调用设备服务读取属性
                    log.info("读取设备属性：deviceId={}，propertyId={}", deviceId, readPropertyId);
                    result.put("deviceOutput", "读取设备属性成功");
                    break;
                default:
                    log.warn("未知的设备输出类型：{}", outputType);
                    result.put("deviceOutput", "未知的设备输出类型");
                    break;
            }
        } catch (Exception e) {
            log.error("执行设备输出动作异常：{}", e.getMessage(), e);
            result.put("deviceOutput", "执行设备输出动作失败：" + e.getMessage());
        }
    }
} 