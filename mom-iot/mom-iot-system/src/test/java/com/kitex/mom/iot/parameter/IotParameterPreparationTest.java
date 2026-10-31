package com.kitex.mom.iot.parameter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.service.impl.IotDeviceDataAccess;
import com.kitex.mom.mes.api.feign.RemoteMesParameterContextService;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;

import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

class IotParameterPreparationTest {
    IotDeviceDataAccess access;
    RemoteMesParameterContextService mes;
    IotDeviceMapper devices;
    IotDeviceEntity device;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "options"),
                IotDeviceEntity.class);
        access = mock(IotDeviceDataAccess.class);
        mes = mock(RemoteMesParameterContextService.class);
        devices = mock(IotDeviceMapper.class);
        when(access.factory("read")).thenReturn("F1");
        when(access.tenant()).thenReturn(1L);
        device = new IotDeviceEntity();
        device.setId(3L);
        device.setTenantId(1L);
        device.setFactoryCode("F1");
        device.setDeviceCode("D1");
        device.setDeviceName("测试设备");
        device.setFirmwareVersion("FW1");
        device.setDeviceSecret("MUST_NOT_LEAK");
        when(devices.selectList(any())).thenReturn(List.of(device));
    }

    void source(
            String factory,
            String task,
            String arrange,
            String deviceCode,
            List<String> codes,
            boolean ok)
            throws Exception {
        var type = Class.forName("com.kitex.mom.mes.dto.MesParameterDispatchContextDTO$Options");
        var raw = new HashMap<String, Object>();
        raw.put("schemaVersion", "MES_PARAMETER_PREPARATION@1");
        raw.put("factoryCode", factory);
        raw.put("taskCode", task);
        raw.put("arrangeCode", arrange);
        raw.put("deviceCode", deviceCode);
        raw.put(
                "arrangements",
                List.of(
                        Map.of(
                                "arrangeCode",
                                "A1",
                                "processCode",
                                "WIRE",
                                "stationCode",
                                "S1",
                                "routeNodeCode",
                                "RN-1",
                                "status",
                                "RECEIVE")));
        raw.put("deviceCodes", codes);
        raw.put("truncated", false);
        raw.put("blocker", "");
        var value = json.convertValue(raw, MesParameterDispatchContextDTO.Options.class);
        when(mes.preparationOptions(any())).thenReturn(ok ? R.ok(value) : R.failed(value));
    }

    Object invoke() throws Exception {
        var type = Class.forName("com.kitex.mom.iot.parameter.IotParameterPreparationService");
        var s = type.getConstructors()[0].newInstance(access, mes, devices);
        try {
            return type.getMethod("query", String.class, String.class, String.class)
                    .invoke(s, "MT1", "A1", null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    @Test
    void scopedCandidateIncludesActualFirmwareButNoSecret() throws Exception {
        source("F1", "MT1", "A1", null, List.of("D1"), true);
        String result = json.writeValueAsString(invoke());
        assertTrue(result.contains("FW1"));
        assertTrue(result.contains("\"simulatorEnabled\":false"));
        assertFalse(result.contains("MUST_NOT_LEAK"));
    }

    @Test
    void failedRemoteResponseWithDataIsRejected() throws Exception {
        source("F1", "MT1", "A1", null, List.of("D1"), false);
        assertThrows(RuntimeException.class, this::invoke);
    }

    @Test
    void wrongRemoteFactoryIsRejected() throws Exception {
        source("F2", "MT1", "A1", null, List.of("D1"), true);
        assertThrows(RuntimeException.class, this::invoke);
    }

    @Test
    void wrongRemoteTaskIsRejected() throws Exception {
        source("F1", "MT2", "A1", null, List.of("D1"), true);
        assertThrows(RuntimeException.class, this::invoke);
    }

    @Test
    void wrongSelectedArrangementIsRejected() throws Exception {
        source("F1", "MT1", "A2", null, List.of("D1"), true);
        assertThrows(RuntimeException.class, this::invoke);
    }

    @Test
    void foreignDeviceCannotLeak() throws Exception {
        source("F1", "MT1", "A1", null, List.of("D1"), true);
        device.setTenantId(2L);
        assertThrows(RuntimeException.class, this::invoke);
    }

    @Test
    void missingFirmwareDisablesChoice() throws Exception {
        source("F1", "MT1", "A1", null, List.of("D1"), true);
        device.setFirmwareVersion(null);
        var n = json.valueToTree(invoke());
        assertFalse(n.at("/devices/0/available").asBoolean());
        assertFalse(n.at("/devices/0/blocker").asText().isBlank());
    }

    @Test
    void duplicateDeviceDisablesChoice() throws Exception {
        source("F1", "MT1", "A1", null, List.of("D1"), true);
        when(devices.selectList(any())).thenReturn(List.of(device, device));
        assertFalse(json.valueToTree(invoke()).at("/devices/0/available").asBoolean());
    }

    @Test
    void missingRegistryDoesNotInventFirmware() throws Exception {
        source("F1", "MT1", "A1", null, List.of("D1"), true);
        when(devices.selectList(any())).thenReturn(List.of());
        assertFalse(json.valueToTree(invoke()).at("/devices/0/available").asBoolean());
    }
}
