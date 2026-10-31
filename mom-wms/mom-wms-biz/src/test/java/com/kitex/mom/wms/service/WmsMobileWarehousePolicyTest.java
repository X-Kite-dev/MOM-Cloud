package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsMobileWarehousePolicyTest {

	private RemoteWarehouseService warehouses;
	private MdmWarehouseEntity warehouse;
	private WmsWarehouseBusinessGuard guard;

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		warehouses = mock(RemoteWarehouseService.class);
		warehouse = new MdmWarehouseEntity().setWarehouseCode("W1").setFactoryCode("F1")
				.setStatus(true).setIsMobile(1);
		warehouse.setTenantId(1L);
		when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse));
		guard = new WmsWarehouseBusinessGuard(warehouses, mock(WmsWarehouseBizService.class),
				mock(WmsWarehousePlacementGuard.class));
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void explicitlyEnabledWarehouseAllowsMobileWork() {
		assertTrue(guard.mobileEnabled("W1"));
		assertDoesNotThrow(() -> guard.checkMobile("W1"));
	}

	@Test
	void offOrMissingSettingDoesNotImplicitlyEnableLegacyWarehouses() {
		for (Integer setting : new Integer[]{0, null}) {
			warehouse.setIsMobile(setting);
			assertFalse(guard.mobileEnabled("W1"));
			assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
		}
	}

	@Test
	void currentSwitchIsReadForEveryNewOperation() {
		guard.checkMobile("W1");
		warehouse.setIsMobile(0);

		assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
		verify(warehouses, times(2)).getByCode("W1");
	}

	@Test
	void foreignOrStoppedWarehouseCannotSupplyAnEnabledSwitch() {
		warehouse.setFactoryCode("F2");
		assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
		warehouse.setFactoryCode("F1");
		warehouse.setTenantId(2L);
		assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
		warehouse.setTenantId(1L);
		warehouse.setStatus(false);
		assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
	}

	@Test
	void invalidSwitchAndUnavailableCatalogFailClosed() {
		warehouse.setIsMobile(2);
		assertThrows(CheckedException.class, () -> guard.mobileEnabled("W1"));
		when(warehouses.getByCode("W1")).thenReturn(R.failed());
		assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
	}

	@Test
	void skippedContextCannotBeUsedAsAnActualWarehouse() {
		FactoryContextHolder.setFactorySkip();

		assertThrows(CheckedException.class, () -> guard.checkMobile("W1"));
		verify(warehouses, never()).getByCode("W1");
	}
}
