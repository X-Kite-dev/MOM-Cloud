package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.service.inBill.DefaultWmsInBillService;
import com.kitex.mom.wms.service.inNotice.DefaultWmsInNoticeService;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsDirectStateMutationTest {

	@Test
	void noticeStatesRequireBusinessTransitions() {
		assertThrows(CheckedException.class, () -> new DefaultWmsInNoticeService().updateState(1L, "3"));
		assertThrows(CheckedException.class, () -> new DefaultWmsOutNoticeService().updateState(1L, "3"));
	}

	@Test
	void billStatesRequireBusinessTransitions() {
		assertThrows(CheckedException.class, () -> new DefaultWmsInBillService().updateState(1L, "2"));
		assertThrows(CheckedException.class, () -> new DefaultWmsOutBillService().updateState(1L, "2"));
	}
}
