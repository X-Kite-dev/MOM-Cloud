package com.kitex.mom.wms.vo;

import java.util.List;

/**
 * 完整启用仓库配置及其读取版本。
 */
public record WmsWarehouseConfigurationCommand(String expectedRevision, List<WmsWarehouseBizCheckVO> warehouses) {
}
