package com.kitex.mom.wms.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 明确容量维度和库存单位换算；全部十进制在API中保持文本。 */
public final class WmsWarehouseCapacityDTO {
	private WmsWarehouseCapacityDTO() {
	}

	public record Limit(String locationCode, String capacity) {
	}

	public record Factor(String matCode, String matUnit, String amount) {
	}

	public record Model(int schemaVersion, String unit, List<Limit> limits, List<Factor> factors) {
		public Model(String unit, List<Limit> limits, List<Factor> factors) {
			this(1, unit, limits, factors);
		}
	}

	public record Location(String locationCode, String locationName, boolean enabled) {
	}

	public record StockGroup(String siteCode, String matCode, String matName, String matUnit,
			String quantity, long stockRows, long invalidRows) {
	}

	public record Contribution(String matCode, String matName, String matUnit, String quantity,
			String factor, String occupied, long stockRows, long invalidRows, String status) {
	}

	public record Position(String locationCode, String locationName, boolean enabled, String capacity,
			String knownOccupied, String occupied, String utilization, String remaining, String excess,
			long stockRows, long unresolvedRows, String status) {
	}

	public record Summary(int activeLocations, int configuredLocations, int completeLocations,
			int overloadedLocations, long unresolvedRows, String declaredCapacity, String occupied,
			String utilization) {
	}

	public record Result(Summary summary, List<Position> locations) {
	}

	public record Policy(Model model, int version, boolean configured) {
	}

	public record View(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
			Policy policy, List<Location> master, Result metrics, LocalDateTime observedAt) {
	}

	public record SaveCommand(String warehouseCode, int expectedVersion, Model model) {
	}

	public record Saved(int contractVersion, String tenantId, String factoryCode, String warehouseCode, int version) {
	}

	public record Detail(int contractVersion, String tenantId, String factoryCode, String warehouseCode,
			String locationCode, int version, String unit, int page, int size, int total,
			List<Contribution> items, LocalDateTime observedAt) {
	}
}
