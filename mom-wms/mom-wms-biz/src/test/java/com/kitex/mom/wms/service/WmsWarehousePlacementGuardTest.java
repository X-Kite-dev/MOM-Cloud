package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsContainerBarcodeEntity;
import com.kitex.mom.wms.mapper.WmsContainerBarcodeMapper;
import com.kitex.mom.wms.service.WmsWarehousePlacementGuard.Placement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsWarehousePlacementGuardTest {

	private RemoteWarehouseService warehouses;
	private RemoteWarehouseLocationService locations;
	private WmsContainerBarcodeMapper containers;
	private WmsWarehousePlacementGuard guard;
	private MdmWarehouseEntity warehouse;
	private MdmWarehouseLocationEntity location;
	private WmsContainerBarcodeEntity container;

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		warehouses = mock(RemoteWarehouseService.class);
		locations = mock(RemoteWarehouseLocationService.class);
		containers = mock(WmsContainerBarcodeMapper.class);
		warehouse = new MdmWarehouseEntity().setWarehouseCode("W1").setFactoryCode("F1").setStatus(true).setIsContainer(1);
		warehouse.setTenantId(1L);
		warehouse.setDelFlag("0");
		location = new MdmWarehouseLocationEntity();
		location.setTenantId(1L);
		location.setFactoryCode("F1");
		location.setWarehouseCode("W1");
		location.setLocationCode("S1");
		location.setStatus(true);
		location.setDelFlag("0");
		container = new WmsContainerBarcodeEntity().setCode("C1").setFactoryCode("F1").setWarehouseCode("W1").setIsUsed(1);
		container.setTenantId(1L);
		container.setDelFlag("0");
		when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse));
		when(locations.getByCode("S1")).thenReturn(R.ok(location));
		when(containers.selectList(any(Wrapper.class))).thenReturn(List.of(container));
		guard = new WmsWarehousePlacementGuard(warehouses, locations, containers);
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void requiredContainerCannotBeOmitted() {
		assertThrows(CheckedException.class, () -> guard.check("W1", List.of(new Placement("S1", null))));
	}

	@Test
	void optionalContainerMayBeAbsentButProvidedIdentityMustStillMatch() {
		warehouse.setIsContainer(0);
		guard.check("W1", List.of(new Placement("S1", null)));
		verify(containers, never()).selectList(any(Wrapper.class));
		container.setWarehouseCode("W2");
		assertThrows(CheckedException.class, () -> guard.check("W1", List.of(new Placement("S1", "C1"))));
	}

	@Test
	void occupiedContainerCanReceiveAdditionalStockAndBatchLookupsAreDeduplicated() {
		guard.check("W1", List.of(new Placement("S1", "C1"), new Placement("S1", "C1")));

		verify(locations, times(1)).getByCode("S1");
		verify(containers, times(1)).selectList(any(Wrapper.class));
	}

	@Test
	void crossTenantFactoryWarehouseOrDisabledLocationCannotBeUsed() {
		location.setTenantId(2L);
		assertThrows(CheckedException.class, this::check);
		location.setTenantId(1L);
		location.setFactoryCode("F2");
		assertThrows(CheckedException.class, this::check);
		location.setFactoryCode("F1");
		location.setWarehouseCode("W2");
		assertThrows(CheckedException.class, this::check);
		location.setWarehouseCode("W1");
		location.setStatus(false);
		assertThrows(CheckedException.class, this::check);
	}

	@Test
	void deletedDuplicateOrForeignContainerCannotBeAccepted() {
		container.setDelFlag("1");
		assertThrows(CheckedException.class, this::check);
		container.setDelFlag("0");
		container.setTenantId(2L);
		assertThrows(CheckedException.class, this::check);
		container.setTenantId(1L);
		container.setFactoryCode("F2");
		assertThrows(CheckedException.class, this::check);
		container.setFactoryCode("F1");
		when(containers.selectList(any(Wrapper.class))).thenReturn(List.of(container, container));
		assertThrows(CheckedException.class, this::check);
	}

	@Test
	void unavailableCatalogAndSkippedContextFailBeforeAnyWrite() {
		when(locations.getByCode("S1")).thenReturn(R.failed("unavailable"));
		assertThrows(CheckedException.class, this::check);
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, this::check);
	}

	@Test
	void nonCanonicalOrMismatchedCodesCannotBeValidatedThenWrittenAsAnotherIdentity() {
		assertThrows(CheckedException.class, () -> guard.check("W1", List.of(new Placement("S1 ", "C1"))));
		assertThrows(CheckedException.class, () -> guard.check("W1", List.of(new Placement("S1", "C1 "))));
		location.setLocationCode("ANOTHER");
		assertThrows(CheckedException.class, this::check);
	}

	@Test
	void currentWarehouseConfigurationIsReadAgainForEveryOperation() {
		warehouse.setIsContainer(0);
		guard.check("W1", List.of(new Placement("S1", null)));
		warehouse.setIsContainer(1);

		assertThrows(CheckedException.class, () -> guard.check("W1", List.of(new Placement("S1", null))));
	}

	private void check() {
		guard.check("W1", List.of(new Placement("S1", "C1")));
	}
}
