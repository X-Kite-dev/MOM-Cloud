package com.kitex.mom.wms.service;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckAppVO;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;

import java.util.List;

public interface WmsWarehouseMenuService extends IService<WmsWarehouseMenuEntity> {

	/**
	 * 返回树形菜单集合
	 *
	 * @param parentId 父节点ID
	 * @param menuName 菜单名称
	 * @param type     菜单类型 1 按钮、0 菜单
	 * @return 树形菜单
	 */
	List<Tree<Long>> treeMenu(Long parentId, String menuName, String type);

	/**
	 * 查找仓库业务选择集合
	 *
	 * @param warehouseCode 仓库编码
	 * @return 全部集合
	 */
	List<WmsWarehouseBizCheckVO> listCheckBiz(String warehouseCode);

	/**
	 * 查找一个仓库的业务选择集合
	 */
	WmsWarehouseBizCheckAppVO checkBizByWarehouse(String warehouseCode);

	/**
	 * 提交选择的功能
	 */
	Boolean commitCheckBiz(List<WmsWarehouseBizCheckVO> list);

	/**
	 * 同步系统菜单
	 */
	Boolean syncSysMenu();

	/**
	 * 发布页面已展示的预览，ID集合变化时拒绝覆盖。
	 */
	Boolean syncSysMenu(List<Long> expectedIds);
}
