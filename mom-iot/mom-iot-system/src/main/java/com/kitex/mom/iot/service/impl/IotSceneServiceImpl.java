package com.kitex.mom.iot.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.dto.IotSceneDTO;
import com.kitex.mom.iot.entity.IotSceneEntity;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;
import com.kitex.mom.iot.mapper.IotSceneMapper;
import com.kitex.mom.iot.service.IotSceneHistoryService;
import com.kitex.mom.iot.service.IotSceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 场景联动Service实现类
 */
@Service
@RequiredArgsConstructor
public class IotSceneServiceImpl extends ServiceImpl<IotSceneMapper, IotSceneEntity> implements IotSceneService {

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
		IotSceneEntity sceneEntity = this.getById(id);
		if (sceneEntity == null) {
			return false;
		}

		// 场景动作尚无真实执行器和回执；保存设计不代表动作已执行。
		IotSceneHistoryEntity history = new IotSceneHistoryEntity();
		history.setSceneName(sceneEntity.getName());
		history.setTriggerType(sceneEntity.getTriggerType());
		history.setStatus(0);
		Map<String, Object> result = new HashMap<>();
		String error;
		if (!Integer.valueOf(1).equals(sceneEntity.getStatus())) {
			error = "场景未启用，未执行任何动作";
		} else if (CollectionUtil.isEmpty(sceneEntity.getActions())) {
			error = "场景没有配置执行动作，未执行任何动作";
		} else {
			error = "场景动作尚未接入真实执行器，未执行任何动作";
			List<Map<String, Object>> actionResults = new ArrayList<>();
			for (int index = 0; index < sceneEntity.getActions().size(); index++) {
				Map<String, Object> action = sceneEntity.getActions().get(index);
				Object type = action == null ? null : action.get("type");
				String reason = actionFailure(type);
				actionResults.add(Map.of("index", index, "status", "NOT_EXECUTED", "message", reason));
			}
			result.put("actions", actionResults);
		}
		result.put("status", "NOT_EXECUTED");
		history.setResult(result);
		history.setErrorMessage(error);
		// 不吞掉历史持久化错误，避免向调用方伪造已有审计记录。
		if (!sceneHistoryService.addHistory(history)) {
			throw new IllegalStateException("场景执行失败记录保存失败");
		}
		return false;
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

	private String actionFailure(Object type) {
		if (!(type instanceof String actionType) || actionType.isBlank()) {
			return "动作类型缺失或无效，未执行";
		}
		return switch (actionType) {
		case "alarm" -> "场景触发告警执行器未接入，未创建告警";
		case "clearAlarm" -> "场景解除告警执行器未接入，未解除告警";
		case "notification" -> "场景通知执行器未接入，未发送通知";
		case "deviceOutput" -> "场景设备输出执行器未接入，未向设备发送指令或读取属性";
		default -> "不支持的场景动作，未执行";
		};
	}
}
