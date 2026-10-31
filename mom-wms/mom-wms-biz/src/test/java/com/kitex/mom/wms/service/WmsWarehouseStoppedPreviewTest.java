package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.admin.api.feign.RemoteMenuService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsWarehouseMenuServiceImpl;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/**
 * 实际MyBatis预览增删与Spring事务；仅替代PG事务锁及外部主数据服务。
 */
class WmsWarehouseStoppedPreviewTest {
	private JdbcTemplate db;
	private WmsWarehouseMenuServiceImpl service;
	private RemoteWarehouseService warehouses;
	private MdmWarehouseEntity active;
	private MdmWarehouseEntity stopped;

	@BeforeEach
	void setup() throws Exception {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		var source = new DriverManagerDataSource("jdbc:h2:mem:stopped-preview-" + UUID.randomUUID()
				+ ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		var config = new MybatisConfiguration();
		config.addMapper(WmsWarehouseMenuMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(config);
		var sessions = new SqlSessionTemplate(factory.getObject());
		var table = TableInfoHelper.getTableInfo(WmsWarehouseMenuEntity.class);
		var ddl = new StringBuilder("create table wms_warehouse_menu(id bigint primary key");
		for (var field : table.getFieldList()) {
			String type = field.getPropertyType() == Long.class ? "bigint" : "varchar";
			ddl.append(',').append(field.getColumn()).append(' ').append(type);
		}
		db.execute(ddl.append(')').toString());
		var mapper = mock(WmsWarehouseMenuMapper.class,
				delegatesTo(sessions.getMapper(WmsWarehouseMenuMapper.class)));
		doReturn(1).when(mapper).lockConfiguration(anyString());
		var biz = mock(WmsWarehouseBizService.class);
		when(biz.updateAll(anyList())).thenReturn(true);
		warehouses = mock(RemoteWarehouseService.class);
		active = master("W1", true);
		stopped = master("STOPPED", false);
		when(warehouses.getList(any())).thenReturn(R.ok(List.of(active)));
		when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(active, stopped)));
		var target = new WmsWarehouseMenuServiceImpl(biz, warehouses, mock(RemoteMenuService.class),
				mock(RemoteUserService.class), mock(WmsWarehouseBusinessGuard.class));
		ReflectionTestUtils.setField(target, "baseMapper", mapper);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (WmsWarehouseMenuServiceImpl) proxy.getProxy();
		row(1, -1, "/wms/warehouseManege/F1/W1", 1, "F1");
		row(2, 1, "/business/W1", 1, "F1");
		row(3, -1, "/wms/warehouseManege/F1/STOPPED", 1, "F1");
		row(4, 3, "/business/STOPPED", 1, "F1");
		row(5, 4, null, 1, "F1");
		row(6, -1, "/wms/warehouseManege/F1/W1", 2, "F1");
		row(7, -1, "/wms/warehouseManege/F2/W1", 1, "F2");
	}

	@AfterEach
	void clear() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	private MdmWarehouseEntity master(String code, boolean enabled) {
		var result = new MdmWarehouseEntity().setWarehouseCode(code).setStatus(enabled).setFactoryCode("F1");
		result.setTenantId(1L);
		return result;
	}

	private void row(long id, long parent, String path, long tenant, String factory) {
		db.update("insert into wms_warehouse_menu(id,parent_id,path,tenant_id,factory_code,del_flag) "
				+ "values(?,?,?,?,?,'0')", id, parent, path, tenant, factory);
	}

	@Test
	void activeSavePreservesStoppedRootChildButtonAndOtherScopes() {
		assertTrue(service.commitCheckBiz(List.of(new WmsWarehouseBizCheckVO()
				.setWarehouseCode("W1").setBizs(List.of()))));

		assertEquals(List.of(3L, 4L, 5L, 6L, 7L), db.queryForList(
				"select id from wms_warehouse_menu where del_flag='0' order by id", Long.class));
	}

	@Test
	void noActiveMasterRetainsAllStoppedPreviewIds() {
		active.setStatus(false);
		when(warehouses.getList(any())).thenReturn(R.ok(List.of()));

		assertTrue(service.commitCheckBiz(List.of()));

		assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), db.queryForList(
				"select id from wms_warehouse_menu where del_flag='0' order by id", Long.class));
	}

	@Test
	void staleActiveSnapshotCannotAlterStoppedWarehouse() {
		assertThrows(CheckedException.class, () -> service.commitCheckBiz(List.of(
				new WmsWarehouseBizCheckVO().setWarehouseCode("W1").setBizs(List.of()),
				new WmsWarehouseBizCheckVO().setWarehouseCode("STOPPED").setBizs(List.of()))));

		assertEquals(7, db.queryForObject("select count(*) from wms_warehouse_menu where del_flag='0'", Integer.class));
	}

	@Test
	void reenabledWarehouseConfigurationIsAvailableAgainWithOriginalPreviewIds() {
		service.commitCheckBiz(List.of(new WmsWarehouseBizCheckVO().setWarehouseCode("W1").setBizs(List.of())));

		stopped.setStatus(true);
		when(warehouses.getList(any())).thenReturn(R.ok(List.of(active, stopped)));

		assertEquals(List.of(3L), service.treeMenu(null, null, null).stream().map(tree -> tree.getId()).toList());
		assertEquals(List.of(4L), service.treeMenu(3L, null, null).stream().map(tree -> tree.getId()).toList());
	}
}
