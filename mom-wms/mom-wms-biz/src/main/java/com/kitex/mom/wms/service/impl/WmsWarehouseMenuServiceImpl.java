package com.kitex.mom.wms.service.impl;

import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeNode;
import cn.hutool.core.lang.tree.TreeUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteMenuService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import com.kitex.mom.wms.enums.menu.*;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.WmsWarehouseBizService;
import com.kitex.mom.wms.service.WmsWarehouseMenuService;
import com.kitex.mom.wms.service.WmsOtherOutboundScope;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckAppItemVO;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckAppVO;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import com.kitex.mom.wms.vo.WmsWarehouseConfigurationSnapshot;
import com.kitex.mom.wms.vo.WmsWarehouseConfigurationCommand;
import com.kitex.mom.wms.service.WmsWarehouseConfigurationRevision;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 仓库菜单配置
 *
 * @author kitex
 * @date 2025-01-13 14:41:50
 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseMenuServiceImpl extends ServiceImpl<WmsWarehouseMenuMapper, WmsWarehouseMenuEntity>
		implements WmsWarehouseMenuService {

	private final com.kitex.mom.wms.service.overreturn.WmsOverReturnAccess overReturnAccess;
	private final WmsWarehouseBizService wmsWarehouseBizService;
	private final RemoteWarehouseService remoteWarehouseService;
	private final RemoteMenuService remoteMenuService;
	private final RemoteUserService remoteUserService;
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalAccess physicalAccess;

	/**
	 * 返回树形菜单集合
	 *
	 * @param parentId 父节点ID
	 * @param menuName 菜单名称
	 * @param type     菜单类型 1 按钮、0 菜单
	 * @return 树形菜单
	 */
	@Override
	public List<Tree<Long>> treeMenu(Long parentId, String menuName, String type) {
		requireScope();
		Long parent = parentId == null ? CommonConstants.MENU_TREE_ROOT_ID : parentId;

		List<TreeNode<Long>> collect = baseMapper
				.selectList(Wrappers.<WmsWarehouseMenuEntity>lambdaQuery()
						.eq(WmsWarehouseMenuEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsWarehouseMenuEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
						.eq(Objects.nonNull(parentId), WmsWarehouseMenuEntity::getParentId, parentId)
						.like(StrUtil.isNotBlank(menuName), WmsWarehouseMenuEntity::getName, menuName)
						.eq(StrUtil.isNotBlank(type), WmsWarehouseMenuEntity::getMenuType, type)
						.orderByAsc(WmsWarehouseMenuEntity::getSortOrder))
				.stream()
				.map(getNodeFunction())
				.collect(Collectors.toList());

		// 模糊查询 不组装树结构 直接返回 表格方便编辑
		if (StrUtil.isNotBlank(menuName)) {
			return collect.stream().map(node -> {
				Tree<Long> tree = new Tree<>();
				tree.putAll(node.getExtra());
				BeanUtils.copyProperties(node, tree);
				return tree;
			}).collect(Collectors.toList());
		}

		return Optional.ofNullable(TreeUtil.build(collect, parent)).orElseGet(ArrayList::new);
	}

	@NotNull
	private Function<WmsWarehouseMenuEntity, TreeNode<Long>> getNodeFunction() {
		return menu -> {
			TreeNode<Long> node = new TreeNode<>();
			node.setId(menu.getId());
			node.setName(menu.getName());
			node.setParentId(menu.getParentId());
			node.setWeight(menu.getSortOrder());
			// 扩展属性
			Map<String, Object> extra = new HashMap<>();
			extra.put("path", menu.getPath());
			extra.put("component", menu.getComponent());
			extra.put("menuType", menu.getMenuType());
			extra.put("permission", menu.getPermission());
			extra.put("sortOrder", menu.getSortOrder());
			extra.put("icon", menu.getIcon());
			node.setExtra(extra);
			return node;
		};
	}

	/**
	 * 查找仓库业务选择集合
	 *
	 * @param warehouseCode 仓库编码
	 * @return 全部集合
	 */
	@Override
	public List<WmsWarehouseBizCheckVO> listCheckBiz(String warehouseCode) {
		requireScope();
		List<MdmWarehouseEntity> warehouseList = warehouseMasters();
		return configurationSelections(warehouseCode, warehouseList);
	}

	private List<WmsWarehouseBizCheckVO> configurationSelections(String warehouseCode,
	                                                             List<MdmWarehouseEntity> warehouseList) {
		List<String> codeList = warehouseList.stream().map(MdmWarehouseEntity::getWarehouseCode).distinct()
				.toList();

		List<WmsWarehouseBizEntity> list = wmsWarehouseBizService.list(
				new LambdaQueryWrapper<WmsWarehouseBizEntity>()
						.eq(WmsWarehouseBizEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsWarehouseBizEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
						.like(StrUtil.isNotBlank(warehouseCode), WmsWarehouseBizEntity::getWarehouseCode,
								warehouseCode));
		list = list.stream().filter(item -> codeList.contains(item.getWarehouseCode()))
				.collect(Collectors.toList());
		Map<String, List<WmsWarehouseBizEntity>> collect = list.stream()
				.collect(Collectors.groupingBy(WmsWarehouseBizEntity::getWarehouseCode));
		List<WmsWarehouseBizCheckVO> result = new ArrayList<>();
		for (String key : collect.keySet()) {
			result.add(new WmsWarehouseBizCheckVO().setWarehouseCode(key)
					.setBizs(collect.get(key).stream().map(WmsWarehouseBizEntity::getBizType)
							.filter(biz -> !WmsWarehouseBizService.NO_BUSINESS.equals(biz)).toList()));
		}
		// 查询配置项

		Map<String, MdmWarehouseEntity> warehouseEntityMap = warehouseList.stream()
				.collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, Function.identity(),
						(a, b) -> a));
		for (WmsWarehouseBizCheckVO bizCheckVO : result) {
			MdmWarehouseEntity mdmWarehouseEntity = warehouseEntityMap.get(bizCheckVO.getWarehouseCode());
			if (ObjectUtil.isEmpty(mdmWarehouseEntity)) {
				throw new CheckedException("仓库编码：" + bizCheckVO.getWarehouseCode() + "不存在,");
			}
			bizCheckVO.setIsBatch(mdmWarehouseEntity.getIsBatch());
			bizCheckVO.setIsContainer(mdmWarehouseEntity.getIsContainer());

		}
		return result;
	}

	/**
	 * 查找一个仓库的业务选择集合
	 */
	@Override
	public WmsWarehouseBizCheckAppVO checkBizByWarehouse(String warehouseCode) {
		requireScope();
		if (StrUtil.isBlank(warehouseCode)) {
			throw new CheckedException("仓库编码不能为空");
		}
		if (!warehouseGuard.mobileEnabled(warehouseCode)) {
			return new WmsWarehouseBizCheckAppVO().setWarehouseCode(warehouseCode)
					.setMobileEnabled(false).setMobileMessage("该仓库未开启手机端作业")
					.setCommon(List.of()).setInout(List.of()).setOther(List.of());
		}
		List<WmsWarehouseBizEntity> list = wmsWarehouseBizService.list(
				new LambdaQueryWrapper<WmsWarehouseBizEntity>()
						.eq(WmsWarehouseBizEntity::getWarehouseCode, warehouseCode)
						.eq(WmsWarehouseBizEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsWarehouseBizEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()));
		R<List<Tree<Long>>> menuTree = remoteMenuService.getMenuTree("wms");
		if (menuTree == null || !menuTree.isOk() || menuTree.getData() == null) {
			throw new CheckedException("用户菜单获取失败，请刷新后重试");
		}
		String rootPath = "/wms/warehouseManege/" + FactoryContextHolder.getFactoryCode() + "/" + warehouseCode;
		Tree<Long> tree = findWarehouseTree(menuTree.getData(), rootPath);
		if (tree == null) {
			WmsWarehouseBizCheckAppVO result = new WmsWarehouseBizCheckAppVO();
			result.setWarehouseCode(warehouseCode);
			result.setMobileEnabled(true);
			result.setMobileMessage("");
			result.setCommon(new ArrayList<>());
			result.setInout(new ArrayList<>());
			result.setOther(new ArrayList<>());
			return result;
		}
		List<Tree<Long>> treeChildren = Optional.ofNullable(tree.getChildren()).orElse(List.of());
		// 这个是pc上有的菜单的path集合，它是由enum的value加上"/仓库编码"构成，用这个来过滤app的菜单
		List<String> pathList = new ArrayList<>();
		for (LinkedHashMap<String, Object> treeChild : treeChildren) {
			String path = (String) treeChild.get("path");
			pathList.add(path);
		}
		Map<String, MenuCommonBizTypeEnum> commonBizMap = Arrays.stream(MenuCommonBizTypeEnum.values())
				.collect(Collectors.toMap(MenuCommonBizTypeEnum::getValue, Function.identity()));
		Map<String, MenuInBizTypeEnum> inBizMap = Arrays.stream(MenuInBizTypeEnum.values())
				.collect(Collectors.toMap(MenuInBizTypeEnum::getValue, Function.identity()));
		Map<String, MenuOutBizTypeEnum> outBizMap = Arrays.stream(MenuOutBizTypeEnum.values())
				.collect(Collectors.toMap(MenuOutBizTypeEnum::getValue, Function.identity()));
		List<WmsWarehouseBizCheckAppItemVO> common = new ArrayList<>();
		List<WmsWarehouseBizCheckAppItemVO> inout = new ArrayList<>();
		List<WmsWarehouseBizCheckAppItemVO> other = new ArrayList<>();
		for (WmsWarehouseBizEntity entity : list) {
			String bizType = entity.getBizType();
			if (commonBizMap.containsKey(bizType)) {
				MenuCommonBizTypeEnum anEnum = commonBizMap.get(bizType);
				if (!pathList.contains(anEnum.getValue() + "/" + warehouseCode)) {
					continue;
					// 如果pc上没有这个菜单，app也不显示这个菜单
				}
				if (StrUtil.isNotBlank(anEnum.getAppPath())) {
					WmsWarehouseBizCheckAppItemVO vo = new WmsWarehouseBizCheckAppItemVO();
					vo.setCode(anEnum.getCode());
					vo.setName(anEnum.getLabel());
					vo.setPath(anEnum.getAppPath());
					common.add(vo);
				}
			} else if (inBizMap.containsKey(bizType)) {
				MenuInBizTypeEnum anEnum = inBizMap.get(bizType);
				if (!pathList
						.contains(anEnum.getValue() + InOutMenuTypeEnum.IN_NOTICE.getUrl() + "/" +
								warehouseCode)) {
					continue;
				}
				if (StrUtil.isNotBlank(anEnum.getAppPath())) {
					WmsWarehouseBizCheckAppItemVO vo = new WmsWarehouseBizCheckAppItemVO();
					vo.setCode(anEnum.getCode());
					vo.setName(anEnum.getLabel());
					vo.setPath(anEnum.getAppPath());
					if (MenuInBizTypeEnum.OTHER_IN.equals(anEnum)) {
						other.add(vo);
					} else {
						inout.add(vo);
					}
				}
			} else if (outBizMap.containsKey(bizType)) {
				MenuOutBizTypeEnum anEnum = outBizMap.get(bizType);
				if (!pathList
						.contains(anEnum.getValue() + InOutMenuTypeEnum.OUT_NOTICE.getUrl() + "/" +
								warehouseCode)) {
					continue;
				}
				if (StrUtil.isNotBlank(anEnum.getAppPath())) {
					WmsWarehouseBizCheckAppItemVO vo = new WmsWarehouseBizCheckAppItemVO();
					vo.setCode(anEnum.getCode());
					vo.setName(anEnum.getLabel());
					vo.setPath(anEnum.getAppPath());
					if (MenuOutBizTypeEnum.OTHER_OUT.equals(anEnum)) {
						other.add(vo);
					} else {
						inout.add(vo);
					}
				}
			}
		}
		// 附加入口只投影已配置实时库存与真实quality子页show，不新增全局菜单或授权。
		if (list.stream().anyMatch(e -> MenuCommonBizTypeEnum.LIVE_STOCK.getValue().equals(e.getBizType()))
				&& pathList.contains(MenuCommonBizTypeEnum.LIVE_STOCK.getValue() + "/" + warehouseCode)) {
			physicalAccess.read();
			if (physicalAccess.allowed(List.of(warehouseCode), "show")) {
				WmsWarehouseBizCheckAppItemVO quality = new WmsWarehouseBizCheckAppItemVO();
				quality.setCode("quality_execution");
				quality.setName("质量处置");
				quality.setPath("/pages-wms/warehouseIn/live/qualityExecution");
				common.add(quality);
			}
		}
		// 源调拨配置及真实仓级view来源才附加返还实收，不覆盖原ALLOCATION路径。
		if (list.stream().anyMatch(e -> MenuCommonBizTypeEnum.ALLOCATION.getValue().equals(e.getBizType()))
				&& pathList.contains(MenuCommonBizTypeEnum.ALLOCATION.getValue() + "/" + warehouseCode)
				&& overReturnAccess.allowed(warehouseCode, "view", false)) {
			WmsWarehouseBizCheckAppItemVO receipt = new WmsWarehouseBizCheckAppItemVO();
			receipt.setCode("over_return_receipt");
			receipt.setName("溢收退回收货");
			receipt.setPath("/pages-wms/order/allocationBill/overReturn");
			common.add(receipt);
		}
		WmsWarehouseBizCheckAppVO result = new WmsWarehouseBizCheckAppVO();
		result.setWarehouseCode(warehouseCode);
		result.setMobileEnabled(true);
		result.setMobileMessage("");
		result.setCommon(common);
		result.setInout(inout);
		result.setOther(other);
		return result;
	}

	/**
	 * 提交选择的功能
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean commitCheckBiz(List<WmsWarehouseBizCheckVO> list) {
		requireScope();
		lockConfiguration();
		List<MdmWarehouseEntity> masters = warehouseMasters();
		validateCommands(list, masters);
		if (!wmsWarehouseBizService.updateAll(list)) {
			throw new CheckedException("保存仓库业务配置失败");
		}
		return updateMenu(list, masters);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public WmsWarehouseConfigurationSnapshot configurationSnapshot() {
		requireScope();
		lockConfiguration();
		return configurationSnapshot(warehouseMasters());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public WmsWarehouseConfigurationSnapshot commitConfiguration(WmsWarehouseConfigurationCommand command) {
		requireScope();
		if (command == null || command.expectedRevision() == null
				|| !command.expectedRevision().matches("[0-9a-f]{64}")) {
			throw new CheckedException("配置版本缺失，请刷新页面后保存");
		}
		lockConfiguration();
		List<MdmWarehouseEntity> masters = warehouseMasters();
		if (!command.expectedRevision().equals(configurationSnapshot(masters).revision())) {
			throw new CheckedException("仓库配置已被更新，请保留本机修改并刷新后重新核对");
		}
		validateCommands(command.warehouses(), masters);
		if (!wmsWarehouseBizService.updateAll(command.warehouses()) ||
				!Boolean.TRUE.equals(updateMenu(command.warehouses(), masters))) {
			throw new CheckedException("保存仓库业务配置失败");
		}
		return configurationSnapshot(masters);
	}

	private WmsWarehouseConfigurationSnapshot configurationSnapshot(List<MdmWarehouseEntity> masters) {
		var warehouses = masters.stream().sorted(Comparator.comparing(MdmWarehouseEntity::getWarehouseCode))
				.map(row -> new WmsWarehouseConfigurationSnapshot.Warehouse(row.getWarehouseCode(),
						row.getWarehouseName(),
						row.getIsBatch(), row.getIsContainer(), row.getIsMobile(), row.getSort())).toList();
		var selections = configurationSelections(null, masters).stream()
				.sorted(Comparator.comparing(WmsWarehouseBizCheckVO::getWarehouseCode)).toList();
		List<Tree<Long>> tree = treeMenu(null, null, null);
		if (tree == null) {
			throw new CheckedException("仓库预览读取失败，请刷新后重试");
		}
		String tenant = TenantContextHolder.getTenantId().toString();
		String factory = FactoryContextHolder.getFactoryCode();
		String revision = WmsWarehouseConfigurationRevision.calculate(tenant, factory, warehouses, selections,
				tree);
		// 产品能力目录沿菜单生成枚举返回，防止旧系统字典漏项使新业务无法开通。
		// 目录只表示服务支持的范围，启用、规则保存及角色授权仍分别走原合同。
		var commonBusinesses = Arrays.stream(MenuCommonBizTypeEnum.values())
				.sorted(Comparator.comparing(MenuCommonBizTypeEnum::getSort))
				.map(item -> new WmsWarehouseConfigurationSnapshot.BusinessOption(item.getValue(),
						item.getLabel(), item.getSort())).toList();
		return new WmsWarehouseConfigurationSnapshot(1, tenant, factory, revision, warehouses, selections,
				tree, commonBusinesses);
	}

	private void requireScope() {
		if (TenantContextHolder.getTenantId() == null || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
				|| Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
			throw new CheckedException("仓库配置必须具有明确的租户和工厂上下文");
		}
	}

	private void lockConfiguration() {
		baseMapper.lockConfiguration("WMS_WAREHOUSE_MENU:" + TenantContextHolder.getTenantId()
				+ ":" + FactoryContextHolder.getFactoryCode());
	}

	private List<MdmWarehouseEntity> warehouseMasters() {
		R<List<MdmWarehouseEntity>> response = remoteWarehouseService.getMenuConfigurationWarehouses();
		if (response == null || !response.isOk() || response.getData() == null) {
			throw new CheckedException("获取仓库主数据失败，禁止替换配置");
		}
		Set<String> seen = new HashSet<>();
		for (MdmWarehouseEntity warehouse : response.getData()) {
			if (warehouse == null || StrUtil.isBlank(warehouse.getWarehouseCode())
					|| warehouse.getStatus() == null || !seen.add(warehouse.getWarehouseCode())
					|| !Objects.equals(warehouse.getTenantId(), TenantContextHolder.getTenantId())
					|| !Objects.equals(warehouse.getFactoryCode(), FactoryContextHolder.getFactoryCode())) {
				throw new CheckedException("仓库主数据重复或组织范围不一致，请检查基础数据");
			}
		}
		return response.getData().stream().filter(warehouse -> Boolean.TRUE.equals(warehouse.getStatus()))
				.toList();
	}

	private void validateCommands(List<WmsWarehouseBizCheckVO> list, List<MdmWarehouseEntity> masters) {
		if (list == null) {
			throw new CheckedException("仓库配置快照不能为空");
		}
		Set<String> warehouses = masters.stream().map(MdmWarehouseEntity::getWarehouseCode)
				.collect(Collectors.toSet());
		Set<String> submitted = new HashSet<>();
		for (WmsWarehouseBizCheckVO command : list) {
			if (command == null || !warehouses.contains(command.getWarehouseCode())
					|| !submitted.add(command.getWarehouseCode()) || command.getBizs() == null) {
				throw new CheckedException("仓库配置重复、缺失或仓库已变更，请重新加载");
			}
			Set<String> business = new HashSet<>();
			for (String value : command.getBizs()) {
				if (StrUtil.isBlank(value) || !business.add(value) ||
						(MenuCommonBizTypeEnum.getByValue(value) == null
								&& MenuInBizTypeEnum.getByValue(value) == null
								&& MenuOutBizTypeEnum.getByValue(value) == null
								&& !WmsOtherOutboundScope.isConfigurationValue(value)
								&& !ReturnReceiptActionEnum.isConfigurationValue(value))) {
					throw new CheckedException("仓库业务功能无效或重复，请重新加载");
				}
			}
			WmsOtherOutboundScope.validateSelection(business);
			if (!ReturnReceiptActionEnum.validSelection(business)) {
				throw new CheckedException("退料实收能力必须启用生产退料及实收详情");
			}
		}
		// 这是完整替换接口，漏传新仓库不可解释为删除它的配置。
		if (!submitted.equals(warehouses)) {
			throw new CheckedException("仓库列表已经变化，请重新加载完整仓库配置后保存");
		}
	}

	private Tree<Long> findWarehouseTree(List<Tree<Long>> trees, String path) {
		for (Tree<Long> tree : trees) {
			if (path.equals(tree.get("path"))) {
				return tree;
			}
			if (tree.getChildren() != null) {
				Tree<Long> found = findWarehouseTree(tree.getChildren(), path);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	@Transactional(rollbackFor = Exception.class)
	public Boolean updateMenu(List<WmsWarehouseBizCheckVO> list, List<MdmWarehouseEntity> warehouseEntityList) {
		/* 获取当前工厂编码 */
		String factoryCode = FactoryContextHolder.getFactoryCode();
		// 只替换本次启用仓库的子树；停用仓库的预览ID和配置保持可恢复。
		Set<String> activeRoots = list.stream()
				.map(command -> "/wms/warehouseManege/" + factoryCode + "/" + command.getWarehouseCode())
				.collect(Collectors.toSet());
		var scope = Wrappers.<WmsWarehouseMenuEntity>lambdaQuery()
				.eq(WmsWarehouseMenuEntity::getFactoryCode, factoryCode)
				.eq(WmsWarehouseMenuEntity::getTenantId, TenantContextHolder.getTenantId());
		if (!activeRoots.isEmpty()) {
			List<WmsWarehouseMenuEntity> previous = baseMapper.selectList(scope);
			if (previous == null) {
				throw new CheckedException("获取旧仓库预览失败，配置未保存");
			}
			Set<Long> ids = new HashSet<>();
			for (WmsWarehouseMenuEntity row : previous) {
				if (activeRoots.contains(row.getPath())) {
					ids.add(row.getId());
				}
			}
			boolean expanded;
			do {
				expanded = false;
				for (WmsWarehouseMenuEntity row : previous) {
					if (ids.contains(row.getParentId())) {
						expanded |= ids.add(row.getId());
					}
				}
			} while (expanded);
			if (!ids.isEmpty() &&
					baseMapper.delete(scope.in(WmsWarehouseMenuEntity::getId, ids)) != ids.size()) {
				throw new CheckedException("清理旧仓库预览失败，配置未保存");
			}
		}
		/* 2. 转换仓库map */
		Map<String, MdmWarehouseEntity> warehouseMap = warehouseEntityList.stream()
				.collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, it -> it));
		/* 3. 重新添加菜单 */
		// 先构建完整预览，再逐条确认持久化结果。
		Snowflake snowflake = IdUtil.getSnowflake();
		List<WmsWarehouseMenuEntity> insertMenus = new ArrayList<>();
		for (WmsWarehouseBizCheckVO vo : list) {
			String warehouseCode = vo.getWarehouseCode();
			MdmWarehouseEntity mdmWarehouse = Optional.ofNullable(warehouseMap.get(warehouseCode))
					.orElseThrow(() -> new CheckedException("仓库查找失败：[" + warehouseCode + "]"));
			List<String> bizs = vo.getBizs();
			// 1、添加一级菜单
			WmsWarehouseMenuEntity warehouseMenu = new WmsWarehouseMenuEntity();
			if (CollUtil.isNotEmpty(bizs)) {
				warehouseMenu.setName(
								Optional.ofNullable(mdmWarehouse.getWarehouseName()).orElse(warehouseCode) + "管理")
						.setMenuType(MenuTypeEnum.LEFT_MENU.getCode())
						.setParentId(CommonConstants.MENU_TREE_ROOT_ID)
						.setPath(String.format("/wms/warehouseManege/%s/%s", factoryCode, warehouseCode))
						.setSortOrder(Optional.ofNullable(mdmWarehouse.getSort()).orElse(999))
						.setIcon("iconfont icon-quanjushezhi_o");
				long snowId = snowflake.nextId();
				warehouseMenu.setId(snowId);
				insertMenus.add(warehouseMenu);
			}
			// 2、循环添加业务菜单
			for (String biz : bizs) {
				// 判断是入库出库还是通用业务的
				MenuCommonBizTypeEnum typeEnum = MenuCommonBizTypeEnum.getByValue(biz);
				if (typeEnum != null) { // 通用业务
					// 二级菜单
					WmsWarehouseMenuEntity bizMenu = new WmsWarehouseMenuEntity();
					bizMenu.setName(typeEnum.getLabel())
							.setMenuType(MenuTypeEnum.LEFT_MENU.getCode())
							.setParentId(warehouseMenu.getId())
							.setPath(biz + "/" + warehouseCode)
							.setSortOrder(typeEnum.getSort())
							.setIcon(MenuCommonBizTypeEnum.SPECIAL_MOVEMENT.equals(
									typeEnum) ? "ele-Tickets" : "iconfont icon-shuxingtu");
					bizMenu.setId(snowflake.nextId());
					insertMenus.add(bizMenu);
					// 实时库存还分三个页面，其他都是一个页面
					if (MenuCommonBizTypeEnum.LIVE_STOCK.equals(typeEnum)) {
						for (LiveStockMenuTypeEnum liveStockMenuEnum : LiveStockMenuTypeEnum.values()) {
							// 三级菜单(页面菜单)
							WmsWarehouseMenuEntity pageMenu = savePageMenu(liveStockMenuEnum.getName(),
									liveStockMenuEnum.getUrl(), liveStockMenuEnum.getSort(), warehouseCode, biz,
									bizMenu.getId(), insertMenus, snowflake);
							// 按钮权限 库存明细的权限是：后边加上页面编码和仓库编码
							if (LiveStockMenuTypeEnum.QUALITY_STATUS.equals(liveStockMenuEnum)) {
								createButton("查看", "wmsQualityStatus:show", 1, pageMenu.getId(), insertMenus,
										snowflake);
								createButton("重试同步", "wmsQualityStatus:retry", 2, pageMenu.getId(),
										insertMenus,
										snowflake);
								createButton("确认实物执行", "wmsQualityStatus:execute", 3, pageMenu.getId(),
										insertMenus,
										snowflake);
								createButton("返工库存处理", "wmsQualityStatus:rework", 4, pageMenu.getId(),
										insertMenus, snowflake);
							} else {
								for (ButtonLiveStockTypeEnum value : ButtonLiveStockTypeEnum.values()) {
									createButton(value.getName(),
											value.getCode() + "_" + liveStockMenuEnum.getCode() + "_" +
													warehouseCode,
											value.getSort(), pageMenu.getId(), insertMenus, snowflake);
								}
							}
						}
					} else {
						bizMenu.setComponent(biz).setIcon("iconfont icon-shouye_dongtaihui");
						// 按钮权限
						if (MenuCommonBizTypeEnum.IN_BILL.equals(typeEnum)) {
							// 入库单 权限后边加上仓库编码
							for (ButtonBillTypeEnum value : ButtonBillTypeEnum.values()) {
								createButton(value.getName(),
										value.getCode() + "_" + InOutMenuTypeEnum.IN_BILL.getType() + "_"
												+ warehouseCode,
										value.getSort(), bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.OUT_BILL.equals(typeEnum)) {
							// 出库单 权限后边加上仓库编码
							for (ButtonBillTypeEnum value : ButtonBillTypeEnum.values()) {
								createButton(value.getName(),
										value.getCode() + "_" + InOutMenuTypeEnum.OUT_BILL.getType() + "_"
												+ warehouseCode,
										value.getSort(), bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.OUT_NOTICE_BATCH.equals(typeEnum)) {
							// 批量拣货 权限后边加上仓库编码
							for (ButtonOutNoticeBatchTypeEnum value : ButtonOutNoticeBatchTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.INOUT_FLOW.equals(typeEnum)) {
							// 出入库流水 权限后边加上仓库编码
							for (ButtonInoutFlowTypeEnum value : ButtonInoutFlowTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.INVENTORY.equals(typeEnum)) {
							// 库存盘点 权限后边加上仓库编码
							for (ButtonInventoryTypeEnum value : ButtonInventoryTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.ALLOCATION.equals(typeEnum)) {
							// 调拨单 权限后边加上仓库编码
							for (ButtonAllocationTypeEnum value : ButtonAllocationTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.ALLOCATION_RECEIVE.equals(typeEnum)) {
							// 调拨接收 权限后边加上仓库编码
							for (ButtonAllocationReceiveTypeEnum value : ButtonAllocationReceiveTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.ORGANIZATION_TRANSFER.equals(typeEnum)) {
							for (var value : ButtonOrganizationTransferTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.SPECIAL_MOVEMENT.equals(typeEnum)) {
							for (ButtonSpecialMovementTypeEnum value : ButtonSpecialMovementTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						} else if (MenuCommonBizTypeEnum.MOVE_SITE.equals(typeEnum)) {
							// 库内移动 权限后边加上仓库编码
							for (ButtonMoveSiteTypeEnum value : ButtonMoveSiteTypeEnum.values()) {
								createButton(value.getName(), value.getCode() + "_" + warehouseCode,
										value.getSort(),
										bizMenu.getId(), insertMenus, snowflake);
							}
						}
					}
				} else {
					// 入库出库业务
					MenuInBizTypeEnum inBizTypeEnum = MenuInBizTypeEnum.getByValue(biz);
					if (inBizTypeEnum != null) {
						WmsWarehouseMenuEntity noticeMenu = saveInOutBizMenu(inBizTypeEnum.getLabel(),
								inBizTypeEnum.getCode(), warehouseMenu.getId(), biz,
								warehouseCode, inBizTypeEnum.getSort(), InOutMenuTypeEnum.IN_NOTICE,
								insertMenus,
								snowflake);
						if (inBizTypeEnum == MenuInBizTypeEnum.RETURN) {
							for (ReturnReceiptActionEnum action : ReturnReceiptActionEnum.values()) {
								if (bizs.contains(action.configurationValue())) {
									createButton(action.getLabel(), action.permission(warehouseCode),
											action.getSort(),
											noticeMenu.getId(), insertMenus, snowflake);
								}
							}
						}
					}
					MenuOutBizTypeEnum outBizTypeEnum = MenuOutBizTypeEnum.getByValue(biz);
					if (outBizTypeEnum != null) {
						saveInOutBizMenu(outBizTypeEnum.getLabel(), outBizTypeEnum.getCode(),
								warehouseMenu.getId(),
								biz, warehouseCode, outBizTypeEnum.getSort(), InOutMenuTypeEnum.OUT_NOTICE,
								insertMenus,
								snowflake);
					}

				}
			}
		}

		if (insertMenus.size() > 0) {
			for (WmsWarehouseMenuEntity menu : insertMenus) {
				menu.setFactoryCode(factoryCode);
				menu.setTenantId(TenantContextHolder.getTenantId());
				if (baseMapper.insert(menu) != 1) {
					throw new CheckedException("生成仓库预览失败，配置未保存");
				}
			}
		}
		return Boolean.TRUE;
	}

	/**
	 * 保存入库出库菜单及子菜单，包括入库通知单、入库单及按钮权限
	 *
	 * @param name          二级菜单名称
	 * @param billCode      业务类型代码
	 * @param parentId      一级菜单id
	 * @param biz           业务类型
	 * @param warehouseCode 仓库编码
	 * @param sort          排序
	 * @param notice        通知单枚举
	 */
	@Transactional
	public WmsWarehouseMenuEntity saveInOutBizMenu(String name, String billCode, Long parentId, String biz,
	                                               String warehouseCode,
	                                               Integer sort, InOutMenuTypeEnum notice,
	                                               List<WmsWarehouseMenuEntity> insertMenus,
	                                               Snowflake snowflake) {
		// 二级菜单
		WmsWarehouseMenuEntity bizMenu = new WmsWarehouseMenuEntity();
		bizMenu.setName(name)
				.setMenuType(MenuTypeEnum.LEFT_MENU.getCode())
				.setParentId(parentId)
				.setPath(biz + notice.getUrl() + "/" + warehouseCode)
				.setComponent(biz + notice.getUrl())
				.setSortOrder(sort)
				.setIcon("iconfont icon-shuxingtu")
				.setId(snowflake.nextId());
		insertMenus.add(bizMenu);

		// 修改 三级按钮权限
		for (ButtonNoticeTypeEnum value : ButtonNoticeTypeEnum.values()) {
			createButton(value.getName(), value.getCode() + "_" + billCode + "_" + warehouseCode,
					value.getSort(),
					bizMenu.getId(), insertMenus, snowflake);
		}
		return bizMenu;
	}

	/**
	 * 保存具体页面的菜单
	 *
	 * @param name          菜单名
	 * @param url           相对位置
	 * @param sort          排序
	 * @param warehouseCode 仓库编码
	 * @param biz           业务类型
	 * @param parentId      父id
	 * @return 菜单
	 */
	public WmsWarehouseMenuEntity savePageMenu(String name, String url, Integer sort, String warehouseCode,
	                                           String biz,
	                                           Long parentId, List<WmsWarehouseMenuEntity> insertMenus,
	                                           Snowflake snowflake) {
		WmsWarehouseMenuEntity pageMenu = new WmsWarehouseMenuEntity();
		pageMenu.setName(name)
				.setMenuType(MenuTypeEnum.LEFT_MENU.getCode())
				.setParentId(parentId)
				.setPath(biz + url + "/" + warehouseCode)
				.setComponent(biz + url)
				.setSortOrder(sort)
				.setIcon("iconfont icon-shouye_dongtaihui")
				.setId(snowflake.nextId());
		insertMenus.add(pageMenu);
		return pageMenu;
	}

	/**
	 * 按钮拼装
	 *
	 * @param name       名称
	 * @param permission 权限标识
	 * @param sort       排序
	 * @param parentId   父id
	 */
	public WmsWarehouseMenuEntity createButton(String name, String permission, Integer sort, Long parentId,
	                                           List<WmsWarehouseMenuEntity> insertMenus, Snowflake snowflake) {
		WmsWarehouseMenuEntity button = new WmsWarehouseMenuEntity();
		button.setName(name)
				.setPermission(permission)
				.setParentId(parentId)
				.setSortOrder(sort)
				.setMenuType(MenuTypeEnum.BUTTON.getCode())
				.setId(snowflake.nextId());
		insertMenus.add(button);
		return button;
	}

	/**
	 * 同步系统菜单
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean syncSysMenu() {
		return synchronize(null);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean syncSysMenu(List<Long> expectedIds) {
		if (expectedIds == null) {
			throw new CheckedException("缺少已展示的菜单预览，请重新加载");
		}
		return synchronize(expectedIds);
	}

	private Boolean synchronize(List<Long> expectedIds) {
		requireScope();
		lockConfiguration();
		List<Tree<Long>> tree = this.treeMenu(null, null, null);
		if (tree == null) {
			throw new CheckedException("获取仓库菜单预览失败");
		}
		if (expectedIds != null) {
			Set<Long> current = new HashSet<>();
			collectPreviewIds(tree, current);
			if (expectedIds.stream().anyMatch(Objects::isNull)
					|| new HashSet<>(expectedIds).size() != expectedIds.size()
					|| !current.equals(new HashSet<>(expectedIds))) {
				throw new CheckedException("仓库配置已被其他操作更新，请重新加载预览后再同步");
			}
		}
		R<Boolean> result = remoteMenuService.syncWarehouseMenu(tree);
		if (result == null || !result.isOk() || !Boolean.TRUE.equals(result.getData())) {
			throw new CheckedException(
					"系统菜单同步失败：" + (result == null ? "未收到有效回执" : result.getMsg()));
		}
		return true;
	}

	private void collectPreviewIds(List<Tree<Long>> trees, Set<Long> ids) {
		for (Tree<Long> tree : trees) {
			if (tree.getId() == null || !ids.add(tree.getId())) {
				throw new CheckedException("仓库预览ID无效，请重新生成");
			}
			if (tree.getChildren() != null) {
				collectPreviewIds(tree.getChildren(), ids);
			}
		}
	}
}
