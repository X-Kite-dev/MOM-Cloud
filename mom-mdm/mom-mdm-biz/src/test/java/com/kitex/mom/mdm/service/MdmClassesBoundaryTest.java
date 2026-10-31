package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.mapper.MdmClassesMapper;
import com.kitex.mom.mdm.service.impl.MdmClassesServiceImpl;
import com.kitex.mom.mdm.utils.ShiftCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MdmClassesBoundaryTest {

	private List<MdmClassesEntity> shifts() {
		return List.of(shift("D", "08:00", "16:00"), shift("L", "16:00", "22:00"), shift("N", "22:00", "08:00"));
	}

	private MdmClassesEntity shift(String code, String start, String end) {
		var shift = new MdmClassesEntity();
		shift.setShiftCode(code);
		shift.setShiftName(code);
		shift.setStartTime(LocalTime.parse(start));
		shift.setEndTime(LocalTime.parse(end));
		shift.setStatus(true);
		return shift;
	}

	@ParameterizedTest
	@CsvSource({"08:00,261102D", "16:00,261102L", "22:00,261102N", "00:00,261101N",
			"07:59,261101N", "15:59,261102D", "21:59,261102L"})
	void calculatorUsesInclusiveStartAndExclusiveEnd(String time, String expected) {
		assertEquals(expected, ShiftCalculator.getShift(shifts(), LocalDateTime.parse("2026-11-02T" + time)));
	}

	@Test
	void disabledRuleDoesNotTakeTheActiveRulesIdentity() {
		var disabled = shift("DISABLED", "08:00", "16:00");
		disabled.setStatus(false);
		assertEquals("261102D", ShiftCalculator.getShift(List.of(disabled, shifts().get(0)),
				LocalDateTime.parse("2026-11-02T09:00")));
	}

	private MdmClassesMapper mapper() throws Exception {
		return mapper(shifts());
	}

	private MdmClassesMapper mapper(List<MdmClassesEntity> rules) throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:shift-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		var configuration = new MybatisConfiguration();
		configuration.setMapUnderscoreToCamelCase(true);
		var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
		GlobalConfigUtils.setGlobalConfig(configuration, global);
		configuration.addMapper(MdmClassesMapper.class);
		var db = new JdbcTemplate(source);
		var ddl = new StringBuilder("CREATE TABLE mdm_classes(id bigint primary key");
		for (var field : TableInfoHelper.getTableInfo(MdmClassesEntity.class).getFieldList()) {
			var type = field.getPropertyType();
			ddl.append(',').append(field.getColumn()).append(' ').append(type == LocalTime.class ? "time"
					: type == Boolean.class ? "boolean" : type == Long.class ? "bigint"
					: type == Integer.class ? "integer" : type == LocalDateTime.class ? "timestamp" : "varchar(500)");
		}
		db.execute(ddl.append(')').toString());
		long id = 1;
		for (var shift : rules) {
			db.update("INSERT INTO mdm_classes(id,shift_code,shift_name,start_time,end_time,status,del_flag,tenant_id,factory_code) VALUES(?,?,?,?,?,?,'0',1,'F1')",
					id++, shift.getShiftCode(), shift.getShiftName(), shift.getStartTime(), shift.getEndTime(), shift.getStatus());
		}
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		factory.setGlobalConfig(global);
		factory.setMapperLocations(new ClassPathResource("mapper/MdmClassesMapper.xml"));
		return new SqlSessionTemplate(factory.getObject()).getMapper(MdmClassesMapper.class);
	}

	@ParameterizedTest
	@CsvSource({"08:00,261102D,261102L", "16:00,261102L,261102N", "00:00,261101N,261102D"})
	void dispatchShiftQueryReturnsOneCurrentShiftAtHandover(String time, String expected, String next) throws Exception {
		var service = new MdmClassesServiceImpl();
		ReflectionTestUtils.setField(service, "baseMapper", mapper());
		var result = assertDoesNotThrow(() -> service.getClassesBydate(LocalDateTime.parse("2026-11-02T" + time), 3));
		assertEquals(3, result.size());
		assertEquals(expected, result.get(0).getShiftCode());
		assertEquals(next, result.get(1).getShiftCode());
	}

	@ParameterizedTest
	@CsvSource({"08:00,D", "16:00,L", "22:00,N"})
	void currentShiftMapperUsesTheSamePostgresTimeBoundary(String time, String expected) throws Exception {
		var actualMapper = mapper();
		var result = assertDoesNotThrow(() -> actualMapper.getInfoByNow(LocalTime.parse(time)));
		assertNotNull(result);
		assertEquals(expected, result.getShiftCode());
	}

	@Test
	void nextShiftIdentityDoesNotSkipAShortConfiguredShift() throws Exception {
		var service = new MdmClassesServiceImpl();
		ReflectionTestUtils.setField(service, "baseMapper", mapper(List.of(
				shift("D", "08:00", "16:00"), shift("S", "16:00", "16:01"), shift("L", "16:01", "22:00"))));
		var result = service.getClassesBydate(LocalDateTime.parse("2026-11-02T09:00"), 3);
		assertEquals(List.of("261102D", "261102S", "261102L"),
				result.stream().map(MdmClassesEntity::getShiftCode).toList());
	}
    @Test
    void disabledShiftIsExcludedFromDatabaseCurrentAndDispatchQueries() throws Exception {
        var disabled = shift("DISABLED", "08:00", "16:00");
        disabled.setStatus(false);
        var actualMapper = mapper(List.of(disabled, shifts().get(0), shifts().get(1)));
        assertEquals("D", actualMapper.getInfoByNow(LocalTime.of(8, 0)).getShiftCode());
        var service = new MdmClassesServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", actualMapper);
        assertEquals("261102D", service.getClassesBydate(LocalDateTime.of(2026, 11, 2, 8, 0), 1).get(0).getShiftCode());
    }

}
