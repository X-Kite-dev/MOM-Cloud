package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WmsOtherBusinessQueryScopeTest {
	@BeforeAll
	static void initializeTableMetadata() {
		for (var type : new Class<?>[] { WmsInBillEntity.class, WmsInNoticeEntity.class,
				WmsOutBillEntity.class, WmsOutNoticeEntity.class }) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "other-query"), type);
		}
	}

	@Test
	void genericOtherInboundAppearsInNoticeAndBillLists() {
		var notice = new WmsOtherInNoticeController();
		var bill = new WmsOtherInBillController();

		assertConstrained(notice.getMainQueryWrapper(new WmsInNoticeQueryDTO()), "other_in");
		assertConstrained(notice.getCountQueryWrapper(new WmsInNoticeQueryDTO()), "other_in");
		assertConstrained(bill.getMainQueryWrapper(new WmsInBillDetailQueryDTO()), "other_in");
		assertConstrained(bill.getCountQueryWrapper(new WmsInBillDetailQueryDTO()), "other_in");
	}

	@Test
	void genericOtherOutboundAppearsInNoticeAndBillLists() {
		var notice = new WmsOtherOutNoticeController();
		var bill = new WmsOtherOutBillController();

		assertConstrained(notice.getMainQueryWrapper(new WmsInNoticeQueryDTO()), "other_out");
		assertConstrained(notice.getCountQueryWrapper(new WmsInNoticeQueryDTO()), "other_out");
		assertConstrained(bill.getMainQueryWrapper(new WmsInBillDetailQueryDTO()), "other_out");
		assertConstrained(bill.getCountQueryWrapper(new WmsInBillDetailQueryDTO()), "other_out");
	}

	@Test
	void explicitInboundTypeCannotEscapeOtherBillList() {
		var query = new WmsInBillDetailQueryDTO();
		query.setBillType("purchase");
		var bill = new WmsOtherInBillController();

		assertConstrained(bill.getMainQueryWrapper(query), "other_in");
		assertConstrained(bill.getCountQueryWrapper(query), "other_in");
	}

	@Test
	void explicitOutboundTypeCannotEscapeOtherBillList() {
		var query = new WmsInBillDetailQueryDTO();
		query.setBillType("sales");
		var bill = new WmsOtherOutBillController();

		assertConstrained(bill.getMainQueryWrapper(query), "other_out");
		assertConstrained(bill.getCountQueryWrapper(query), "other_out");
	}

	private void assertConstrained(QueryWrapper<?> wrapper, String genericType) {
		assertTrue(wrapper.getSqlSegment().contains(" IN "));
		assertTrue(wrapper.getParamNameValuePairs().containsValue(genericType));
	}
}
