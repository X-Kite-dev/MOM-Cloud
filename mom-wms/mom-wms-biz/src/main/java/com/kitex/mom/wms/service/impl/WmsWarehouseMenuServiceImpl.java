package com.kitex.mom.wms.service.impl;

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
import com.kitex.admin.api.vo.UserVO;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.common.security.util.UserUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import com.kitex.mom.wms.enums.menu.*;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.WmsWarehouseBizService;
import com.kitex.mom.wms.service.WmsWarehouseMenuService;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckAppItemVO;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckAppVO;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
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
public class WmsWarehouseMenuServiceImpl extends ServiceImpl<WmsWarehouseMenuMapper, WmsWarehouseMenuEntity> implements WmsWarehouseMenuService {

    private final WmsWarehouseBizService wmsWarehouseBizService;
    private final RemoteWarehouseService remoteWarehouseService;
    private final RemoteMenuService remoteMenuService;
    private final RemoteUserService remoteUserService;

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
        Long parent = parentId == null ? CommonConstants.MENU_TREE_ROOT_ID : parentId;

        List<TreeNode<Long>> collect = baseMapper
                .selectList(Wrappers.<WmsWarehouseMenuEntity>lambdaQuery()
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

        return TreeUtil.build(collect, parent);
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
        R<List<MdmWarehouseEntity>> warehouseListData = remoteWarehouseService.getList(new MdmWarehouseEntity());
        if (warehouseListData.getCode() != 0) {
            throw new CheckedException("获取仓库配置数据失败,请检查主数据是否正常运行！");
        }
        List<MdmWarehouseEntity> warehouseList = warehouseListData.getData();
        List<String> codeList = warehouseList.stream().map(MdmWarehouseEntity::getWarehouseCode).distinct().toList();

        List<WmsWarehouseBizEntity> list = wmsWarehouseBizService.list(new LambdaQueryWrapper<WmsWarehouseBizEntity>()
                .like(StrUtil.isNotBlank(warehouseCode), WmsWarehouseBizEntity::getWarehouseCode, warehouseCode)
        );
        list = list.stream().filter(item -> codeList.contains(item.getWarehouseCode())).collect(Collectors.toList());
        Map<String, List<WmsWarehouseBizEntity>> collect = list.stream().collect(Collectors.groupingBy(WmsWarehouseBizEntity::getWarehouseCode));
        List<WmsWarehouseBizCheckVO> result = new ArrayList<>();
        for (String key : collect.keySet()) {
            result.add(new WmsWarehouseBizCheckVO().setWarehouseCode(key).setBizs(collect.get(key).stream().map(WmsWarehouseBizEntity::getBizType).toList()));
        }
        //查询配置项


        Map<String, MdmWarehouseEntity> warehouseEntityMap = warehouseList.stream().collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, Function.identity(), (a, b) -> a));
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
        List<WmsWarehouseBizEntity> list = wmsWarehouseBizService.list(new LambdaQueryWrapper<WmsWarehouseBizEntity>()
                .eq(StrUtil.isNotBlank(warehouseCode), WmsWarehouseBizEntity::getWarehouseCode, warehouseCode)
        );
        // 查找系统角色的wms菜单
        R<List<Tree<Long>>> menuTree = remoteMenuService.getMenuTree("wms");
        if (!menuTree.isOk()) {
            throw new CheckedException("用户菜单获取失败！" + menuTree.getMsg());
        }
        Tree<Long> tree = menuTree.getData().stream().filter(it -> ("/wms/warehouseManege/" + warehouseCode).equals(it.get("path"))).findFirst().orElse(null);
        if (tree == null) { // 没有菜单，返回空数据
            WmsWarehouseBizCheckAppVO result = new WmsWarehouseBizCheckAppVO();
            result.setWarehouseCode(warehouseCode);
            result.setCommon(new ArrayList<>());
            result.setInout(new ArrayList<>());
            result.setOther(new ArrayList<>());
            return result;
        }
        List<Tree<Long>> treeChildren = tree.getChildren();
        // 这个是pc上有的菜单的path集合，它是由enum的value加上"/仓库编码"构成，用这个来过滤app的菜单
        List<String> pathList = new ArrayList<>();
        for (LinkedHashMap<String, Object> treeChild : treeChildren) {
            String path = (String) treeChild.get("path");
            pathList.add(path);
        }

        Map<String, MenuCommonBizTypeEnum> commonBizMap = Arrays.stream(MenuCommonBizTypeEnum.values()).collect(Collectors.toMap(MenuCommonBizTypeEnum::getValue, Function.identity()));
        Map<String, MenuInBizTypeEnum> inBizMap = Arrays.stream(MenuInBizTypeEnum.values()).collect(Collectors.toMap(MenuInBizTypeEnum::getValue, Function.identity()));
        Map<String, MenuOutBizTypeEnum> outBizMap = Arrays.stream(MenuOutBizTypeEnum.values()).collect(Collectors.toMap(MenuOutBizTypeEnum::getValue, Function.identity()));
        List<WmsWarehouseBizCheckAppItemVO> common = new ArrayList<>();
        List<WmsWarehouseBizCheckAppItemVO> inout = new ArrayList<>();
        List<WmsWarehouseBizCheckAppItemVO> other = new ArrayList<>();
        for (WmsWarehouseBizEntity entity : list) {
            String bizType = entity.getBizType();
            if (commonBizMap.containsKey(bizType)) {
                MenuCommonBizTypeEnum anEnum = commonBizMap.get(bizType);
                if (!pathList.contains(anEnum.getValue() + "/" + warehouseCode)) {
                    continue; // 如果pc上没有这个菜单，app也不显示这个菜单
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
        WmsWarehouseBizCheckAppVO result = new WmsWarehouseBizCheckAppVO();
        result.setWarehouseCode(warehouseCode);
        result.setCommon(common);
        result.setInout(inout);
        result.setOther(other);
        return result;
    }

    /**
     * 提交选择的功能
     */
    @Override
    @Transactional
    public Boolean commitCheckBiz(List<WmsWarehouseBizCheckVO> list) {
        // 获取当前用户的 业务id
        UserVO userInfo = UserUtils.getUserInfo();

        /* 1. 获取仓库列表，过滤不存在的仓库 */
        List<MdmWarehouseEntity> warehouseEntityList = remoteWarehouseService.getList(new MdmWarehouseEntity()).getData();
        List<String> warehouseCodes = warehouseEntityList.stream().map(MdmWarehouseEntity::getWarehouseCode).toList();
        list = list.stream().filter(it -> warehouseCodes.contains(it.getWarehouseCode())).toList();
        // 2. 更新选择项
        wmsWarehouseBizService.updateAll(list);
        // 3. 更新预览菜单
        return updateMenu(list, warehouseEntityList);
    }

    @Transactional
    public Boolean updateMenu(List<WmsWarehouseBizCheckVO> list, List<MdmWarehouseEntity> warehouseEntityList) {
        /* 获取当前工厂编码 */
        String factoryCode = FactoryContextHolder.getFactoryCode();
        /* 1. 删除现有菜单 */
        remove(Wrappers.<WmsWarehouseMenuEntity>lambdaQuery().eq(WmsWarehouseMenuEntity::getFactoryCode, factoryCode));
        /* 2. 转换仓库map */
        Map<String, MdmWarehouseEntity> warehouseMap = warehouseEntityList.stream().collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, it -> it));
        /* 3. 重新添加菜单 */
        // 采用批量插入
        Snowflake snowflake = IdUtil.getSnowflake();
        List<WmsWarehouseMenuEntity> insertMenus = new ArrayList<>();
        for (WmsWarehouseBizCheckVO vo : list) {
            String warehouseCode = vo.getWarehouseCode();
            MdmWarehouseEntity mdmWarehouse = Optional.ofNullable(warehouseMap.get(warehouseCode)).orElseThrow(() -> new CheckedException("仓库查找失败：[" + warehouseCode + "]"));
            List<String> bizs = vo.getBizs();
            // 1、添加一级菜单
            WmsWarehouseMenuEntity warehouseMenu = new WmsWarehouseMenuEntity();
            if (CollUtil.isNotEmpty(bizs)) {
                warehouseMenu.setName(Optional.ofNullable(mdmWarehouse.getWarehouseName()).orElse(warehouseCode) + "管理")
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
                            .setIcon("iconfont icon-shuxingtu");
                    bizMenu.setId(snowflake.nextId());
                    insertMenus.add(bizMenu);
                    // 实时库存还分三个页面，其他都是一个页面
                    if (MenuCommonBizTypeEnum.LIVE_STOCK.equals(typeEnum)) {
                        for (LiveStockMenuTypeEnum liveStockMenuEnum : LiveStockMenuTypeEnum.values()) {
                            // 三级菜单(页面菜单)
                            WmsWarehouseMenuEntity pageMenu = savePageMenu(liveStockMenuEnum.getName(), liveStockMenuEnum.getUrl(), liveStockMenuEnum.getSort(), warehouseCode, biz, bizMenu.getId(), insertMenus, snowflake);
                            // 按钮权限 库存明细的权限是：后边加上页面编码和仓库编码
                            for (ButtonLiveStockTypeEnum value : ButtonLiveStockTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + liveStockMenuEnum.getCode() + "_" + warehouseCode, value.getSort(), pageMenu.getId(), insertMenus, snowflake);
                            }
                        }
                    } else {
                        bizMenu.setComponent(biz).setIcon("iconfont icon-shouye_dongtaihui");
                        // 按钮权限
                        if (MenuCommonBizTypeEnum.IN_BILL.equals(typeEnum)) {
                            // 入库单 权限后边加上仓库编码
                            for (ButtonBillTypeEnum value : ButtonBillTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + InOutMenuTypeEnum.IN_BILL.getType() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.OUT_BILL.equals(typeEnum)) {
                            // 出库单 权限后边加上仓库编码
                            for (ButtonBillTypeEnum value : ButtonBillTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + InOutMenuTypeEnum.OUT_BILL.getType() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.OUT_NOTICE_BATCH.equals(typeEnum)) {
                            // 批量拣货 权限后边加上仓库编码
                            for (ButtonOutNoticeBatchTypeEnum value : ButtonOutNoticeBatchTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.INOUT_FLOW.equals(typeEnum)) {
                            // 出入库流水 权限后边加上仓库编码
                            for (ButtonInoutFlowTypeEnum value : ButtonInoutFlowTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.INVENTORY.equals(typeEnum)) {
                            // 库存盘点 权限后边加上仓库编码
                            for (ButtonInventoryTypeEnum value : ButtonInventoryTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.ALLOCATION.equals(typeEnum)) {
                            // 调拨单 权限后边加上仓库编码
                            for (ButtonAllocationTypeEnum value : ButtonAllocationTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.ALLOCATION_RECEIVE.equals(typeEnum)) {
                            // 调拨接收 权限后边加上仓库编码
                            for (ButtonAllocationReceiveTypeEnum value : ButtonAllocationReceiveTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        } else if (MenuCommonBizTypeEnum.MOVE_SITE.equals(typeEnum)) {
                            // 库内移动 权限后边加上仓库编码
                            for (ButtonMoveSiteTypeEnum value : ButtonMoveSiteTypeEnum.values()) {
                                createButton(value.getName(), value.getCode() + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
                            }
                        }
                    }
                } else {
                    // 入库出库业务
                    MenuInBizTypeEnum inBizTypeEnum = MenuInBizTypeEnum.getByValue(biz);
                    if (inBizTypeEnum != null) {
                        saveInOutBizMenu(inBizTypeEnum.getLabel(), inBizTypeEnum.getCode(), warehouseMenu.getId(), biz, warehouseCode, inBizTypeEnum.getSort(), InOutMenuTypeEnum.IN_NOTICE, insertMenus, snowflake);
                    }
                    MenuOutBizTypeEnum outBizTypeEnum = MenuOutBizTypeEnum.getByValue(biz);
                    if (outBizTypeEnum != null) {
                        saveInOutBizMenu(outBizTypeEnum.getLabel(), outBizTypeEnum.getCode(), warehouseMenu.getId(), biz, warehouseCode, outBizTypeEnum.getSort(), InOutMenuTypeEnum.OUT_NOTICE, insertMenus, snowflake);
                    }

                }
            }
        }

        if (insertMenus.size() > 0) {
            baseMapper.insert(insertMenus.stream().peek(menu -> menu.setFactoryCode(factoryCode)).toList());
        }
        return Boolean.TRUE;
    }

    /**
     * 保存入库出库菜单及子菜单，包括入库通知单、入库单及按钮权限
     *
     * @param name          二级菜单名称
     *                      //	 * @param billCode      业务类型代码
     * @param parentId      一级菜单id
     * @param biz           业务类型
     * @param warehouseCode 仓库编码
     * @param sort          排序
     * @param notice        通知单枚举
     *                      //	 * @param bill          入库出库单枚举
     */
    @Transactional
    public void saveInOutBizMenu(String name, String billCode, Long parentId, String biz, String warehouseCode, Integer sort, InOutMenuTypeEnum notice, List<WmsWarehouseMenuEntity> insertMenus, Snowflake snowflake) {
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

        //修改 三级按钮权限
        for (ButtonNoticeTypeEnum value : ButtonNoticeTypeEnum.values()) {
            createButton(value.getName(), value.getCode() + "_" + billCode + "_" + warehouseCode, value.getSort(), bizMenu.getId(), insertMenus, snowflake);
        }

        // 修改 去除出入库单
//		// 三级菜单 出入库单
//		WmsWarehouseMenuEntity pageMenuBill = savePageMenu(bill.getName(), bill.getUrl(), bill.getSort(), warehouseCode, biz, bizMenu.getId(), insertMenus, snowflake);
//		// 四级 按钮权限 code + 业务类型 + 仓库编码
//		for (ButtonBillTypeEnum value : ButtonBillTypeEnum.values()) {
//			createButton(value.getName(), value.getCode() + "_" + billCode + "_" + warehouseCode, value.getSort(), pageMenuBill.getId(), insertMenus, snowflake);
//		}
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
    public WmsWarehouseMenuEntity savePageMenu(String name, String url, Integer sort, String warehouseCode, String biz, Long parentId, List<WmsWarehouseMenuEntity> insertMenus, Snowflake snowflake) {
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
    public WmsWarehouseMenuEntity createButton(String name, String permission, Integer sort, Long parentId, List<WmsWarehouseMenuEntity> insertMenus, Snowflake snowflake) {
        WmsWarehouseMenuEntity button = new WmsWarehouseMenuEntity();
        button.setName(name)
                .setPermission(permission)
//				.setPath(permission)
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
    public Boolean syncSysMenu() {
        // 1. 获取预览菜单
        List<Tree<Long>> treeMenu = Optional.ofNullable(this.treeMenu(null, null, null)).orElse(new ArrayList<>());
        UserVO userInfo = UserUtils.getUserInfo();


        // 2. 发送到系统模块
        R<Boolean> result = remoteMenuService.syncWarehouseMenu(treeMenu, SecurityUtils.getUser().getId());
        if (!result.isOk()) {
            throw new CheckedException("系统菜单同步失败：" + result.getMsg());
        }
        return true;
    }
}