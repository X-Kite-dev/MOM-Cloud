package com.kitex.mom.wms.vo;

import cn.hutool.core.lang.tree.Tree;

import java.util.List;

/**
 * 仓库作业配置与预览的同版本快照；修订值由服务端生成。
 *
 * @param contractVersion  合同版本，当前为1；业务目录为兼容性追加字段
 * @param tenantId         实际认证租户身份，文本传输避免Long精度丢失
 * @param factoryCode      当前已授权工厂编码
 * @param revision         当前仓库配置及菜单预览的内容摘要，保存时用于并发核对
 * @param warehouses       当前组织内启用的仓库及通用开关
 * @param selections       已保存的逐仓业务组合；不代表账号已获各业务操作授权
 * @param menuTree         与配置一起读取的菜单预览，节点身份用于同步前核对
 * @param commonBusinesses 当前服务支持的通用业务目录；旧调用未提供时为空
 */
public record WmsWarehouseConfigurationSnapshot(int contractVersion, String tenantId, String factoryCode,
                                                String revision, List<Warehouse> warehouses,
                                                List<WmsWarehouseBizCheckVO> selections,
                                                List<Tree<Long>> menuTree,
                                                List<BusinessOption> commonBusinesses) {

	/**
	 * 保留原Java构造合同；未提供能力目录时客户端沿旧字典显示，不推定新服务能力。
	 */
	public WmsWarehouseConfigurationSnapshot(int contractVersion, String tenantId, String factoryCode,
	                                         String revision, List<Warehouse> warehouses,
	                                         List<WmsWarehouseBizCheckVO> selections,
	                                         List<Tree<Long>> menuTree) {
		this(contractVersion, tenantId, factoryCode, revision, warehouses, selections, menuTree, null);
	}

	/**
	 * 当前仓库通用设置；数值开关沿既有0/1合同，不改变库存或规则。
	 *
	 * @param warehouseCode 当前组织内的仓库编码
	 * @param warehouseName 仓库名称
	 * @param isBatch       是否自动生成批次，1启用、0关闭
	 * @param isContainer   是否强制使用容器，1启用、0关闭
	 * @param isMobile      是否允许手机端新作业，1启用、0关闭
	 * @param sort          仓库菜单排序，允许未设置
	 */
	public record Warehouse(String warehouseCode, String warehouseName, Integer isBatch,
	                        Integer isContainer, Integer isMobile, Integer sort) {
	}

	/**
	 * 与菜单生成和业务准入使用同一枚举的可选项，不含角色授权或启用状态。
	 *
	 * @param value 保存到业务配置的真实路径，不附加仓库编码
	 * @param label 产品标准中文名称
	 * @param sort  产品目录排序
	 */
	public record BusinessOption(String value, String label, Integer sort) {
	}
}
