package com.kitex.mom.mes.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.service.MesWarehousePickingBillService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MesPickingWritePermissionTest {

	@Test
	void mutationsRequireCanonicalPickingPermissionsWhileCallbackStaysInternal() {
		Map<String, String> permissions = Map.of("save", "add", "updateById", "edit", "commitBill", "edit",
				"withdraw", "edit", "removeById", "del", "removeChild", "del", "importExcel", "add");
		for (var method : MesWarehousePickingBillController.class.getDeclaredMethods()) {
			if (permissions.containsKey(method.getName())) {
				var annotation = method.getAnnotation(PreAuthorize.class);
				assertNotNull(annotation, method.getName());
				assertEquals("@pms.hasPermission('mes_mesWarehousePickingBill_" + permissions.get(method.getName())
						+ "')", annotation.value(), method.getName());
			}
			if (method.getName().equals("wmsOutBillBack")) {
				assertNotNull(method.getAnnotation(Inner.class));
				assertNull(method.getAnnotation(PreAuthorize.class));
			}
		}
	}

	@Test
	void spreadsheetHeadersCannotBypassControlledPickingCreation() {
		var service = mock(MesWarehousePickingBillService.class);
		var controller = new MesWarehousePickingBillController(service);

		assertThrows(CheckedException.class,
				() -> controller.importExcel(List.of(new MesWarehousePickingBillEntity()), null));

		verifyZeroInteractions(service);
	}
}
