package com.kitex.mom.wms.service;

import cn.hutool.core.lang.tree.Tree;
import cn.hutool.json.JSONUtil;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import com.kitex.mom.wms.vo.WmsWarehouseConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 服务端配置版本；仅序列化显式标量，避免记录类型的JSON反射策略改变版本。
 */
public final class WmsWarehouseConfigurationRevision {
	private WmsWarehouseConfigurationRevision() {
	}

	public static String calculate(String tenant, String factory,
	                               List<WmsWarehouseConfigurationSnapshot.Warehouse> warehouses,
	                               List<WmsWarehouseBizCheckVO> selections, List<Tree<Long>> tree) {
		var masterValues = warehouses.stream().sorted(Comparator.comparing(WmsWarehouseConfigurationSnapshot.Warehouse::warehouseCode))
				.map(row -> Arrays.asList(row.warehouseCode(), row.warehouseName(), row.isBatch(), row.isContainer(), row.isMobile(), row.sort())).toList();
		var selected = selections.stream().sorted(Comparator.comparing(WmsWarehouseBizCheckVO::getWarehouseCode))
				.map(row -> List.of(row.getWarehouseCode(), row.getBizs().stream().sorted().toList())).toList();
		List<List<String>> nodes = new ArrayList<>();
		flatten(tree, nodes);
		nodes.sort(Comparator.comparing(row -> row.get(0)));
		String canonical = JSONUtil.toJsonStr(List.of("WMS_CONFIGURATION_V1", tenant, factory, masterValues, selected, nodes));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256不可用", error);
		}
	}

	private static void flatten(List<Tree<Long>> tree, List<List<String>> rows) {
		for (Tree<Long> node : tree) {
			rows.add(List.of("id", "parentId", "name", "path", "permission", "component", "icon", "sortOrder", "menuType")
					.stream().map(field -> Objects.toString(node.get(field), "")).toList());
			if (node.getChildren() != null) {
				flatten(node.getChildren(), rows);
			}
		}
	}
}
