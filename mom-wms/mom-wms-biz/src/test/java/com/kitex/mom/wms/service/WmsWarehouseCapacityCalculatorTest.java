package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WmsWarehouseCapacityCalculatorTest {
	private final WmsWarehouseCapacityCalculator calculator = new WmsWarehouseCapacityCalculator();
	private final List<Location> master = List.of(new Location("A", "A区", true), new Location("B", "B区", true));

	@Test
	void materialSpecificConversionProducesExactOccupancyAndDoesNotAssumeEqualPieces() {
		var model = new Model("M3", List.of(new Limit("A", "1.0"), new Limit("B", "1")),
				List.of(new Factor("MOTOR", "PCS", "0.2"), new Factor("SWITCH", "PCS", "0.01")));
		var result = calculator.calculate(model, master, List.of(
				group("A", "MOTOR", "PCS", "2"), group("A", "SWITCH", "PCS", "10")));

		assertEquals("0.5", result.locations().get(0).occupied());
		assertEquals("50", result.locations().get(0).utilization());
		assertEquals("0.5", result.summary().occupied());
		assertEquals("25", result.summary().utilization());
	}

	@Test
	void missingMaterialUnitMappingIsUnknownInsteadOfZeroOrDefaultConversion() {
		var result = calculator.calculate(model(), master, List.of(group("A", "M1", "BOX", "2")));

		var position = result.locations().get(0);
		assertEquals("CONVERSION_MISSING", position.status());
		assertNull(position.occupied());
		assertNull(position.utilization());
		assertNull(position.remaining());
		assertNull(result.summary().utilization());
		assertEquals(1, position.unresolvedRows());
	}

	@Test
	void unconfiguredEmptyPhysicalLocationPreventsAFalseWholeWarehouseRate() {
		var result = calculator.calculate(new Model("M3", List.of(new Limit("A", "1")), model().factors()), master,
				List.of(group("A", "M1", "PCS", "1")));

		assertEquals("20", result.locations().get(0).utilization());
		assertEquals("CAPACITY_MISSING", result.locations().get(1).status());
		assertNull(result.summary().utilization());
		assertEquals(1, result.summary().configuredLocations());
		assertEquals(2, result.summary().activeLocations());
	}

	@Test
	void negativeNullAndNonFiniteQuantitiesCannotReduceKnownPhysicalOccupancy() {
		for (String invalid : List.of("-5", "NaN", "Infinity")) {
			var result = calculator.calculate(model(), master, List.of(group("A", "M1", "PCS", "2"),
					new StockGroup("A", "M1", "物料", "PCS", invalid, 1, 0)));
			assertEquals("0.4", result.locations().get(0).knownOccupied());
			assertEquals("QUANTITY_INVALID", result.locations().get(0).status());
			assertNull(result.locations().get(0).utilization());
		}
		var result = calculator.calculate(model(), master, List.of(new StockGroup("A", "M1", "物料", "PCS", "0", 0, 2)));
		assertNull(result.summary().utilization());
		assertEquals(2, result.summary().unresolvedRows());
	}

	@Test
	void disabledAndUnknownStockLocationsCannotDisappearFromCompleteness() {
		var result = calculator.calculate(model(), List.of(new Location("A", "A区", true), new Location("B", "B区", false)),
				List.of(group("B", "M1", "PCS", "2"), group("UNKNOWN", "M1", "PCS", "1")));
		assertNull(result.summary().utilization());
		assertEquals(2, result.locations().stream().filter(row -> "LOCATION_UNAVAILABLE".equals(row.status())).count());
	}

	@Test
	void overCapacityKeepsTheActualPercentageAndShowsTheExcess() {
		var result = calculator.calculate(model(), master, List.of(group("A", "M1", "PCS", "7.5")));
		var row = result.locations().get(0);
		assertEquals("OVER_CAPACITY", row.status());
		assertEquals("150", row.utilization());
		assertEquals("0", row.remaining());
		assertEquals("0.5", row.excess());
		assertEquals(1, result.summary().overloadedLocations());
	}

	@Test
	void decimalContributionDoesNotPassThroughFloatingPoint() {
		var model = new Model("SLOT", List.of(new Limit("A", "1")), List.of(new Factor("M1", "PCS", "0.00000001")));
		var result = calculator.calculate(model, List.of(master.get(0)), List.of(group("A", "M1", "PCS", "99999999")));
		assertEquals("0.99999999", result.locations().get(0).occupied());
		assertEquals("0.00000001", result.locations().get(0).remaining());
	}

	@Test
	void duplicateNonpositiveOrAmbiguousConfigurationIsRejected() {
		assertThrows(CheckedException.class, () -> calculator.normalize(new Model("PCS", List.of(), List.of())));
		assertThrows(CheckedException.class, () -> calculator.normalize(new Model("M3", List.of(new Limit("A", "0")), List.of())));
		assertThrows(CheckedException.class, () -> calculator.normalize(new Model("M3", List.of(), List.of(new Factor("M1", "PCS", "1"), new Factor("M1", "PCS", "2")))));
		assertThrows(CheckedException.class, () -> calculator.normalize(new Model("M3", List.of(new Limit("A", "1"), new Limit("A", "2")), List.of())));
	}

	private Model model() {
		return new Model("M3", List.of(new Limit("A", "1"), new Limit("B", "1")), List.of(new Factor("M1", "PCS", "0.2")));
	}

	private StockGroup group(String site, String material, String unit, String quantity) {
		return new StockGroup(site, material, material, unit, quantity, 1, 0);
	}
}
