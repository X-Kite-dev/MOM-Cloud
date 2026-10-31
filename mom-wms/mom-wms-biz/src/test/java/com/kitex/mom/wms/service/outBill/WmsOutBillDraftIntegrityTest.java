package com.kitex.mom.wms.service.outBill;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsOutBillDraftIntegrityTest {

	private final DefaultWmsOutBillService service = new DefaultWmsOutBillService();

	@org.junit.jupiter.api.BeforeEach
	void preparePlacementDependency() {
		org.springframework.test.util.ReflectionTestUtils.setField(service, "warehouseBusinessGuard",
				org.mockito.Mockito.mock(com.kitex.mom.wms.service.WmsWarehouseBusinessGuard.class));
	}

	@Test
	void rejectsBlankMaterialAndUnitBeforeWritingDraft() {
		WmsOutBillEntity blankMaterial = bill();
		blankMaterial.getDetails().get(0).setMatCode(" ");
		assertThrows(CheckedException.class, () -> service.beforeSaveDeep(blankMaterial));

		WmsOutBillEntity blankUnit = bill();
		blankUnit.getDetails().get(0).setMatUnit(" ");
		assertThrows(CheckedException.class, () -> service.beforeSaveDeep(blankUnit));
	}

	@Test
	void rejectsNonPositiveAndNonFiniteQuantityBeforeWritingDraft() {
		for (double quantity : List.of(0D, -1D, Double.NaN, Double.POSITIVE_INFINITY)) {
			WmsOutBillEntity invalid = bill();
			invalid.getDetails().get(0).setMatNum(quantity);
			assertThrows(CheckedException.class, () -> service.beforeSaveDeep(invalid));
		}
		WmsOutBillEntity missing = bill();
		missing.getDetails().get(0).setMatNum(null);
		assertThrows(CheckedException.class, () -> service.beforeSaveDeep(missing));
	}

	@Test
	void acceptsCompleteOwnedLine() {
		assertDoesNotThrow(() -> service.checkData(bill()));
	}

	private static WmsOutBillEntity bill() {
		WmsOutBillDetailEntity line = new WmsOutBillDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setMatNum(1D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setBillType("sales");
		bill.setWarehouseCode("W1");
		bill.setDetails(List.of(line));
		return bill;
	}
}
