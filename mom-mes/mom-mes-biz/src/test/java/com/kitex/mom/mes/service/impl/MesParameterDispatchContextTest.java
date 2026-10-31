package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.feign.RemoteMdmProcessParameterStandardService;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Real frozen-catalog validator and new context resolver; read-only DB/MDM ports adapted. */
class MesParameterDispatchContextTest {
    MesTaskEntity task;
    MesTaskArrangeEntity arrange;
    MesProduceTaskEntity operation;
    MesWorksiteMoldEntity binding;
    MesActualFactoryAccessService access;
    MesTaskMapper tasks;
    MesTaskArrangeMapper arrangements;
    MesProduceTaskMapper operations;
    MesWorksiteMoldMapper bindings;
    MesProcessParameterStandardFreezeService freeze;
    MesEngineeringPackageService engineering;
    MesReworkMaterialPlanService reworkPlans;
    String engineeringParameterProjection;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        var config = new MybatisConfiguration();
        for (var type :
                List.of(
                        MesTaskEntity.class,
                        MesTaskArrangeEntity.class,
                        MesProduceTaskEntity.class,
                        MesWorksiteMoldEntity.class))
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(config, "parameter-context"), type);
        access = mock(MesActualFactoryAccessService.class);
        when(access.require("read")).thenReturn("F1");
        tasks = mock(MesTaskMapper.class);
        arrangements = mock(MesTaskArrangeMapper.class);
        operations = mock(MesProduceTaskMapper.class);
        bindings = mock(MesWorksiteMoldMapper.class);
        task = new MesTaskEntity();
        task.setId(1L);
        task.setTenantId(1L);
        task.setFactoryCode("F1");
        task.setCode("MT1");
        task.setStatus("DISPATCH");
        task.setProcessRouteId(11L);
        task.setProcessRouteCode("R1");
        task.setEngineeringReleaseId(9L);
        task.setEngineeringVersionNo(2);
        task.setEngineeringBundleSnapshot("{\"revision\":2}");
        task.setEngineeringContentHash(hash(task.getEngineeringBundleSnapshot()));
        arrange = new MesTaskArrangeEntity();
        arrange.setTenantId(1L);
        arrange.setFactoryCode("F1");
        arrange.setCode("A1");
        arrange.setTaskCode("MT1");
        arrange.setProduceTaskCode("OP1");
        arrange.setRouteCode("WIRE");
        arrange.setSourceRouteNodeCode("RN-1");
        arrange.setActualWorksiteCode("S1");
        arrange.setPlannedDeviceCode("D1");
        arrange.setStatus("RECEIVE");
        arrange.setWorksiteCodes("S1");
        operation = new MesProduceTaskEntity();
        operation.setTenantId(1L);
        operation.setFactoryCode("F1");
        operation.setCode("OP1");
        operation.setTaskCode("MT1");
        operation.setRouteCode("WIRE");
        operation.setSourceRouteNodeCode("RN-1");
        operation.setProcessRouteId(11L);
        operation.setProcessRouteCode("R1");
        operation.setStatus("RECEIVE");
        operation.setWorksiteCodes("S1");
        binding = new MesWorksiteMoldEntity();
        binding.setTenantId(1L);
        binding.setFactoryCode("F1");
        binding.setWorksiteCode("S1");
        binding.setDeviceCode("D1");
        var route = new MdmProcessRouteWorkingDTO();
        route.setRouteNodeCode("RN-1");
        route.setProcessCode("WIRE");
        route.setFactoryCode("F1");
        var parameter =
                new MdmProcessParameterStandardDTO.FrozenParameter()
                        .setSourceItemId(5L)
                        .setRouteNodeCode("RN-1")
                        .setProcessCode("WIRE")
                        .setIdentifier("torque")
                        .setDataType("DECIMAL")
                        .setUnit("Nm")
                        .setTargetValue(new BigDecimal("2.50000000000000001"))
                        .setLowerLimit(new BigDecimal("2"))
                        .setUpperLimit(new BigDecimal("3"))
                        .setRequired(true);
        var catalog =
                new MdmProcessParameterStandardDTO.FrozenCatalog()
                        .setAvailable(true)
                        .setSnapshotSchemaVersion("ROUTE_NODE_V2")
                        .setContentSchemaVersion("NODE_V2")
                        .setProcessRouteId(11L)
                        .setRouteCode("R1")
                        .setRouteNodeProcesses(Map.of("RN-1", "WIRE"))
                        .setStandardId(12L)
                        .setStandardNo("PS1")
                        .setVersionNo(2)
                        .setContentFingerprint("a".repeat(64))
                        .setParameters(List.of(parameter));
        freeze =
                new MesProcessParameterStandardFreezeService(
                        mock(RemoteMdmProcessParameterStandardService.class));
        var frozen =
                freeze.freezeFromEngineeringSnapshot(
                        task, List.of(route), JSONUtil.toJsonStr(catalog));
        task.setMatCode("MAT1");
        task.setBomId(13L);
        task.setBomVersion("V1");
        var graph =
                Map.of(
                        "procedureList",
                        List.of(
                                Map.of(
                                        "routeNodeCode",
                                        "RN-1",
                                        "processCode",
                                        "WIRE",
                                        "factoryCode",
                                        "F1")));
        var frozenNode =
                Map.of(
                        "routeNodeCode",
                        "RN-1",
                        "processCode",
                        "WIRE",
                        "works",
                        List.of(Map.of("workCode", "S1", "workName", "工位1")),
                        "materials",
                        List.of(),
                        "molds",
                        List.of(),
                        "legacySops",
                        List.of());
        var original = new LinkedHashMap<String, Object>();
        original.put("tenantId", "1");
        original.put("factoryCode", "F1");
        original.put("route", Map.of("id", "11", "routeCode", "R1", "productCode", "MAT1"));
        original.put("bom", Map.of("id", "13", "version", "V1"));
        original.put("bomStructure", Map.of("children", List.of()));
        original.put("graph", graph);
        original.put("nodes", List.of(frozenNode));
        original.put("parameterCatalog", catalog);
        original.put("sopManifest", Map.of());
        String bundleJson = json.writeValueAsString(original);
        task.setEngineeringBundleSnapshot(bundleJson);
        task.setEngineeringContentHash(hash(bundleJson));
        var pack = new com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO.ProductionPackage();
        pack.setReleaseId(9L);
        pack.setVersionNo(2);
        pack.setContentHash(hash(bundleJson));
        pack.setProductCode("MAT1");
        pack.setTenantId("1");
        pack.setFactoryCode("F1");
        pack.setSchemaVersion(com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO.SCHEMA_VERSION);
        pack.setRouteId(11L);
        pack.setRouteCode("R1");
        pack.setBomId(13L);
        pack.setBomVersion("V1");
        pack.setBundleSnapshot(bundleJson);
        var remote = mock(com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService.class);
        when(remote.resolveForProduction(any())).thenReturn(com.kitex.common.core.util.R.ok(pack));
        engineering = new MesEngineeringPackageService(remote);
        var receive = new com.kitex.mom.mes.dto.MesTaskReceiveDTO();
        receive.setEngineeringReleaseId(9L);
        receive.setEngineeringContentHash(hash(bundleJson));
        receive.setMatCode("MAT1");
        receive.setFactoryCode("F1");
        var released = engineering.resolve(receive);
        engineeringParameterProjection = released.parameterCatalogSnapshot();
        task.setProcessRouteSnapshot(released.routeSnapshot());
        task.setBomSnapshot(released.bomSnapshot());
        when(remote.resolveForProduction(any()))
                .thenThrow(new IllegalStateException("禁止重选今日MDM工程包"));
        reworkPlans = mock(MesReworkMaterialPlanService.class);

        task.setProcessParameterStandardSnapshot(frozen.snapshot());
        task.setProcessParameterSnapshotFingerprint(frozen.fingerprint());
        task.setProcessParameterStandardNo("PS1");
        task.setProcessParameterStandardVersion(2);
        operation.setProcessParameterStandardNo("PS1");
        operation.setProcessParameterStandardVersion(2);
        operation.setProcessParameterStandardSnapshot(
                frozen.nodeSnapshots().get("RN-1").snapshot());
        operation.setProcessParameterSnapshotFingerprint(
                frozen.nodeSnapshots().get("RN-1").fingerprint());
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(arrangements.selectList(any())).thenReturn(List.of(arrange));
        when(operations.selectList(any())).thenReturn(List.of(operation));
        when(bindings.selectList(any())).thenReturn(List.of(binding));
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    String hash(String text) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    Object query() throws Exception {
        Class<?> dto, service;
        try {
            dto = Class.forName("com.kitex.mom.mes.dto.MesParameterDispatchContextDTO$Query");
            service =
                    Class.forName(
                            "com.kitex.mom.mes.service.impl.MesParameterDispatchContextService");
        } catch (ClassNotFoundException absent) {
            return fail("Frozen dispatch context is not implemented");
        }
        var input =
                json.convertValue(
                        Map.of(
                                "factoryCode",
                                "F1",
                                "taskCode",
                                "MT1",
                                "arrangeCode",
                                "A1",
                                "deviceCode",
                                "D1"),
                        dto);
        Object[] dependencies = {
            access, tasks, arrangements, operations, bindings, freeze, engineering, reworkPlans
        };
        var constructor = service.getConstructors()[0];
        var instance =
                constructor.newInstance(
                        Arrays.copyOf(dependencies, constructor.getParameterCount()));
        try {
            return service.getMethod("query", dto).invoke(instance, input);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    @Test
    void beforeStartUsesOnlyFrozenNodeAndConfirmedStationDevice() throws Exception {
        var value = json.valueToTree(query());
        assertEquals("RN-1", value.path("routeNodeCode").asText());
        assertEquals("S1", value.path("stationCode").asText());
        assertEquals("D1", value.path("deviceCode").asText());
        assertEquals(
                "2.50000000000000001",
                value.path("parameters").get(0).path("targetValue").asText());
        assertEquals("Nm", value.path("parameters").get(0).path("unit").asText());
        assertEquals(9L, value.path("engineeringReleaseId").asLong());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "tenant",
                "factory",
                "task",
                "arrangeTask",
                "operationTask",
                "node",
                "station",
                "binding",
                "plannedDevice",
                "finished",
                "unreceived",
                "engineering",
                "parameterHash",
                "nodeSnapshot",
                "operationUnknown",
                "operationFinished",
                "arrangeStations",
                "operationStations"
            })
    void inconsistentOriginalIdentityCannotAuthorizeDeviceParameters(String changed)
            throws Exception {
        switch (changed) {
            case "tenant" -> arrange.setTenantId(2L);
            case "factory" -> task.setFactoryCode("F2");
            case "task" -> task.setCode("OTHER");
            case "arrangeTask" -> arrange.setTaskCode("OTHER");
            case "operationTask" -> operation.setTaskCode("OTHER");
            case "node" -> arrange.setSourceRouteNodeCode("RN-2");
            case "station" -> arrange.setActualWorksiteCode(null);
            case "binding" -> binding.setWorksiteCode("OTHER");
            case "plannedDevice" -> arrange.setPlannedDeviceCode("D2");
            case "finished" -> task.setStatus("FINISH");
            case "unreceived" -> arrange.setStatus("UN_RECEIVE");
            case "engineering" -> task.setEngineeringContentHash("b".repeat(64));
            case "parameterHash" -> task.setProcessParameterSnapshotFingerprint("b".repeat(64));
            case "nodeSnapshot" -> operation.setProcessParameterStandardSnapshot("{}");
            case "operationUnknown" -> operation.setStatus("UNKNOWN");
            case "operationFinished" -> operation.setStatus("FINISH");
            case "arrangeStations" -> arrange.setWorksiteCodes("S2");
            case "operationStations" -> operation.setWorksiteCodes("S2");
        }
        assertThrows(RuntimeException.class, this::query);
    }

    void replaceCatalog(String process, String target) throws Exception {
        var catalog =
                JSONUtil.toBean(
                        task.getProcessParameterStandardSnapshot(),
                        MdmProcessParameterStandardDTO.FrozenCatalog.class);
        catalog.setRouteNodeProcesses(Map.of("RN-1", process));
        var item = catalog.getParameters().get(0);
        item.setProcessCode(process);
        item.setTargetValue(new BigDecimal(target));
        item.setLowerLimit(new BigDecimal("0"));
        item.setUpperLimit(new BigDecimal("10"));
        var rows = JSONUtil.toList(task.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
        rows.get(0).setProcessCode(process);
        task.setProcessRouteSnapshot(JSONUtil.toJsonStr(rows));
        var changed = freeze.freezeFromEngineeringSnapshot(task, rows, JSONUtil.toJsonStr(catalog));
        task.setProcessParameterStandardSnapshot(changed.snapshot());
        task.setProcessParameterSnapshotFingerprint(changed.fingerprint());
        operation.setProcessParameterStandardSnapshot(
                changed.nodeSnapshots().get("RN-1").snapshot());
        operation.setProcessParameterSnapshotFingerprint(
                changed.nodeSnapshots().get("RN-1").fingerprint());
    }

    @Test
    void selectedProcessMustEqualOriginalFrozenNodeMapping() throws Exception {
        arrange.setRouteCode("CUT");
        operation.setRouteCode("CUT");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void unrelatedCorrectlyHashedEngineeringBodyCannotAuthorizeParameters() throws Exception {
        task.setEngineeringBundleSnapshot("{\"revision\":2}");
        task.setEngineeringContentHash(hash(task.getEngineeringBundleSnapshot()));
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void selfConsistentReplacementCatalogCannotClaimOriginalEngineeringHash() throws Exception {
        replaceCatalog("WIRE", "9");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void selfConsistentRouteReplacementCannotClaimOriginalEngineeringGraph() throws Exception {
        replaceCatalog("CUT", "2.5");
        arrange.setRouteCode("CUT");
        operation.setRouteCode("CUT");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void issueTimeSopAndExecutionEnrichmentDoesNotReplaceEngineeringParameterAuthority()
            throws Exception {
        var rows = JSONUtil.parseArray(task.getProcessRouteSnapshot());
        rows.getJSONObject(0)
                .set("routeExecutionSchemaVersion", "NODE_EXECUTION_V1")
                .set("formalSopId", "formal-001");
        task.setProcessRouteSnapshot(rows.toString());
        assertEquals(
                "2.50000000000000001",
                json.valueToTree(query()).path("parameters").get(0).path("targetValue").asText());
    }

    void approvedRework(String mode) throws Exception {
        task.setResourceType("REWORK");
        task.setReworkMaterialPlanId(31L);
        task.setReworkEngineeringMode(mode);
        task.setReworkRouteNodeCode("RN-1");
        String scope =
                json.writeValueAsString(
                        Map.of(
                                "version",
                                MesReworkEngineeringOverrideService.VERSION,
                                "mode",
                                mode,
                                "baseReleaseId",
                                "9",
                                "baseContentHash",
                                task.getEngineeringContentHash(),
                                "targetRouteNodeCode",
                                "RN-1"));
        task.setReworkEngineeringScopeSnapshot(scope);
        task.setReworkEngineeringScopeFingerprint(hash(scope));
    }

    @Test
    void reworkRequiresAnApprovedOriginalBasis() throws Exception {
        task.setResourceType("REWORK");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void existingReworkAuthorityRejectionIsNotIgnored() throws Exception {
        approvedRework("INHERIT_SOURCE");
        doThrow(new IllegalArgumentException("原批准方案与当前参数不一致")).when(reworkPlans).assertReady(task);
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void approvedReworkParametersCarryTheirDistinctApprovalIdentity() throws Exception {
        replaceCatalog("WIRE", "9");
        approvedRework("APPROVED_OVERRIDE");
        task.setReworkEngineeringOverrideId(51L);
        task.setReworkEngineeringOverrideHash("d".repeat(64));
        var value = json.valueToTree(query());
        assertEquals("9", value.path("parameters").get(0).path("targetValue").asText());
        assertEquals("APPROVED_OVERRIDE", value.path("reworkEngineeringMode").asText());
        assertEquals("31", value.path("reworkMaterialPlanId").asText());
        assertEquals("51", value.path("reworkEngineeringOverrideId").asText());
    }

    @Test
    void engineeringOriginalParserKeepsFullTargetPrecision() {
        var catalog =
                JSONUtil.toBean(
                        engineeringParameterProjection,
                        MdmProcessParameterStandardDTO.FrozenCatalog.class);
        assertEquals(
                0,
                new BigDecimal("2.50000000000000001")
                        .compareTo(catalog.getParameters().get(0).getTargetValue()));
    }

    @Test
    void equivalentApprovedBomDecimalProjectionRemainsUsable() throws Exception {
        String original =
                task.getEngineeringBundleSnapshot()
                        .replace("\"children\":[]", "\"children\":[{\"num\":2.0}]");
        task.setEngineeringBundleSnapshot(original);
        task.setEngineeringContentHash(hash(original));
        task.setBomSnapshot("[{\"num\":2.0}]");
        assertEquals(
                "RN-1",
                json.valueToTree(assertDoesNotThrow(this::query)).path("routeNodeCode").asText());
    }
}
