package com.kitex.mom.iot.agent.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.agent.dto.*;
import com.kitex.mom.iot.entity.*;
import com.kitex.mom.iot.mapper.*;
import com.kitex.mom.mes.agent.dto.MesProcessTelemetryScopeDTO;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Real domain query and XML aggregation; MES and existing database query ports are adapted
 * explicitly.
 */
class IotParameterComparisonTest {
    IotProcessTelemetryQueryService service;
    RemoteMesTaskService mes;
    IotDevicePropertyMapper properties;
    MesProcessTelemetryScopeDTO.Evidence scope;
    MesProcessTelemetryScopeDTO.FrozenParameterStandard standard;
    final LocalDateTime start = LocalDateTime.of(2026, 10, 31, 8, 0);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setup() {
        var config = new MybatisConfiguration();
        for (var type : List.of(IotDeviceEntity.class, IotProductModelEntity.class))
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(config, "parameter-test"), type);
        var access = mock(IotActualFactoryAccessService.class);
        when(access.require("read")).thenReturn("F1");
        when(access.tenantId()).thenReturn(1L);
        mes = mock(RemoteMesTaskService.class);
        properties = mock(IotDevicePropertyMapper.class);
        var devices = mock(IotDeviceMapper.class);
        var models = mock(IotProductModelMapper.class);
        var device = new IotDeviceEntity();
        device.setId(3L);
        device.setDeviceCode("D1");
        when(devices.selectList(any())).thenReturn(List.of(device));
        standard =
                new MesProcessTelemetryScopeDTO.FrozenParameterStandard()
                        .setSourceItemId(10L)
                        .setProcessCode("WIRE")
                        .setRouteNodeCode("RN-1")
                        .setIdentifier("torque")
                        .setUnit("Nm")
                        .setLowerLimit(new BigDecimal("1"))
                        .setUpperLimit(new BigDecimal("5"))
                        .setRequired(true)
                        .setMinimumSampleCount(1)
                        .setAggregationMode("RANGE");
        scope =
                new MesProcessTelemetryScopeDTO.Evidence()
                        .setFactoryCode("F1")
                        .setTaskCode("MT1")
                        .setSnapshotSchemaVersion("ROUTE_NODE_V2")
                        .setContentSchemaVersion("NODE_V2")
                        .setProcessParameterStandardNo("PS1")
                        .setProcessParameterStandardVersion(1)
                        .setFrozenParameterStandards(List.of(standard))
                        .setWindows(List.of(window(1, 0)))
                        .setTruncated(false);
        when(mes.processTelemetryScope(any())).thenAnswer(call -> R.ok(scope));
        when(properties.aggregateProcessTelemetry(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(row("Nm", 1, 0)));
        service =
                new IotProcessTelemetryQueryService(access, mes, devices, properties, models, json);
    }

    MesProcessTelemetryScopeDTO.ExecutionWindow window(long id, int hour) {
        return new MesProcessTelemetryScopeDTO.ExecutionWindow()
                .setExecutionSegmentId(id)
                .setDeviceCode("D1")
                .setProcessCode("WIRE")
                .setRouteNodeCode("RN-1")
                .setStartTime(start.plusHours(hour))
                .setEndTime(start.plusHours(hour).plusMinutes(30));
    }

    IotTelemetryAggregateRow row(String unit, int variants, long missing) {
        var r = new IotTelemetryAggregateRow();
        r.setIdentifier("torque");
        r.setUnit(unit);
        r.setSampleCount(2L);
        r.setGoodSampleCount(2L);
        r.setNonGoodSampleCount(0L);
        r.setNumericGoodSampleCount(2L);
        r.setMinimumValue(new BigDecimal("2"));
        r.setMaximumValue(new BigDecimal("3"));
        r.setAverageValue(new BigDecimal("2.5"));
        r.setFirstCollectTime(start);
        r.setLastCollectTime(start.plusMinutes(1));
        r.setFirstPropertyId(1L);
        r.setLastPropertyId(2L);
        r.setNumericUnitCount(variants);
        r.setMissingNumericUnitCount(missing);
        return r;
    }

    IotProcessTelemetryDTO.Evidence query() {
        return service.query(
                new IotProcessTelemetryDTO.Query()
                        .setFactoryCode("F1")
                        .setTaskCode("MT1")
                        .setAcceptedSnapshotSchemaVersion("ROUTE_NODE_V2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mixed", "missing", "unknownMetadata", "wrongCase", "missingStandard"})
    void unreliableUnitEvidenceCannotBeConformant(String changed) {
        var row = row("Nm", 1, 0);
        if (changed.equals("mixed")) row = row("Nm", 2, 0);
        if (changed.equals("missing")) row = row("Nm", 1, 1);
        if (changed.equals("unknownMetadata")) {
            row = new IotTelemetryAggregateRow();
            row.setIdentifier("torque");
            row.setUnit("Nm");
            row.setSampleCount(2L);
            row.setGoodSampleCount(2L);
            row.setNonGoodSampleCount(0L);
            row.setNumericGoodSampleCount(2L);
            row.setMinimumValue(BigDecimal.valueOf(2));
            row.setMaximumValue(BigDecimal.valueOf(2));
            row.setAverageValue(BigDecimal.valueOf(2));
        }
        if (changed.equals("wrongCase")) standard.setUnit("nm");
        if (changed.equals("missingStandard")) standard.setUnit(null);
        when(properties.aggregateProcessTelemetry(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(row));
        var result = query();
        assertEquals("PARTIAL", result.getProcessComplianceStatus());
        assertEquals(
                "INSUFFICIENT_EVIDENCE",
                result.getParameters().get(0).getProcessComplianceStatus());
        var evidence = result.getParameters().get(0);
        assertEquals(standard.getUnit(), evidence.getStandardUnit());
        if (List.of("mixed", "missing", "unknownMetadata").contains(changed)) {
            assertNull(evidence.getSampleUnit());
            assertNull(evidence.getMinimumValue());
            assertNull(evidence.getMaximumValue());
            assertNull(evidence.getAverageValue());
        } else {
            assertEquals("KNOWN", evidence.getSampleUnitStatus());
            assertEquals("Nm", evidence.getSampleUnit());
        }
    }

    @Test
    void disjointWindowsWithDifferentUnitsCannotMergeIntoOneGoodQuantity() {
        scope.setWindows(List.of(window(1, 0), window(2, 1)));
        when(properties.aggregateProcessTelemetry(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of(row("Nm", 1, 0)), List.of(row("Ncm", 1, 0)));
        var result = query();
        assertEquals("PARTIAL", result.getProcessComplianceStatus());
        assertEquals("MIXED", result.getParameters().get(0).getSampleUnitStatus());
        assertNull(result.getParameters().get(0).getAverageValue());
    }

    @Test
    void truncatedObservationCannotBePresentedAsFullConformance() {
        scope.setTruncated(true);
        assertEquals("PARTIAL", query().getProcessComplianceStatus());
    }

    @Test
    void knownUnitsUseTheActualFrozenRange() {
        assertEquals("CONFORMANT", query().getProcessComplianceStatus());
        standard.setUpperLimit(new BigDecimal("2.8"));
        assertEquals("NON_CONFORMANT", query().getProcessComplianceStatus());
    }

    @Test
    void noSamplesAndNoStandardRemainSeparate() {
        when(properties.aggregateProcessTelemetry(anyLong(), anyString(), anyLong(), any(), any()))
                .thenReturn(List.of());
        assertEquals("PARTIAL", query().getProcessComplianceStatus());
        scope.setFrozenParameterStandards(List.of());
        assertEquals("NO_STANDARD", query().getProcessComplianceStatus());
    }

    @Test
    void wrongFactoryOrFailedMesReplyNeverBecomesEvidence() {
        assertThrows(
                RuntimeException.class,
                () ->
                        service.query(
                                new IotProcessTelemetryDTO.Query()
                                        .setFactoryCode("F2")
                                        .setTaskCode("MT1")));
        when(mes.processTelemetryScope(any())).thenReturn(R.ok(scope).setCode(1));
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void actualXmlCountsOnlyScopedGoodNumericUnitsAndExclusiveWindow() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:parameter_units_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var db = new JdbcTemplate(ds);
        db.execute(
                "CREATE TABLE iot_device_property(id BIGINT,tenant_id BIGINT,factory_code"
                        + " VARCHAR,device_id BIGINT,identifier VARCHAR,name VARCHAR,data_type"
                        + " VARCHAR,unit VARCHAR,value VARCHAR,quality_code VARCHAR,collect_time"
                        + " TIMESTAMP,del_flag VARCHAR,scope_verified BOOLEAN)");
        for (int i = 0; i < 7; i++)
            db.update(
                    "INSERT INTO iot_device_property"
                            + " VALUES(?,1,'F1',3,'torque','扭矩','DECIMAL',?,?,'GOOD',?,'0',TRUE)",
                    i + 1,
                    i == 0 ? "Nm" : i == 1 ? "Ncm" : null,
                    i == 3 ? "bad" : "2",
                    i == 4 ? start.plusMinutes(30) : start.plusMinutes(1));
        db.update("UPDATE iot_device_property SET quality_code='BAD' WHERE id=3");
        db.update("UPDATE iot_device_property SET factory_code='F2' WHERE id=6");
        db.update("UPDATE iot_device_property SET tenant_id=2 WHERE id=7");
        var config = new MybatisConfiguration();
        config.addMapper(IotDevicePropertyMapper.class);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(config);
        bean.setMapperLocations(
                new FileSystemResource("src/main/resources/mapper/IotDevicePropertyMapper.xml"));
        var mapper =
                new SqlSessionTemplate(bean.getObject()).getMapper(IotDevicePropertyMapper.class);
        var rows = mapper.aggregateProcessTelemetry(1L, "F1", 3L, start, start.plusMinutes(30));
        assertEquals(1, rows.size());
        var r = rows.get(0);
        assertEquals(4L, r.getSampleCount());
        assertEquals(2L, r.getNumericGoodSampleCount());
        assertEquals(2, r.getNumericUnitCount());
        assertEquals(0L, r.getMissingNumericUnitCount());
        db.update(
                "INSERT INTO iot_device_property VALUES(8,1,'F1',3,'torque','扭矩','DECIMAL',' "
                        + " ','2','GOOD',?,'0',TRUE)",
                start.plusMinutes(2));
        var withMissing =
                mapper.aggregateProcessTelemetry(1L, "F1", 3L, start, start.plusMinutes(30)).get(0);
        assertEquals(3L, withMissing.getNumericGoodSampleCount());
        assertEquals(2, withMissing.getNumericUnitCount());
        assertEquals(1L, withMissing.getMissingNumericUnitCount());
    }
}
