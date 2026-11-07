package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmProcessRouteService;
import com.kitex.mom.mdm.sop.MdmSopSupport;
import com.kitex.mom.mdm.sop.MdmSopWorkspace;
import com.kitex.mom.mdm.support.MdmRouteNodeGraphSupport;
import com.kitex.mom.mdm.support.MdmRouteNodeIdentitySupport;
import com.kitex.mom.mdm.support.MdmRouteNodeResolver;
import com.kitex.mom.mdm.support.MdmRouteNodeTimingPolicy;
import com.kitex.mom.mdm.vo.*;
import com.kitex.mom.mdm.vo.excel.MdmProcessRouteExcelVO;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工艺路线
 *
 * @author kitex
 * @date 2024-10-30 14:36:59
 */
@Service
@AllArgsConstructor
public class MdmProcessRouteServiceImpl extends ServiceImpl<MdmProcessRouteMapper, MdmProcessRouteEntity>
		implements MdmProcessRouteService {

	private final MdmProcessRouteWorkingMapper mdmProcessRouteWorkingMapper;

	private final MdmMaterialMapper mdmMaterialMapper;

	private final MdmBomMapper mdmBomMapper;

	private final MdmProcessProcedureSopMapper mdmProcessProcedureSopMapper;

	private final MdmProcessProcedureMatMapper mdmProcessProcedureMatMapper;

	private final MdmProcessProcedureSettingMapper mdmProcessProcedureSettingMapper;

	private final MdmStationSettingMapper mdmStationSettingMapper;

	private final MdmRouteNodeResolver routeNodeResolver;
	private final MdmSopOriginMapper sopOrigins;


	/**
	 * 通过id删除工艺路线
	 *
	 * @param ids id列表
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean removeRoute(List<Long> ids) {
		this.removeBatchByIds(ids);
		return Boolean.TRUE;
	}

	/**
	 * 通过id查询工艺路线
	 *
	 * @param id id
	 * @return R
	 */
	@Override
	@SneakyThrows
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MdmProcessRouteDTO getRouteById(Long id) {
		MdmProcessRouteDTO mdmProcessRouteDTO = new MdmProcessRouteDTO();
		MdmProcessRouteEntity mdmProcessRouteEntity = routeInCurrentFactory(id, false);
		BeanUtils.copyProperties(mdmProcessRouteEntity, mdmProcessRouteDTO);
		if (mdmProcessRouteEntity.getProcedureSnapshot() != null) {
			ObjectMapper mapper = new ObjectMapper();
			mdmProcessRouteDTO.setProcedureSnapshot(mapper.readTree(mdmProcessRouteEntity.getProcedureSnapshot()));
		}
		List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
				new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
						.eq(MdmProcessRouteWorkingEntity::getFactoryCode, mdmProcessRouteEntity.getFactoryCode())
						.eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode()));
		MdmRouteNodeGraphSupport.requireLegacyExecutable(mdmProcessRouteEntity.getGraphSchemaVersion(),
				mdmProcessRouteWorkingEntities);
		List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
		for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
			MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
			BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
			mdmProcessRouteWorkingVO.setMdmProcessProcedureSopList(
					mdmProcessProcedureSopMapper.selectList(sopQuery(mdmProcessRouteWorkingEntity)));
			mdmProcessRouteWorkingVO.setMdmProcessProcedureMatList(
					mdmProcessProcedureMatMapper.selectList(matQuery(mdmProcessRouteWorkingEntity)));
			procedureList.add(mdmProcessRouteWorkingVO);
		}
		mdmProcessRouteDTO.setProcedureList(procedureList);
		return mdmProcessRouteDTO;
	}


	/**
	 * 通过ids查询工艺路线
	 *
	 * @param ids ids
	 * @return R
	 */
	@Override
	@SneakyThrows
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<MdmProcessRouteDTO> getRouteByIds(List<Long> ids) {
		List<MdmProcessRouteDTO> result = new ArrayList<>();
		for (Long id : ids) {
			MdmProcessRouteDTO mdmProcessRouteDTO = new MdmProcessRouteDTO();
			MdmProcessRouteEntity mdmProcessRouteEntity = routeInCurrentFactory(id, false);
			BeanUtils.copyProperties(mdmProcessRouteEntity, mdmProcessRouteDTO);
			if (mdmProcessRouteEntity.getProcedureSnapshot() != null) {
				ObjectMapper mapper = new ObjectMapper();
				mdmProcessRouteDTO.setProcedureSnapshot(mapper.readTree(mdmProcessRouteEntity.getProcedureSnapshot()));
			}
			List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
					new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
							.eq(MdmProcessRouteWorkingEntity::getFactoryCode, mdmProcessRouteEntity.getFactoryCode())
							.eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode()));
			MdmRouteNodeGraphSupport.requireLegacyExecutable(mdmProcessRouteEntity.getGraphSchemaVersion(),
					mdmProcessRouteWorkingEntities);
			List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
			for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
				MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
				BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
				mdmProcessRouteWorkingVO.setMdmProcessProcedureSopList(
						mdmProcessProcedureSopMapper.selectList(sopQuery(mdmProcessRouteWorkingEntity)));
				mdmProcessRouteWorkingVO.setMdmProcessProcedureMatList(
						mdmProcessProcedureMatMapper.selectList(matQuery(mdmProcessRouteWorkingEntity)));

				// 查询工序绑定的默认工作中心列表
				List<MdmStationSettingEntity> stationList = mdmStationSettingMapper.selectList(
						new LambdaQueryWrapper<MdmStationSettingEntity>()
								.eq(MdmStationSettingEntity::getProcedureCode,
										mdmProcessRouteWorkingEntity.getProcessCode())
								.eq(MdmStationSettingEntity::getStatus, Boolean.TRUE)
								.eq(MdmStationSettingEntity::getDelFlag, "0")
				);
				List<MdmProcessProcedureWorkVO> workList = stationList.stream()
						.map(station -> {
							MdmProcessProcedureWorkVO vo = new MdmProcessProcedureWorkVO();
							vo.setWorkCode(station.getStationCode());
							vo.setWorkName(station.getStationName());
							vo.setLineCode(station.getLineCode());
							vo.setLineName(station.getLineName());
							vo.setPrimary(station.getIsPrimary());
							vo.setPriority(station.getPriority());
							vo.setStandardWorkingTime(station.getStandardWorkingTime());
							vo.setStationCapacity(station.getStationCapacity());
							vo.setParallelCapacity(station.getParallelCapacity());
							vo.setResourceGroupCode(station.getResourceGroupCode());
							vo.setResourceGroupName(station.getResourceGroupName());
							vo.setResourceGroupCapacity(station.getResourceGroupCapacity());
							vo.setDeviceCodes(station.getDeviceCodes());
							vo.setDeviceNames(station.getDeviceNames());
							vo.setEmployeeCodes(station.getEmployeeCodes());
							vo.setEmployeeNames(station.getEmployeeNames());
							return vo;
						}).collect(Collectors.toList());
				mdmProcessRouteWorkingVO.setMdmProcessProcedureWorkList(workList);
				procedureList.add(mdmProcessRouteWorkingVO);
			}
			if (!procedureList.isEmpty()) {
				// 根据 orderNum 进行排序
				procedureList.sort(Comparator.comparingInt(MdmProcessRouteWorkingVO::getOrderNum));
			}
			mdmProcessRouteDTO.setProcedureList(procedureList);
			result.add(mdmProcessRouteDTO);
		}
		return result;
	}

	/**
	 * 通过id查询工艺路线
	 *
	 * @param id id
	 * @return R
	 */
	@Override
	@SneakyThrows
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MdmProcessRouteDTO getRouteByIdAndLineCode(Long id, String lineCode) {
		MdmProcessRouteDTO mdmProcessRouteDTO = new MdmProcessRouteDTO();
		MdmProcessRouteEntity mdmProcessRouteEntity = routeInCurrentFactory(id, false);
		BeanUtils.copyProperties(mdmProcessRouteEntity, mdmProcessRouteDTO);
		if (mdmProcessRouteEntity.getProcedureSnapshot() != null) {
			ObjectMapper mapper = new ObjectMapper();
			mdmProcessRouteDTO.setProcedureSnapshot(mapper.readTree(mdmProcessRouteEntity.getProcedureSnapshot()));
		}
		List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
				new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
						.eq(MdmProcessRouteWorkingEntity::getFactoryCode, mdmProcessRouteEntity.getFactoryCode())
						.eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode()));
		MdmRouteNodeGraphSupport.requireLegacyExecutable(mdmProcessRouteEntity.getGraphSchemaVersion(),
				mdmProcessRouteWorkingEntities);
		List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
		for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
			MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
			BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
			mdmProcessRouteWorkingVO.setMdmProcessProcedureSopList(
					mdmProcessProcedureSopMapper.selectList(sopQuery(mdmProcessRouteWorkingEntity)));
			mdmProcessRouteWorkingVO.setMdmProcessProcedureMatList(
					mdmProcessProcedureMatMapper.selectList(matQuery(mdmProcessRouteWorkingEntity)));

			// 需要根据选择的线体，获取绑定工序的工位(工作中心)
			// 查询工序绑定的默认工作中心列表
			List<MdmStationSettingEntity> stationList = mdmStationSettingMapper.selectList(
					new LambdaQueryWrapper<MdmStationSettingEntity>()
							.eq(MdmStationSettingEntity::getProcedureCode,
									mdmProcessRouteWorkingEntity.getProcessCode())
							.eq(MdmStationSettingEntity::getStatus, Boolean.TRUE)
							.eq(MdmStationSettingEntity::getDelFlag, "0")
			);
			List<MdmProcessProcedureWorkVO> workList = stationList.stream()
					.filter(station -> station.getLineCode().equals(lineCode))
					.map(station -> {
						MdmProcessProcedureWorkVO vo = new MdmProcessProcedureWorkVO();
						vo.setWorkCode(station.getStationCode());
						vo.setWorkName(station.getStationName());
						vo.setLineCode(station.getLineCode());
						vo.setLineName(station.getLineName());
						vo.setPrimary(station.getIsPrimary());
						vo.setPriority(station.getPriority());
						vo.setStandardWorkingTime(station.getStandardWorkingTime());
						vo.setStationCapacity(station.getStationCapacity());
						vo.setParallelCapacity(station.getParallelCapacity());
						vo.setResourceGroupCode(station.getResourceGroupCode());
						vo.setResourceGroupName(station.getResourceGroupName());
						vo.setResourceGroupCapacity(station.getResourceGroupCapacity());
						vo.setDeviceCodes(station.getDeviceCodes());
						vo.setDeviceNames(station.getDeviceNames());
						vo.setEmployeeCodes(station.getEmployeeCodes());
						vo.setEmployeeNames(station.getEmployeeNames());
						return vo;
					}).collect(Collectors.toList());
			mdmProcessRouteWorkingVO.setMdmProcessProcedureWorkList(workList);
			procedureList.add(mdmProcessRouteWorkingVO);
		}
		if (!procedureList.isEmpty()) {
			// 根据 orderNum 进行排序
			procedureList.sort(Comparator.comparingInt(MdmProcessRouteWorkingVO::getOrderNum));
		}
		mdmProcessRouteDTO.setProcedureList(procedureList);
		return mdmProcessRouteDTO;
	}

	/**
	 * 导出excel 表格
	 *
	 * @param mdmProcessRoute 查询条件
	 * @param ids             导出指定ID
	 * @return excel 文件流
	 */
	@Override
	public List<MdmProcessRouteExcelVO> export(MdmProcessRouteEntity mdmProcessRoute, Long[] ids) {
		// 获取工艺路线列表
		List<MdmProcessRouteEntity> routerList = this.list(
				Wrappers.lambdaQuery(mdmProcessRoute).in(ArrayUtil.isNotEmpty(ids), MdmProcessRouteEntity::getId, ids));
		List<MdmProcessRouteExcelVO> rList = new ArrayList<>();
		for (MdmProcessRouteEntity item : routerList) {
			MdmProcessRouteExcelVO mdmProcessRouteExcelVO = new MdmProcessRouteExcelVO();
			BeanUtils.copyProperties(item, mdmProcessRouteExcelVO);
			rList.add(mdmProcessRouteExcelVO);
		}
		return rList;
	}

	/**
	 * 导入excel 表格
	 *
	 * @param excelVOList   工艺路线列表
	 * @param bindingResult 错误信息列表
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public R importProcessRoute(List<MdmProcessRouteExcelVO> excelVOList, BindingResult bindingResult) {
		// 通用校验获取失败的数据
		List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

		// 获取工艺路线列表
		Map<String, String> routeMap = this.list()
				.stream()
				.collect(Collectors.toMap(MdmProcessRouteEntity::getRouteCode, MdmProcessRouteEntity::getRouteCode,
						(a, b) -> a));
		// 获取物料列表
		Map<String, String> materialMap = mdmMaterialMapper
				.selectList(new LambdaQueryWrapper<MdmMaterialEntity>().eq(MdmMaterialEntity::getStatus, Boolean.TRUE))
				.stream()
				.collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getMaterialName,
						(a, b) -> a));
		// 获取bom列表
		Map<String, String> bomMap = mdmBomMapper
				.selectList(new LambdaQueryWrapper<MdmBomEntity>().eq(MdmBomEntity::getEnabled, Boolean.TRUE))
				.stream()
				.collect(Collectors.toMap(e -> e.getProductCode() + '_' + e.getVersion(), MdmBomEntity::getVersion,
						(a, b) -> a));
		List<MdmProcessRouteEntity> processRouteList = new ArrayList<>();
		for (MdmProcessRouteExcelVO mdmProcessRouteExcelVO : excelVOList) {
			Set<String> errorMsg = new HashSet<>();
			// 产品编码校验
			String productName = materialMap.get(mdmProcessRouteExcelVO.getProductCode());
			if (productName == null) {
				errorMsg.add(mdmProcessRouteExcelVO.getProductCode() + "产品编码不存在");
			} else {
				mdmProcessRouteExcelVO.setProductName(productName);
			}
			// bom校验
			String bomVersion = bomMap.get(
					mdmProcessRouteExcelVO.getProductCode() + '_' + mdmProcessRouteExcelVO.getBomVersion());
			if (bomVersion == null) {
				errorMsg.add(String.format("产品编码【%s】下不存在【%s】bom版本", mdmProcessRouteExcelVO.getProductCode(),
						mdmProcessRouteExcelVO.getBomVersion()));
			}
			// 工艺路线编码唯一性校验
			boolean routeCode = routeMap.containsKey(mdmProcessRouteExcelVO.getRouteCode());
			if (routeCode) {
				errorMsg.add(mdmProcessRouteExcelVO.getRouteCode() + "工艺路线编码已存在");
			}

			if (CollUtil.isNotEmpty(errorMsg)) {
				// 数据不合法情况
				errorMessageList.add(new ErrorMessage(mdmProcessRouteExcelVO.getLineNum() + 1, errorMsg));
			} else {
				MdmProcessRouteEntity mdmProcessRouteEntity = new MdmProcessRouteEntity();
				BeanUtils.copyProperties(mdmProcessRouteExcelVO, mdmProcessRouteEntity);
				mdmProcessRouteEntity.setStatus(Boolean.TRUE);
				processRouteList.add(mdmProcessRouteEntity);
				routeMap.put(mdmProcessRouteExcelVO.getRouteCode(), mdmProcessRouteExcelVO.getRouteCode());
			}
		}
		// 批量插入工艺路线数据
		this.saveBatch(processRouteList);
		if (CollUtil.isNotEmpty(errorMessageList)) {
			return R.failed(errorMessageList);
		}
		return R.ok(null, "工艺路线导入成功");
	}

	/**
	 * 组件传参分页查询
	 *
	 * @param page
	 * @param mdmProcessRoute
	 * @return
	 */
	@Override
	public Page<MdmProcessRouteEntity> selectPage(Page page, MdmProcessRouteSelectDTO mdmProcessRoute) {
		LambdaQueryWrapper<MdmProcessRouteEntity> queryWrapper = new LambdaQueryWrapper<>();
		queryWrapper.eq(MdmProcessRouteEntity::getFactoryCode, currentRouteFactory())
				.like(StrUtil.isNotEmpty(mdmProcessRoute.getRouteCode()), MdmProcessRouteEntity::getRouteCode,
						mdmProcessRoute.getRouteCode())
				.like(StrUtil.isNotEmpty(mdmProcessRoute.getRouteName()), MdmProcessRouteEntity::getRouteName,
						mdmProcessRoute.getRouteName())
				.like(StrUtil.isNotEmpty(mdmProcessRoute.getWorkshopName()), MdmProcessRouteEntity::getWorkshopName,
						mdmProcessRoute.getWorkshopName())
				.like(StrUtil.isNotEmpty(mdmProcessRoute.getWorkshopCode()), MdmProcessRouteEntity::getWorkshopCode,
						mdmProcessRoute.getWorkshopCode())
				.eq(StrUtil.isNotEmpty(mdmProcessRoute.getProductCode()), MdmProcessRouteEntity::getProductCode,
						mdmProcessRoute.getProductCode())
				.eq(mdmProcessRoute.getStatus() != null, MdmProcessRouteEntity::getStatus, mdmProcessRoute.getStatus())
				.orderByAsc(MdmProcessRouteEntity::getRouteCode);
		return baseMapper.selectPage(page, queryWrapper);
	}

	/**
	 * 根据工艺路线ID查询工艺路线下的工序及工序下的工作中心
	 *
	 * @param routeId 工艺路线ID
	 * @return R
	 */
	@Override
	@SneakyThrows
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MdmProcessRouteProcedureVO getProcessWorking(Long routeId) {
		MdmProcessRouteProcedureVO mdmProcessRouteProcedureVO = new MdmProcessRouteProcedureVO();
		MdmProcessRouteEntity processRoute = routeInCurrentFactory(routeId, false);
		BeanUtils.copyProperties(processRoute, mdmProcessRouteProcedureVO);
		List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
				new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
						.eq(MdmProcessRouteWorkingEntity::getRouteCode, processRoute.getRouteCode())
						.eq(MdmProcessRouteWorkingEntity::getFactoryCode, processRoute.getFactoryCode()));
		Map<String, String> canvasKeys = engineeringNodes(processRoute, mdmProcessRouteWorkingEntities);
		mdmProcessRouteProcedureVO.setProcedureSnapshot(MdmRouteNodeGraphSupport.canonicalCanvas(
				readCanvas(processRoute.getProcedureSnapshot()), mdmProcessRouteWorkingEntities, canvasKeys));
		List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
		for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
			MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
			BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
			procedureList.add(mdmProcessRouteWorkingVO);
		}
		mdmProcessRouteProcedureVO.setProcedureList(procedureList);
		return mdmProcessRouteProcedureVO;
	}

	/**
	 * 查询工艺路线详情
	 *
	 * @param id
	 * @return
	 */
	@Override
	public MdmProcessRouteWorkingEntity getProcessWorkingById(Long id) {
		return routeNodeResolver.byId(id, false);
	}


	/**
	 * 新增工艺路线下的工序
	 *
	 * @param mdmProcessRouteProcedureDTO 工艺路线-工序DTO
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean saveProcessWorking(MdmProcessRouteProcedureDTO mdmProcessRouteProcedureDTO) {
		if (mdmProcessRouteProcedureDTO == null || mdmProcessRouteProcedureDTO.getId() == null
				|| StrUtil.isBlank(mdmProcessRouteProcedureDTO.getRouteCode())) {
			throw new CheckedException("工艺路线及工序关系参数不完整");
		}
		MdmProcessRouteEntity parent = routeInCurrentFactory(mdmProcessRouteProcedureDTO.getId(), true);
		if (!Objects.equals(parent.getRouteCode(), mdmProcessRouteProcedureDTO.getRouteCode())) {
			throw new CheckedException("路线ID与编码不一致，请重新打开路线");
		}
		if (mdmProcessRouteProcedureDTO.getExpectedGraphRevision() == null
				|| !Objects.equals(parent.getGraphRevision(), mdmProcessRouteProcedureDTO.getExpectedGraphRevision())) {
			throw new CheckedException("路线已被修改或页面版本已过期，请重新打开后核对，不能覆盖新图");
		}
		return saveGraph(mdmProcessRouteProcedureDTO, parent);
	}

	/**
	 * 调用方已经锁父路线并核原版本；图与节点必须在同一事务提交。
	 */
	private Boolean saveGraph(MdmProcessRouteProcedureDTO command, MdmProcessRouteEntity parent) {
		List<MdmProcessRouteWorkingEntity> procedureList = Optional.ofNullable(
				command.getProcedureList()).orElseGet(ArrayList::new);
		List<MdmProcessRouteWorkingEntity> current = routeNodes(parent);
		boolean nodeGraph = MdmRouteNodeGraphSupport.VERSION.equals(command.getGraphSchemaVersion());
		if (!nodeGraph && (command.getGraphSchemaVersion() != null || parent.getGraphSchemaVersion() != null)) {
			throw new CheckedException("旧图保存协议不能覆盖节点图，请刷新升级后的页面");
		}
		List<MdmProcessRouteWorkingEntity> saved;
		JsonNode canvas = decodeCanvas(command.getProcedureSnapshot());
		if (nodeGraph) {
			saved = MdmRouteNodeIdentitySupport.reconcileNodes(parent, current, procedureList);
			Map<String, String> keys = MdmRouteNodeGraphSupport.normalize(saved);
			canvas = MdmRouteNodeGraphSupport.canonicalCanvas(canvas, saved, keys);
		} else {
			validateAndNormalizeProcessGraph(procedureList);
			if (!procedureList.isEmpty() && canvas == null) {
				throw new CheckedException("工艺路线画布快照不能为空");
			}
			saved = MdmRouteNodeIdentitySupport.reconcile(parent, current, procedureList, command.getNewProcessCodes());
		}
		String procedureSnapshot = canvas == null ? null : canvas.toString();
		LambdaUpdateWrapper<MdmProcessRouteEntity> update = new LambdaUpdateWrapper<MdmProcessRouteEntity>()
				.set(MdmProcessRouteEntity::getProcedureSnapshot, procedureSnapshot)
				.eq(MdmProcessRouteEntity::getId, parent.getId());
		if (nodeGraph) {
			update.set(MdmProcessRouteEntity::getGraphSchemaVersion, MdmRouteNodeGraphSupport.VERSION);
		}
		if (!this.update(update)) {
			throw new CheckedException("路线画布保存失败");
		}
		Set<Long> currentIds = current.stream().map(MdmProcessRouteWorkingEntity::getId).collect(Collectors.toSet());
		Set<Long> savedIds = saved.stream().map(MdmProcessRouteWorkingEntity::getId).collect(Collectors.toSet());
		// 保留节点行而非全删重建；数据库同时核查删除依赖，失败则整图回滚。
		for (MdmProcessRouteWorkingEntity old : current) {
			if (!savedIds.contains(old.getId()) && mdmProcessRouteWorkingMapper.deleteById(old.getId()) != 1) {
				throw new CheckedException("路线节点移除失败，请刷新后核对");
			}
		}
		for (MdmProcessRouteWorkingEntity node : saved) {
			int affected = currentIds.contains(node.getId()) ? mdmProcessRouteWorkingMapper.updateById(node)
					: mdmProcessRouteWorkingMapper.insert(node);
			if (affected != 1) {
				throw new CheckedException("路线节点保存失败：" + node.getProcessCode());
			}
		}
		return Boolean.TRUE;
	}

	/**
	 * 来源与目标按ID加锁；结构复制不能覆盖节点、附件或继承来源身份。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean copyGraph(MdmRouteGraphCopyDTO command) {
		if (command == null || command.getId() == null || command.getSourceRouteId() == null
				|| Objects.equals(command.getId(), command.getSourceRouteId())) {
			throw new CheckedException("请选择不同的来源和空目标路线");
		}
		Map<Long, MdmProcessRouteEntity> locked = new HashMap<>();
		List.of(command.getId(), command.getSourceRouteId()).stream().sorted()
				.forEach(id -> locked.put(id, routeInCurrentFactory(id, true)));
		MdmProcessRouteEntity target = locked.get(command.getId());
		MdmProcessRouteEntity source = locked.get(command.getSourceRouteId());
		if (command.getExpectedGraphRevision() == null || command.getExpectedSourceGraphRevision() == null
				|| !Objects.equals(target.getGraphRevision(), command.getExpectedGraphRevision())
				|| !Objects.equals(source.getGraphRevision(), command.getExpectedSourceGraphRevision())) {
			throw new CheckedException("来源或目标路线已被修改，请重新读取并确认复制范围");
		}
		if (!routeNodes(target).isEmpty()) {
			throw new CheckedException("结构复制只允许空目标路线，不能覆盖已有节点");
		}
		List<MdmProcessRouteWorkingEntity> rows = routeNodes(source);
		if (rows.isEmpty()) {
			throw new CheckedException("来源路线没有可复制的节点");
		}
		Map<String, String> sourceKeys = engineeringNodes(source, rows);
		JsonNode canvas = MdmRouteNodeGraphSupport.canonicalCanvas(readCanvas(source.getProcedureSnapshot()), rows,
				sourceKeys);
		Map<String, String> newKeys = new LinkedHashMap<>();
		rows.forEach(row -> newKeys.put(row.getRouteNodeCode(), "C-" + UUID.randomUUID()));
		// 先按原图核验，随后画布和配置共同替换到新请求键，保存时再分配RN身份。
		for (JsonNode node : canvas.path("nodes")) {
			String replacement = newKeys.get(node.path("id").asText());
			if (replacement != null) {
				((com.fasterxml.jackson.databind.node.ObjectNode) node).put("id", replacement);
			}
		}
		for (JsonNode edge : canvas.path("edges")) {
			for (String field : List.of("source", "target")) {
				String replacement = newKeys.get(edge.path(field).asText());
				if (replacement != null) {
					((com.fasterxml.jackson.databind.node.ObjectNode) edge).put(field, replacement);
				}
			}
		}
		for (MdmProcessRouteWorkingEntity row : rows) {
			row.setClientNodeKey(newKeys.get(row.getRouteNodeCode()));
			row.setNewNode(true);
			row.setId(null);
			row.setRouteNodeCode(null);
			row.setPreRouteNodeCodes(
					Arrays.stream(row.getPreRouteNodeCodes()).map(newKeys::get).toArray(String[]::new));
			row.setNodeTimingRulesJson(MdmRouteNodeTimingPolicy.remap(row.getNodeTimingRulesJson(), newKeys));
		}
		MdmProcessRouteProcedureDTO save = new MdmProcessRouteProcedureDTO();
		save.setGraphSchemaVersion(MdmRouteNodeGraphSupport.VERSION);
		save.setProcedureList(rows);
		save.setProcedureSnapshot(canvas);
		return saveGraph(save, target);
	}

	/**
	 * 当前工程图读取投影；历史头不改，旧关系先按原规则规范化。
	 */
	private Map<String, String> engineeringNodes(MdmProcessRouteEntity parent,
	                                             List<MdmProcessRouteWorkingEntity> rows) {
		if (parent.getGraphSchemaVersion() == null) {
			validateAndNormalizeProcessGraph(rows);
			return MdmRouteNodeGraphSupport.projectLegacyForEditing(rows);
		}
		if (!MdmRouteNodeGraphSupport.VERSION.equals(parent.getGraphSchemaVersion())) {
			throw new CheckedException("不支持的路线图协议，请升级服务");
		}
		rows.forEach(row -> row.setClientNodeKey(row.getRouteNodeCode()));
		return MdmRouteNodeGraphSupport.normalize(rows);
	}

	private List<MdmProcessRouteWorkingEntity> routeNodes(MdmProcessRouteEntity parent) {
		return mdmProcessRouteWorkingMapper.selectList(new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
				.eq(MdmProcessRouteWorkingEntity::getFactoryCode, parent.getFactoryCode())
				.eq(MdmProcessRouteWorkingEntity::getRouteCode, parent.getRouteCode()));
	}

	@Override
	public MdmProcessRouteEntity getEngineeringHeader(Long id) {
		MdmProcessRouteEntity row = routeInCurrentFactory(id, false);
		row.setProcedureSnapshot(null);
		return row;
	}

	/**
	 * 兼容旧页面将JSON字符串再次编码为JSON字符串的快照，V2只存对象。
	 */
	private JsonNode readCanvas(String value) {
		try {
			return value == null || value.isBlank() ? null : decodeCanvas(new ObjectMapper().readTree(value));
		} catch (Exception ex) {
			throw new CheckedException("路线画布快照无法读取，请核对原数据");
		}
	}

	private JsonNode decodeCanvas(JsonNode value) {
		try {
			JsonNode result = value;
			for (int i = 0; i < 2 && result != null && result.isTextual(); i++) {
				result = new ObjectMapper().readTree(result.asText());
			}
			return result == null || result.isNull() ? null : result;
		} catch (Exception ex) {
			throw new CheckedException("路线画布JSON格式无效");
		}
	}

	/**
	 * 旧选路/物料协议在返回数据前核能力；不静默过滤默认路线。
	 */
	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public void requireLegacyExecutableRoutes(List<MdmProcessRouteEntity> routes) {
		for (MdmProcessRouteEntity input : routes) {
			MdmProcessRouteEntity actual = routeInCurrentFactory(input.getId(), false);
			MdmRouteNodeGraphSupport.requireLegacyExecutable(actual.getGraphSchemaVersion(), routeNodes(actual));
		}
	}

	/**
	 * 维护入口只认当前真实租户/工厂；写入口先锁父路线以串行图修改。
	 */
	private MdmProcessRouteEntity routeInCurrentFactory(Long id, boolean lock) {
		String factory = currentRouteFactory();
		LambdaQueryWrapper<MdmProcessRouteEntity> query = new LambdaQueryWrapper<MdmProcessRouteEntity>()
				.eq(MdmProcessRouteEntity::getId, id).eq(MdmProcessRouteEntity::getFactoryCode, factory);
		if (lock) {
			query.last("FOR UPDATE");
		}
		MdmProcessRouteEntity row = baseMapper.selectOne(query);
		if (row == null) {
			throw new CheckedException("路线不存在或不属于当前工厂");
		}
		return row;
	}

	/**
	 * 服务端以“前置工序”作为DAG权威关系，拒绝重复节点、悬空引用、自环和环路，
	 * 并重算后置关系及拓扑层级，避免仅靠前端画布校验导致脏路线进入APS/MES。
	 */
	private void validateAndNormalizeProcessGraph(List<MdmProcessRouteWorkingEntity> procedures) {
		if (procedures.isEmpty()) {
			return;
		}
		Map<String, MdmProcessRouteWorkingEntity> byCode = new LinkedHashMap<>();
		for (MdmProcessRouteWorkingEntity procedure : procedures) {
			String code = StrUtil.trim(procedure.getProcessCode());
			if (StrUtil.isBlank(code)) {
				throw new CheckedException("工艺路线存在未选择工序的节点");
			}
			procedure.setProcessCode(code);
			if (byCode.putIfAbsent(code, procedure) != null) {
				throw new CheckedException("工艺路线中工序[" + code + "]重复");
			}
			procedure.setTimeCheck(Boolean.TRUE.equals(procedure.getTimeCheck()));
			if (!Boolean.TRUE.equals(procedure.getTimeCheck())) {
				procedure.setTimeVal(null);
			} else if (procedure.getTimeVal() == null || procedure.getTimeVal().signum() < 0) {
				throw new CheckedException("工序[" + code + "]启用转序时间控制后，转序分钟必须大于等于0");
			}
			procedure.setOverlapEnabled(Boolean.TRUE.equals(procedure.getOverlapEnabled()));
			if (!Boolean.TRUE.equals(procedure.getOverlapEnabled())) {
				procedure.setTransferBatchQuantity(null);
			} else if (procedure.getTransferBatchQuantity() == null
					|| procedure.getTransferBatchQuantity().signum() <= 0) {
				throw new CheckedException("工序[" + code + "]启用重叠执行后，转移批量必须大于0");
			}
		}

		Map<String, LinkedHashSet<String>> predecessors = new LinkedHashMap<>();
		Map<String, LinkedHashSet<String>> successors = new LinkedHashMap<>();
		byCode.keySet().forEach(code -> {
			predecessors.put(code, new LinkedHashSet<>());
			successors.put(code, new LinkedHashSet<>());
		});
		boolean hasExplicitRelation = procedures.stream().anyMatch(procedure ->
				!normalizeCodes(procedure.getPreRouteCode()).isEmpty()
						|| !normalizeCodes(procedure.getNextRouteCode()).isEmpty());
		List<MdmProcessRouteWorkingEntity> legacyOrder = procedures.stream().sorted(Comparator
						.comparingInt((MdmProcessRouteWorkingEntity item) -> Optional.ofNullable(item.getOrderNum())
								.orElse(Integer.MAX_VALUE)).thenComparing(MdmProcessRouteWorkingEntity::getProcessCode))
				.toList();
		if (!hasExplicitRelation && legacyOrder.size() > 1) {
			for (int index = 1; index < legacyOrder.size(); index++) {
				legacyOrder.get(index).setPreRouteCode(new String[]{legacyOrder.get(index - 1).getProcessCode()});
			}
		}
		for (MdmProcessRouteWorkingEntity procedure : procedures) {
			String code = procedure.getProcessCode();
			for (String predecessor : normalizeCodes(procedure.getPreRouteCode())) {
				if (!byCode.containsKey(predecessor)) {
					throw new CheckedException("工序[" + code + "]引用了不存在的前置工序[" + predecessor + "]");
				}
				if (code.equals(predecessor)) {
					throw new CheckedException("工序[" + code + "]不能把自身作为前置工序");
				}
				predecessors.get(code).add(predecessor);
				successors.get(predecessor).add(code);
			}
		}
		List<String> terminalProcesses = successors.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
				.map(Map.Entry::getKey).toList();
		if (terminalProcesses.size() != 1) {
			throw new CheckedException("生产工艺路线必须且只能有一道最终工序，当前末端工序："
					+ String.join(",", terminalProcesses));
		}
		String terminal = terminalProcesses.get(0);
		if (Boolean.TRUE.equals(byCode.get(terminal).getOverlapEnabled())) {
			throw new CheckedException("最终工序[" + terminal + "]没有后序，不能启用转移批量重叠");
		}

		Map<String, Integer> indegrees = predecessors.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size(),
						(a, b) -> a, LinkedHashMap::new));
		Map<String, Integer> levels = new HashMap<>();
		PriorityQueue<String> ready = new PriorityQueue<>(Comparator
				.comparingInt(
						(String code) -> Optional.ofNullable(byCode.get(code).getOrderNum()).orElse(Integer.MAX_VALUE))
				.thenComparing(Function.identity()));
		indegrees.forEach((code, degree) -> {
			if (degree == 0) {
				ready.add(code);
				levels.put(code, 1);
			}
		});
		int visited = 0;
		while (!ready.isEmpty()) {
			String code = ready.poll();
			visited++;
			for (String successor : successors.get(code)) {
				levels.merge(successor, levels.get(code) + 1, Math::max);
				int remaining = indegrees.computeIfPresent(successor, (key, degree) -> degree - 1);
				if (remaining == 0) {
					ready.add(successor);
				}
			}
		}
		if (visited != procedures.size()) {
			String cyclic = indegrees.entrySet().stream().filter(entry -> entry.getValue() > 0)
					.map(Map.Entry::getKey).collect(Collectors.joining(","));
			throw new CheckedException("工艺路线存在环路，涉及工序：" + cyclic);
		}
		Map<String, String> timingModes = new HashMap<>();
		procedures.forEach(procedure -> timingModes.put(procedure.getProcessCode(), procedure.getType()));
		procedures.forEach(procedure -> {
			String code = procedure.getProcessCode();
			procedure.setPreRouteCode(predecessors.get(code).toArray(String[]::new));
			procedure.setNextRouteCode(successors.get(code).toArray(String[]::new));
			procedure.setOrderNum(levels.get(code));
			procedure.setTimingRulesJson(com.kitex.mom.mdm.support.MdmProcessTimingPolicy
					.parse(procedure.getTimingRulesJson()).normalize(code,
							com.kitex.mom.mdm.support.MdmProcessTimingPolicy.ancestors(code, predecessors)));
			com.kitex.mom.mdm.support.MdmProcessTimingPolicy.parse(procedure.getTimingRulesJson())
					.validateModes(code, timingModes);
		});
	}

	private List<String> normalizeCodes(String[] values) {
		if (values == null || values.length == 0) {
			return List.of();
		}
		return Arrays.stream(values).map(StrUtil::trim).filter(StrUtil::isNotBlank).distinct().toList();
	}

	/**
	 * 根据工艺路线编码查询工序列表
	 *
	 * @param routeCode 工艺路线编码
	 * @return R
	 */
	@Override
	public List<MdmProcessRouteWorkingEntity> getProcedureList(String routeCode) {
		return mdmProcessRouteWorkingMapper.selectList(
				new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
						.eq(MdmProcessRouteWorkingEntity::getFactoryCode, currentRouteFactory())
						.eq(MdmProcessRouteWorkingEntity::getRouteCode, routeCode)
						.orderByAsc(MdmProcessRouteWorkingEntity::getOrderNum));
	}

	/**
	 * 新增工艺路线下的工序的SOP
	 *
	 * @param mdmProcessProcedureSopDTO 工艺路线-工序-SOP
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean saveSop(MdmProcessProcedureSopDTO mdmProcessProcedureSopDTO) {
		if (mdmProcessProcedureSopDTO == null || mdmProcessProcedureSopDTO.getSopList() == null
				|| mdmProcessProcedureSopDTO.getSopList().stream().anyMatch(Objects::isNull)) {
			throw new CheckedException("请提供完整SOP列表，清空时提交空列表");
		}
		MdmProcessRouteWorkingEntity node = routeNodeResolver.byId(mdmProcessProcedureSopDTO.getId(), true);
		var previous = mdmProcessProcedureSopMapper.selectList(sopQuery(node));
		var expected = mdmProcessProcedureSopDTO.getExpectedAttachmentHashes();
		if (expected != null) {
			var actual = previous.stream().map(MdmSopWorkspace::legacyHash).sorted().toList();
			if (expected.stream().anyMatch(Objects::isNull) || !actual.equals(expected.stream().sorted().toList())) {
				throw new CheckedException("工序历史附件已被其他人员修改，请重新读取后操作");
			}
		} else if (sopOrigins.selectCount(new LambdaQueryWrapper<MdmSopOriginEntity>()
				.eq(MdmSopOriginEntity::getTenantId, node.getTenantId())
				.eq(MdmSopOriginEntity::getFactoryCode, node.getFactoryCode())
				.eq(MdmSopOriginEntity::getRouteCode, node.getRouteCode())
				.eq(MdmSopOriginEntity::getRouteNodeCode, node.getRouteNodeCode())) > 0) {
			throw new CheckedException("该节点已接入统一SOP，请刷新前端后携带原附件指纹保存");
		}
		// 保留未改变文件的稳定ID，不能因保存整个列表就破坏已经形成的转入来源。
		var retained = new HashSet<Long>();
		var submitted = new HashSet<String>();
		// 只接受文件业务属性；身份和审计不能由客户端继承或伪造。
		for (var item : mdmProcessProcedureSopDTO.getSopList()) {
			String key = MdmSopSupport.hash(item.getName(), item.getFileName(), item.getUrl());
			if (!submitted.add(key)) {
				throw new CheckedException("同一工序不能重复添加相同历史附件");
			}
			var unchanged = previous.stream().filter(old -> !retained.contains(old.getId())
					&& Objects.equals(old.getName(), item.getName()) &&
					Objects.equals(old.getFileName(), item.getFileName())
					&& Objects.equals(old.getUrl(), item.getUrl())).findFirst();
			if (unchanged.isPresent()) {
				retained.add(unchanged.get().getId());
				continue;
			}
			MdmProcessProcedureSopEntity row = new MdmProcessProcedureSopEntity();
			row.setTenantId(node.getTenantId());
			row.setFactoryCode(node.getFactoryCode());
			row.setRouteCode(node.getRouteCode());
			row.setRouteNodeCode(node.getRouteNodeCode());
			row.setProcessCode(node.getProcessCode());
			row.setName(item.getName());
			row.setFileName(item.getFileName());
			row.setUrl(item.getUrl());
			if (mdmProcessProcedureSopMapper.insert(row) != 1) {
				throw new CheckedException("SOP保存失败，原配置未变更");
			}
		}
		for (var old : previous) {
			if (!retained.contains(old.getId())) {
				mdmProcessProcedureSopMapper.deleteById(old.getId());
			}
		}
		return Boolean.TRUE;
	}

	/**
	 * 查询工艺路线下的工序的SOP
	 *
	 * @param id 工艺路线-工序id
	 * @return R
	 */
	@Override
	public List<MdmProcedureSopVO> getSop(Long id) {
		return sopViews(routeNodeResolver.byId(id, false));
	}

	/**
	 * 查询工艺路线下工序绑定的投料信息
	 *
	 * @param id 工艺路线-工序id
	 * @return R
	 */
	@Override
	public List<MdmProcessProcedureMatEntity> getMat(Long id) {
		return mdmProcessProcedureMatMapper.selectList(matQuery(routeNodeResolver.byId(id, false))
				.orderByAsc(MdmProcessProcedureMatEntity::getStep));
	}

	/**
	 * 持锁替换精确路线节点的完整投料配置，并显式选择统一或逐需求耗料方式。
	 * 逐料要求每一需求都有FEED/BACKFLUSH；继承操作不能擦除已激活逐料配置。
	 *
	 * @param mdmProcessProcedureMatDTO 原节点主键、完整物料及明确策略；物料、单位或方式不完整时拒绝
	 * @return 本次配置是否完整保存；必要写入失败时整笔回滚
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean saveMat(MdmProcessProcedureMatDTO mdmProcessProcedureMatDTO) {
		if (mdmProcessProcedureMatDTO == null || mdmProcessProcedureMatDTO.getMatList() == null
				|| mdmProcessProcedureMatDTO.getMatList().stream().anyMatch(Objects::isNull)) {
			throw new CheckedException("请提供完整投料列表，清空时提交空列表");
		}
		MdmProcessRouteWorkingEntity node = routeNodeResolver.byId(mdmProcessProcedureMatDTO.getId(), true);
		var materialRows = mdmProcessProcedureMatDTO.getMatList();
		var setting = mdmProcessProcedureSettingMapper.selectOne(settingQuery(node));
		String consumptionPolicy = validateMaterialConsumptionPolicy(mdmProcessProcedureMatDTO.getConsumptionPolicy(),
				setting, materialRows);
		updateMaterialConsumptionSetting(node, setting, consumptionPolicy);
		mdmProcessProcedureMatMapper.delete(matQuery(node));
		for (var item : materialRows) {
			MdmProcessProcedureMatEntity row = new MdmProcessProcedureMatEntity();
			row.setTenantId(node.getTenantId());
			row.setFactoryCode(node.getFactoryCode());
			row.setRouteCode(node.getRouteCode());
			row.setRouteNodeCode(node.getRouteNodeCode());
			row.setProcessCode(node.getProcessCode());
			row.setMaterialCode(item.getMaterialCode());
			row.setMaterialName(item.getMaterialName());
			row.setMaterialSpec(item.getMaterialSpec());
			row.setUnit(item.getUnit());
			row.setQuantity(item.getQuantity());
			row.setStep(item.getStep());
			row.setConsumptionMode("PER_MATERIAL".equals(consumptionPolicy) ? item.getConsumptionMode() : null);
			if (mdmProcessProcedureMatMapper.insert(row) != 1) {
				throw new CheckedException("投料保存失败，原配置未变更");
			}
		}
		return Boolean.TRUE;
	}

	/**
	 * 所有资格在删除旧绑定前核清；旧客户端不能把逐料版本改回统一设置。
	 */
	private String validateMaterialConsumptionPolicy(String requested, MdmProcessProcedureSettingEntity current,
	                                                 List<MdmProcessProcedureMatEntity> rows) {
		String policy = requested == null ? "INHERIT" : requested;
		if (!Set.of("INHERIT", "FEED", "BACKFLUSH", "PER_MATERIAL").contains(policy)
				|| current != null && current.getMaterialConsumptionSchema() != null
				&& !"MATERIAL_CONSUMPTION_V2".equals(current.getMaterialConsumptionSchema())) {
			throw new CheckedException("耗料策略版本未知，请刷新后使用明确配置");
		}
		if ("INHERIT".equals(policy) && current != null && current.getMaterialConsumptionSchema() != null) {
			throw new CheckedException("当前节点已逐物料配置，旧载荷不能覆盖；请明确选择逐物料、投料或倒冲");
		}
		if (rows.size() > 100 || "PER_MATERIAL".equals(policy) && rows.isEmpty()) {
			throw new CheckedException("逐物料配置须有1至100项需求，用料列表最多100项");
		}
		var codes = new HashSet<String>();
		for (var row : rows) {
			if (row.getMaterialCode() == null || row.getMaterialCode().isBlank()
					|| !row.getMaterialCode().equals(row.getMaterialCode().trim()) || !codes.add(row.getMaterialCode())
					|| row.getUnit() == null || row.getUnit().isBlank() || row.getQuantity() == null
					|| row.getQuantity().signum() <= 0 || row.getStep() == null || row.getStep() < 1) {
				throw new CheckedException("工序用料须有唯一物料、原单位、正单耗和正投料步骤");
			}
			String mode = row.getConsumptionMode();
			if (mode != null && !Set.of("FEED", "BACKFLUSH").contains(mode)
					|| "PER_MATERIAL".equals(policy) && mode == null
					|| "INHERIT".equals(policy) && mode != null) {
				throw new CheckedException("逐物料须明确投料或倒冲；沿用工序设置不能混入逐料覆盖");
			}
		}
		return policy;
	}

	private void updateMaterialConsumptionSetting(MdmProcessRouteWorkingEntity node,
	                                              MdmProcessProcedureSettingEntity current,
	                                              String policy) {
		if ("INHERIT".equals(policy)) {
			return;
		}
		boolean insert = current == null;
		var row = current;
		if (insert) {
			row = new MdmProcessProcedureSettingEntity();
			row.setTenantId(node.getTenantId());
			row.setFactoryCode(node.getFactoryCode());
			row.setRouteCode(node.getRouteCode());
			row.setRouteNodeCode(node.getRouteNodeCode());
			row.setProcessCode(node.getProcessCode());
		}
		row.setMaterialConsumptionSchema("PER_MATERIAL".equals(policy) ? "MATERIAL_CONSUMPTION_V2" : null);
		row.setIsBackFlush("PER_MATERIAL".equals(policy) ? null : "BACKFLUSH".equals(policy));
		row.setUpdateBy(null);
		row.setUpdateTime(null);
		int changed = insert ? mdmProcessProcedureSettingMapper.insert(
				row) : mdmProcessProcedureSettingMapper.updateById(row);
		if (changed != 1) {
			throw new CheckedException("工序耗料策略未保存，原用料配置保持");
		}
	}

	/**
	 * 按产品分组查询工艺路线树，组内默认路线优先。
	 * 未关联产品的历史路线保留在独立展示分组中，不修改其产品归属。
	 *
	 * @param queryWrapper 查询条件
	 * @return 产品分组及其工艺路线；无数据时返回空列表
	 */
	@Override
	public List<MdmProcessRouteTreeVO> tree(QueryWrapper<MdmProcessRouteEntity> queryWrapper) {
		// 创建返回体VO
		List<MdmProcessRouteTreeVO> treeList = new ArrayList<>();
		// 获取工艺路线列表
		List<MdmProcessRouteEntity> list = this.list(queryWrapper);

		// groupingBy 不接受 null 键；空串仅作展示分组键，不回写路线的产品编码。
		Map<String, List<MdmProcessRouteEntity>> groupedByProduct = list.stream()
				.collect(Collectors.groupingBy(
						route -> StrUtil.isBlank(route.getProductCode()) ? "" : route.getProductCode(),
						LinkedHashMap::new,
						Collectors.toList()
				));

		// 处理每个产品分组
		groupedByProduct.forEach((productCode, routes) -> {
			if (!routes.isEmpty()) {
				MdmProcessRouteEntity firstRoute = routes.get(0);
				MdmProcessRouteTreeVO parentNode = new MdmProcessRouteTreeVO();
				// 树节点使用独立命名空间，避免产品编码、未关联分组与路线主键冲突。
				if (productCode.isEmpty()) {
					parentNode.setId("route-unassigned");
					parentNode.setTitle("未关联产品");
					// 未关联路线不能共用首条记录的名称、规格或单位，各自信息保留在子节点。
				} else {
					parentNode.setId("route-product:" + productCode);
					parentNode.setTitle(StrUtil.isBlank(firstRoute.getProductName()) ? productCode
							: String.format("%s(%s)", firstRoute.getProductName(), productCode));
					parentNode.setProductCode(productCode);
					parentNode.setProductName(firstRoute.getProductName());
					parentNode.setProductSpec(firstRoute.getProductSpec());
					parentNode.setProductUnit(firstRoute.getProductUnit());
				}

				// 创建子节点列表
				List<MdmProcessRouteChildrenVO> children = java.util.stream.Stream.concat(
								routes.stream().filter(route -> Boolean.TRUE.equals(route.getIsDefault())),
								routes.stream().filter(route -> !Boolean.TRUE.equals(route.getIsDefault()))
						)
						.map(route -> {
							MdmProcessRouteChildrenVO childNode = new MdmProcessRouteChildrenVO();
							BeanUtils.copyProperties(route, childNode);
							childNode.setTitle(route.getRouteCode());
							return childNode;
						})
						.collect(Collectors.toList());

				parentNode.setChildren(children);
				treeList.add(parentNode);
			}
		});

		return treeList;
	}

	/**
	 * 根据工艺路线工序编码查询工序的SOP
	 *
	 * @param routeCode   工艺路线编码
	 * @param processCode 工艺路线-编码
	 * @return
	 */
	@Override
	public List<MdmProcedureSopVO> getSopByCode(String routeCode, String processCode) {
		return sopViews(routeNodeResolver.resolve(routeCode, null, processCode, false));
	}

	/**
	 * 根据工艺路线、工序编码查询工序的投料
	 *
	 * @param routeCode   工艺路线编码
	 * @param processCode 工艺路线-编码
	 * @return
	 */
	@Override
	public List<MdmProcessProcedureMatEntity> getMatByCode(String routeCode, String processCode) {
		return mdmProcessProcedureMatMapper.selectList(
				matQuery(routeNodeResolver.resolve(routeCode, null, processCode, false))
						.orderByAsc(MdmProcessProcedureMatEntity::getStep));
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<MdmProcessProcedureMatEntity> getMaterialAssignments(MdmRouteMaterialAssignmentQueryDTO query) {
		if (query == null || StrUtil.isBlank(query.getFactoryCode())
				|| CollUtil.isEmpty(query.getRouteCodes()) || CollUtil.isEmpty(query.getMaterialCodes())) {
			throw new CheckedException("批量查询工序投料映射必须指定工厂、工艺路线和物料");
		}
		if (query.getRouteCodes().size() > 200 || query.getMaterialCodes().size() > 500) {
			throw new CheckedException("单次最多查询200条工艺路线和500个物料");
		}
		if (!Objects.equals(currentRouteFactory(), query.getFactoryCode())) {
			throw new CheckedException("工序投料查询工厂必须与当前工厂一致");
		}
		// 旧PLAN/MRP按主工序聚合投料；拒绝重复图，不能先返回会被合并的节点数据。
		List<MdmProcessRouteEntity> routes = baseMapper.selectList(new LambdaQueryWrapper<MdmProcessRouteEntity>()
				.eq(MdmProcessRouteEntity::getFactoryCode, query.getFactoryCode())
				.in(MdmProcessRouteEntity::getRouteCode, query.getRouteCodes()));
		if (routes.size() != new HashSet<>(query.getRouteCodes()).size()) {
			throw new CheckedException("投料查询存在缺失或重复路线，请核对当前组织主数据");
		}
		requireLegacyExecutableRoutes(routes);
		return mdmProcessProcedureMatMapper.selectList(new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
				.eq(MdmProcessProcedureMatEntity::getFactoryCode, query.getFactoryCode())
				.in(MdmProcessProcedureMatEntity::getRouteCode, query.getRouteCodes())
				.in(MdmProcessProcedureMatEntity::getMaterialCode, query.getMaterialCodes())
				.orderByAsc(MdmProcessProcedureMatEntity::getRouteCode)
				.orderByAsc(MdmProcessProcedureMatEntity::getRouteNodeCode)
				.orderByAsc(MdmProcessProcedureMatEntity::getProcessCode)
				.orderByAsc(MdmProcessProcedureMatEntity::getStep));
	}

	/**
	 * 根据id设置默认工艺路线
	 *
	 * @param id id
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean routeDefault(Long id) {
		MdmProcessRouteEntity processRoute = this.getById(id);
		this.update(
				new LambdaUpdateWrapper<MdmProcessRouteEntity>().set(MdmProcessRouteEntity::getIsDefault, Boolean.FALSE)
						.ne(MdmProcessRouteEntity::getId, id)
						.eq(MdmProcessRouteEntity::getProductCode, processRoute.getProductCode()));
		return this.update(
				new LambdaUpdateWrapper<MdmProcessRouteEntity>().set(MdmProcessRouteEntity::getIsDefault, Boolean.TRUE)
						.eq(MdmProcessRouteEntity::getId, id));
	}

	/**
	 * 查询工艺路线下工序的设置信息
	 *
	 * @param routeCode   工艺路线编码
	 * @param processCode 工序编码
	 * @return R
	 */
	@Override
	public MdmProcessProcedureSettingEntity getSetByCode(String routeCode, String processCode) {
		return mdmProcessProcedureSettingMapper.selectOne(settingQuery(
				routeNodeResolver.resolve(routeCode, null, processCode, false)));
	}

	/**
	 * 新页面精确选择节点；不能回退到主工序再次查询。
	 */
	@Override
	public MdmProcessProcedureSettingEntity getSetByNode(String routeCode, String routeNodeCode) {
		return mdmProcessProcedureSettingMapper.selectOne(settingQuery(
				routeNodeResolver.resolve(routeCode, routeNodeCode, null, false)));
	}

	/**
	 * 修改工艺路线下工序的设置信息
	 *
	 * @param mdmProcessProcedureSetting 工艺路线-工序设置
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean saveOrUpdateSet(MdmProcessProcedureSettingEntity mdmProcessProcedureSetting) {
		if (mdmProcessProcedureSetting == null) {
			throw new CheckedException("工序配置不能为空");
		}
		MdmProcessRouteWorkingEntity node = routeNodeResolver.resolve(mdmProcessProcedureSetting.getRouteCode(),
				mdmProcessProcedureSetting.getRouteNodeCode(), mdmProcessProcedureSetting.getProcessCode(), true);
		MdmProcessProcedureSettingEntity row = mdmProcessProcedureSettingMapper.selectOne(settingQuery(node));
		if (mdmProcessProcedureSetting.getId() != null) {
			if (row == null || !Objects.equals(row.getId(), mdmProcessProcedureSetting.getId())) {
				throw new CheckedException("工序配置身份已失效或不属于当前节点，请刷新");
			}
		} else if (row != null) {
			throw new CheckedException("当前节点已有配置，请刷新后修改，不可重复创建");
		}
		boolean perMaterial = row != null && "MATERIAL_CONSUMPTION_V2".equals(row.getMaterialConsumptionSchema());
		if (perMaterial && (!"MATERIAL_CONSUMPTION_V2".equals(mdmProcessProcedureSetting.getMaterialConsumptionSchema())
				|| mdmProcessProcedureSetting.getIsBackFlush() != null)
				|| !perMaterial && mdmProcessProcedureSetting.getMaterialConsumptionSchema() != null) {
			throw new CheckedException("逐物料耗料请通过工序用料完整配置，不能用旧统一开关覆盖");
		}
		if (Boolean.TRUE.equals(mdmProcessProcedureSetting.getIsMatWeightConvert())
				&& (mdmProcessProcedureSetting.getMatWeightConvert() == null
				|| mdmProcessProcedureSetting.getMatWeightConvert().signum() <= 0)) {
			throw new CheckedException("产料单位重量必须大于零");
		}
		boolean insert = row == null;
		if (insert) {
			row = new MdmProcessProcedureSettingEntity();
			row.setTenantId(node.getTenantId());
			row.setFactoryCode(node.getFactoryCode());
			row.setRouteCode(node.getRouteCode());
			row.setRouteNodeCode(node.getRouteNodeCode());
			row.setProcessCode(node.getProcessCode());
		}
		// 更新只复制业务值，保留真实行身份和创建审计；更新审计交给当前登录人填充。
		row.setIsMatWeightConvert(mdmProcessProcedureSetting.getIsMatWeightConvert());
		row.setMatWeightConvert(mdmProcessProcedureSetting.getMatWeightConvert());
		row.setIsBackFlush(mdmProcessProcedureSetting.getIsBackFlush());
		row.setIsAutomate(mdmProcessProcedureSetting.getIsAutomate());
		row.setDefaultReportNum(mdmProcessProcedureSetting.getDefaultReportNum());
		row.setTimeCheck(mdmProcessProcedureSetting.getTimeCheck());
		row.setTimeVal(mdmProcessProcedureSetting.getTimeVal());
		row.setUpdateBy(null);
		row.setUpdateTime(null);
		int changed = insert ? mdmProcessProcedureSettingMapper.insert(
				row) : mdmProcessProcedureSettingMapper.updateById(row);
		if (changed != 1) {
			throw new CheckedException("工序配置保存失败，请刷新后核对");
		}
		return Boolean.TRUE;
	}

	/**
	 * 明确移除配置依赖，不把所有开关关闭误认为已经解绑。
	 * 已激活逐料策略须先显式切回统一方式；不能通过删除设置绕过完整性校验。
	 *
	 * @param id 当前实际租户、工厂内的设置主键
	 * @return 是否成功移除；未知归属或仍有逐料依赖时拒绝
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean removeSetting(Long id) {
		String factory = currentRouteFactory();
		MdmProcessProcedureSettingEntity row = mdmProcessProcedureSettingMapper.selectOne(
				new LambdaQueryWrapper<MdmProcessProcedureSettingEntity>()
						.eq(MdmProcessProcedureSettingEntity::getId, id)
						.eq(MdmProcessProcedureSettingEntity::getFactoryCode, factory));
		if (row == null) {
			throw new CheckedException("配置不存在或不属于当前工厂");
		}
		if ("MATERIAL_CONSUMPTION_V2".equals(row.getMaterialConsumptionSchema())) {
			throw new CheckedException(
					"当前节点使用逐物料规则，请先在用料配置中明确切换为统一投料或统一倒冲，再移除配置");
		}
		MdmProcessRouteEntity parent = baseMapper.selectOne(new LambdaQueryWrapper<MdmProcessRouteEntity>()
				.eq(MdmProcessRouteEntity::getFactoryCode, factory)
				.eq(MdmProcessRouteEntity::getRouteCode, row.getRouteCode()).last("FOR UPDATE"));
		if (parent == null || mdmProcessProcedureSettingMapper.deleteById(row.getId()) != 1) {
			throw new CheckedException("配置移除失败，请刷新后核对");
		}
		return Boolean.TRUE;
	}

	/**
	 * 所有配置查询同时限定真实组织、路线和节点，不按主工序混合各次配置。
	 */
	private LambdaQueryWrapper<MdmProcessProcedureSopEntity> sopQuery(MdmProcessRouteWorkingEntity node) {
		return new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
				.eq(MdmProcessProcedureSopEntity::getTenantId, node.getTenantId())
				.eq(MdmProcessProcedureSopEntity::getFactoryCode, node.getFactoryCode())
				.eq(MdmProcessProcedureSopEntity::getRouteCode, node.getRouteCode())
				.eq(MdmProcessProcedureSopEntity::getRouteNodeCode, node.getRouteNodeCode());
	}

	private List<MdmProcedureSopVO> sopViews(MdmProcessRouteWorkingEntity node) {
		return mdmProcessProcedureSopMapper.selectList(sopQuery(node)).stream().map(item -> {
			MdmProcedureSopVO view = new MdmProcedureSopVO();
			BeanUtils.copyProperties(item, view);
			view.setFileUrl(item.getFileName());
			view.setAttachmentHash(MdmSopWorkspace.legacyHash(item));
			return view;
		}).collect(Collectors.toList());
	}

	private LambdaQueryWrapper<MdmProcessProcedureMatEntity> matQuery(MdmProcessRouteWorkingEntity node) {
		return new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
				.eq(MdmProcessProcedureMatEntity::getTenantId, node.getTenantId())
				.eq(MdmProcessProcedureMatEntity::getFactoryCode, node.getFactoryCode())
				.eq(MdmProcessProcedureMatEntity::getRouteCode, node.getRouteCode())
				.eq(MdmProcessProcedureMatEntity::getRouteNodeCode, node.getRouteNodeCode());
	}

	private LambdaQueryWrapper<MdmProcessProcedureSettingEntity> settingQuery(MdmProcessRouteWorkingEntity node) {
		return new LambdaQueryWrapper<MdmProcessProcedureSettingEntity>()
				.eq(MdmProcessProcedureSettingEntity::getTenantId, node.getTenantId())
				.eq(MdmProcessProcedureSettingEntity::getFactoryCode, node.getFactoryCode())
				.eq(MdmProcessProcedureSettingEntity::getRouteCode, node.getRouteCode())
				.eq(MdmProcessProcedureSettingEntity::getRouteNodeCode, node.getRouteNodeCode());
	}

	private String currentRouteFactory() {
		String factory = FactoryContextHolder.getFactoryCode();
		if (TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantSkip()
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)) {
			throw new CheckedException("路线维护需要明确当前租户和工厂");
		}
		return factory;
	}

}
