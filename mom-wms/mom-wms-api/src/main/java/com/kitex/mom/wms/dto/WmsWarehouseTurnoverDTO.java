package com.kitex.mom.wms.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 同物料库存单位的数量周转；数值保留十进制文本。
 */
public final class WmsWarehouseTurnoverDTO {
	private WmsWarehouseTurnoverDTO() {
	}

	public record Balance(String matCode, String matUnit, LocalDate day, String quantity, long invalidRows) {
	}

	public record Issue(String matCode, String matUnit, String quantity, long invalidRows) {
	}

	public record Day(LocalDate date, String closingInventory, long invalidRows) {
	}

	public record Row(String matCode, String matUnit, String openingInventory, String closingInventory,
	                  String sumDailyInventory, String averageInventory, String outboundQuantity, String turnover,
	                  String turnoverDays, String status, List<Day> days) {
	}

	public record Context(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                      LocalDate firstDate, LocalDate lastDate, boolean available, LocalDateTime observedAt) {
	}

	public record View(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
	                   LocalDate fromDate, LocalDate toDate, String basis, String matCode, int dayCount, List<Row> rows,
	                   LocalDateTime observedAt) {
	}
}
