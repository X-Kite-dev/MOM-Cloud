package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

class MesParameterPreparationOptionsTest {
    final ObjectMapper json = new ObjectMapper();
    MesActualFactoryAccessService access;
    MesTaskMapper tasks;
    MesTaskArrangeMapper arrangements;
    MesWorksiteMoldMapper bindings;
    MesTaskEntity task;
    MesTaskArrangeEntity arrange;
    MesWorksiteMoldEntity binding;

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(1L);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type :
                List.of(
                        MesTaskEntity.class,
                        MesTaskArrangeEntity.class,
                        MesWorksiteMoldEntity.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "options"),
                    type);
        access = mock(MesActualFactoryAccessService.class);
        when(access.require("read")).thenReturn("F1");
        tasks = mock(MesTaskMapper.class);
        arrangements = mock(MesTaskArrangeMapper.class);
        bindings = mock(MesWorksiteMoldMapper.class);
        task = new MesTaskEntity();
        task.setTenantId(1L);
        task.setFactoryCode("F1");
        task.setCode("MT1");
        task.setStatus("PRODUCT");
        arrange = new MesTaskArrangeEntity();
        arrange.setTenantId(1L);
        arrange.setFactoryCode("F1");
        arrange.setCode("A1");
        arrange.setTaskCode("MT1");
        arrange.setStatus("RECEIVE");
        arrange.setActualWorksiteCode("S1");
        arrange.setWorksiteCodes("S1");
        arrange.setRouteCode("WIRE");
        arrange.setSourceRouteNodeCode("RN-1");
        binding = new MesWorksiteMoldEntity();
        binding.setTenantId(1L);
        binding.setFactoryCode("F1");
        binding.setWorksiteCode("S1");
        binding.setDeviceCode("D1");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(arrangements.selectList(any())).thenReturn(List.of(arrange));
        when(bindings.selectList(any())).thenReturn(List.of(binding));
    }

    @AfterEach
    void clean() {
        TenantContextHolder.clear();
    }

    com.fasterxml.jackson.databind.JsonNode query(String arrangeCode, String deviceCode)
            throws Exception {
        var type =
                Class.forName(
                        "com.kitex.mom.mes.service.impl.MesParameterPreparationOptionsService");
        var qType =
                Class.forName("com.kitex.mom.mes.dto.MesParameterDispatchContextDTO$OptionsQuery");
        var q = new HashMap<String, Object>();
        q.put("factoryCode", "F1");
        q.put("taskCode", "MT1");
        q.put("arrangeCode", arrangeCode);
        q.put("deviceCode", deviceCode);
        var service = type.getConstructors()[0].newInstance(access, tasks, arrangements, bindings);
        try {
            return json.valueToTree(
                    type.getMethod("query", qType).invoke(service, json.convertValue(q, qType)));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    @Test
    void choicesRequireExactArrangementBeforeDeviceLookup() throws Exception {
        var root = query(null, null);
        assertEquals("A1", root.at("/arrangements/0/arrangeCode").asText());
        assertTrue(root.get("deviceCodes").isEmpty());
        verifyZeroInteractions(bindings);
        var selected = query("A1", null);
        assertEquals("D1", selected.at("/deviceCodes/0").asText());
    }

    @Test
    void endedTaskReturnsBlockerAndNeverQueriesDispatches() throws Exception {
        task.setStatus("FINISH");
        var root = query(null, null);
        assertFalse(root.get("blocker").asText().isBlank());
        assertTrue(root.get("arrangements").isEmpty());
        verifyZeroInteractions(arrangements, bindings);
    }

    @Test
    void foreignTenantTaskFailsClosed() {
        task.setTenantId(2L);
        assertThrows(RuntimeException.class, () -> query(null, null));
    }

    @Test
    void foreignTaskArrangementFailsClosed() {
        arrange.setTaskCode("OTHER");
        assertThrows(RuntimeException.class, () -> query("A1", null));
    }

    @Test
    void unknownRequestedArrangementIsNotSubstituted() {
        assertThrows(RuntimeException.class, () -> query("A2", null));
    }

    @Test
    void endedArrangementCannotBecomeCandidate() {
        arrange.setStatus("FINISH");
        assertThrows(RuntimeException.class, () -> query("A1", null));
    }

    @Test
    void actualStationMustBelongToOriginalDispatch() {
        arrange.setWorksiteCodes("S2");
        assertThrows(RuntimeException.class, () -> query("A1", null));
    }

    @Test
    void foreignBindingCannotLeakDevice() {
        binding.setFactoryCode("F2");
        assertThrows(RuntimeException.class, () -> query("A1", null));
    }

    @Test
    void plannedDeviceMismatchCannotBeOffered() {
        arrange.setPlannedDeviceCode("D2");
        assertThrows(RuntimeException.class, () -> query("A1", null));
    }

    @Test
    void selectedDeviceCannotBeSubstituted() {
        assertThrows(RuntimeException.class, () -> query("A1", "D2"));
    }

    @Test
    void capIsExplicitAndDoesNotQueryEachArrangement() throws Exception {
        var rows = new ArrayList<MesTaskArrangeEntity>();
        for (int i = 0; i < 101; i++) {
            var row = new MesTaskArrangeEntity();
            org.springframework.beans.BeanUtils.copyProperties(arrange, row);
            row.setCode("A" + i);
            rows.add(row);
        }
        when(arrangements.selectList(any())).thenReturn(rows);
        var root = query(null, null);
        assertEquals(100, root.get("arrangements").size());
        assertTrue(root.get("truncated").asBoolean());
        verifyZeroInteractions(bindings);
    }
}
