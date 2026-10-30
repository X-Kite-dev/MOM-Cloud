package com.kitex.mom.wms.service;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.admin.api.feign.RemoteMenuService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import org.springframework.test.util.ReflectionTestUtils;
import com.kitex.mom.wms.enums.menu.*;
import com.kitex.mom.wms.service.impl.WmsWarehouseMenuServiceImpl;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 服务边界回归：调用真实配置校验、APP菜单过滤和同步回执逻辑。 */
class WmsWarehouseMenuIntegrityTest {
	private WmsWarehouseBizService biz;
	private RemoteWarehouseService warehouses;
	private RemoteMenuService menus;
	private WmsWarehouseMenuServiceImpl service;

	@BeforeEach
	void setup() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "menu"),
				WmsWarehouseBizEntity.class);
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		biz = mock(WmsWarehouseBizService.class);
		warehouses = mock(RemoteWarehouseService.class);
		menus = mock(RemoteMenuService.class);
		service = new WmsWarehouseMenuServiceImpl(biz, warehouses, menus, mock(RemoteUserService.class)) {
			@Override
			public List<Tree<Long>> treeMenu(Long parent, String name, String type) {
				return List.of();
			}

			@Override
			public Boolean updateMenu(List<WmsWarehouseBizCheckVO> commands, List<MdmWarehouseEntity> masters) {
				return true;
			}
		};
		ReflectionTestUtils.setField(service, "baseMapper", mock(WmsWarehouseMenuMapper.class));
		var warehouse = new MdmWarehouseEntity();
		warehouse.setWarehouseCode("W1");
		warehouse.setStatus(true);
		warehouse.setFactoryCode("F1");
		warehouse.setTenantId(1L);
		when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(warehouse)));
		when(biz.updateAll(anyList())).thenReturn(true);
		when(menus.syncWarehouseMenu(anyList())).thenReturn(R.ok(true));
		when(biz.list(any(Wrapper.class))).thenReturn(List.of());
	}

	@AfterEach
	void clear() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	private WmsWarehouseBizCheckVO command(String warehouse, List<String> codes) {
		return new WmsWarehouseBizCheckVO().setWarehouseCode(warehouse).setBizs(codes);
	}

	@Test
	void unknownWarehouseRejectsBeforeReplacingConfiguration() {
		assertThrows(CheckedException.class, () -> service.commitCheckBiz(List.of(command("OTHER", List.of()))));

		verify(biz, never()).updateAll(any());
	}

	@Test
	void missingWarehouseInSnapshotRejectsBeforeReplacingConfiguration() {
		assertThrows(CheckedException.class, () -> service.commitCheckBiz(List.of()));

		verify(biz, never()).updateAll(any());
	}

	@Test
	void duplicateWarehouseRejectsBeforeReplacingConfiguration() {
		assertThrows(CheckedException.class,
				() -> service.commitCheckBiz(List.of(command("W1", List.of()), command("W1", List.of()))));

		verify(biz, never()).updateAll(any());
	}

	@Test
	void unknownBusinessRejectsBeforeReplacingConfiguration() {
		assertThrows(CheckedException.class, () -> service.commitCheckBiz(List.of(command("W1", List.of("invented")))));

		verify(biz, never()).updateAll(any());
	}

	@Test
	void scopedOtherOutboundTypesAreAcceptedOnlyWithTheirParentAndMarker() {
		String parent = MenuOutBizTypeEnum.OTHER_OUT.getValue();
		String marker = "wms:other_out:types";
		String retail = "wms:other_out:other_retail";

		assertThrows(CheckedException.class,
				() -> service.commitCheckBiz(List.of(command("W1", List.of(marker, retail)))));
		assertThrows(CheckedException.class,
				() -> service.commitCheckBiz(List.of(command("W1", List.of(parent, retail)))));
		assertTrue(service.commitCheckBiz(List.of(command("W1", List.of(parent, marker, retail)))));

		verify(biz).updateAll(argThat(rows -> rows.get(0).getBizs().contains(retail)));
	}

	@Test
	void scopedOtherOutboundValuesDoNotGenerateExtraMenus() {
		WmsWarehouseMenuMapper mapper = mock(WmsWarehouseMenuMapper.class);
		when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
		when(mapper.insert(any(WmsWarehouseMenuEntity.class))).thenReturn(1);
		WmsWarehouseMenuServiceImpl generator = new WmsWarehouseMenuServiceImpl(
				biz, warehouses, menus, mock(RemoteUserService.class));
		ReflectionTestUtils.setField(generator, "baseMapper", mapper);
		MdmWarehouseEntity warehouse = warehouses.getMenuConfigurationWarehouses().getData().get(0);

		assertTrue(generator.updateMenu(List.of(command("W1", List.of(
				MenuOutBizTypeEnum.OTHER_OUT.getValue(), "wms:other_out:types",
				"wms:other_out:other_retail"))), List.of(warehouse)));

		var inserted = org.mockito.ArgumentCaptor.forClass(WmsWarehouseMenuEntity.class);
		verify(mapper, atLeastOnce()).insert(inserted.capture());
		assertTrue(inserted.getAllValues().stream().anyMatch(row ->
				row.getPath() != null && row.getPath().startsWith(MenuOutBizTypeEnum.OTHER_OUT.getValue() + "/")));
		assertTrue(inserted.getAllValues().stream().noneMatch(row ->
				row.getPath() != null && row.getPath().startsWith("wms:other_out:")));
	}

	@Test
	void nullRemoteWarehouseResponseFailsClosed() {
		when(warehouses.getMenuConfigurationWarehouses()).thenReturn(null);

		assertThrows(CheckedException.class, () -> service.listCheckBiz(null));
	}

	@Test
	void failedConfigurationWriteCannotProducePreviewSuccess() {
		when(biz.updateAll(anyList())).thenReturn(false);

		assertThrows(CheckedException.class, () -> service.commitCheckBiz(List.of(command("W1", List.of()))));
	}

	@Test
	void emptySelectionIsAnExplicitConfiguration() {
		assertTrue(service.commitCheckBiz(List.of(command("W1", List.of()))));

		verify(biz).updateAll(argThat(rows -> rows.size() == 1 && rows.get(0).getBizs().isEmpty()));
	}

	@Test
	void falseSyncReceiptCannotReportSuccess() {
		when(menus.syncWarehouseMenu(anyList())).thenReturn(R.ok(false));

		assertThrows(CheckedException.class, () -> service.syncSysMenu());
	}

	@Test
	void nullSyncReceiptCannotReportSuccess() {
		when(menus.syncWarehouseMenu(anyList())).thenReturn(null);

		assertThrows(CheckedException.class, () -> service.syncSysMenu());
	}

	@Test
	void successfulSyncUsesAuthoritativeContextEndpoint() {
		assertTrue(service.syncSysMenu());

		verify(menus).syncWarehouseMenu(anyList());
		verify(menus, never()).syncWarehouseMenu(anyList(), anyLong());
	}

	@Test
	void blankWarehouseCannotReadOtherWarehouseConfiguration() {
		assertThrows(CheckedException.class, () -> service.checkBizByWarehouse(" "));

		verify(biz, never()).list(any(Wrapper.class));
	}

	@Test
	void wrapperMenuUsesFactoryPathAndFiltersUnauthorizedInOutFunctions() {
		var common = MenuCommonBizTypeEnum.INVENTORY;
		var inbound = MenuInBizTypeEnum.values()[0];
		var outbound = MenuOutBizTypeEnum.values()[0];
		when(biz.list(any(Wrapper.class)))
				.thenReturn(List.of(new WmsWarehouseBizEntity().setWarehouseCode("W1").setBizType(common.getValue()),
						new WmsWarehouseBizEntity().setWarehouseCode("W1").setBizType(inbound.getValue()),
						new WmsWarehouseBizEntity().setWarehouseCode("W1").setBizType(outbound.getValue())));
		var root = new Tree<Long>().setId(1L).setParentId(-1L);
		root.put("path", "/wms/warehouseManege/F1/W1");
		var child = new Tree<Long>().setId(2L).setParentId(1L);
		child.put("path", common.getValue() + "/W1");
		root.setChildren(List.of(child));
		var app = new Tree<Long>().setId(9L);
		app.put("path", "/wms");
		app.setChildren(List.of(root));
		when(menus.getMenuTree("wms")).thenReturn(R.ok(List.of(app)));

		var result = service.checkBizByWarehouse("W1");

		assertEquals(1, result.getCommon().size());
		assertTrue(result.getInout().isEmpty());
		assertTrue(result.getOther().isEmpty());
	}

	@Test
	void missingFactoryRejectsEvenWithValidSnapshot() {
		FactoryContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.commitCheckBiz(List.of(command("W1", List.of()))));

		verify(biz, never()).updateAll(any());
	}

	@Test
	void staleDisplayedPreviewCannotPublishUnseenMenus() {
		assertThrows(CheckedException.class, () -> service.syncSysMenu(List.of(999L)));

		verify(menus, never()).syncWarehouseMenu(anyList());
	}

	@Test
	void explicitEmptyDisplayedPreviewCanClearUnassignedMenus() {
		assertTrue(service.syncSysMenu(List.of()));

		verify(menus).syncWarehouseMenu(List.of());
	}

	@Test
	void nullDisplayedPreviewCannotSkipVersionCheck() {
		assertThrows(CheckedException.class, () -> service.syncSysMenu(null));

		verify(menus, never()).syncWarehouseMenu(anyList());
	}

	@Test
	void duplicateDisplayedIdsAreRejected() {
		assertThrows(CheckedException.class, () -> service.syncSysMenu(List.of(1L, 1L)));

		verify(menus, never()).syncWarehouseMenu(anyList());
	}

	@Test
	void realEmptyPreviewIsReturnedAsAnEmptyArray() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "preview"),
				WmsWarehouseMenuEntity.class);
		var real = new WmsWarehouseMenuServiceImpl(biz, warehouses, menus, mock(RemoteUserService.class));
		var mapper = mock(WmsWarehouseMenuMapper.class);
		when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
		ReflectionTestUtils.setField(real, "baseMapper", mapper);

		assertEquals(List.of(), real.treeMenu(null, null, null));
		assertTrue(real.syncSysMenu(List.of()));
	}

}
