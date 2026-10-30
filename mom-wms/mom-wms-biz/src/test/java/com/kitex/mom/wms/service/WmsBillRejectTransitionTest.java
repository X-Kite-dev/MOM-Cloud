package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.service.inBill.DefaultWmsInBillService;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsBillRejectTransitionTest {

	@BeforeAll
	static void registerTables() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "reject-in-bill"),
				WmsInBillEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "reject-out-bill"),
				WmsOutBillEntity.class);
	}

	@BeforeEach
	void setActor() {
		MomUser actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("reviewer");
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
	}

	@AfterEach
	void clearActor() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void inboundRejectNeedsConfirmedLockedTransition() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setId(7L);
		bill.setState("1");
		when(bills.selectById(7L)).thenReturn(bill);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);

		assertThrows(CheckedException.class, () -> service.rejectBill(bill));
		verify(bills).lockBill(7L);
	}

	@Test
	void outboundRejectNeedsConfirmedLockedTransition() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setId(7L);
		bill.setState("1");
		when(bills.selectById(7L)).thenReturn(bill);
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);

		assertThrows(CheckedException.class, () -> service.rejectBill(bill));
		verify(bills).lockBill(7L);
	}
}
