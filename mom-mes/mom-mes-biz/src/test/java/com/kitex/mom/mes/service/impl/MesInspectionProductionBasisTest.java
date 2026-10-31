package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.mes.entity.MesQmsInspectionCommandEntity;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.qms.dto.inspection.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

class MesInspectionProductionBasisTest {
    MesParameterDispatchContextTest f;
    MesInspectionProductionBasisService service;
    MesQmsInspectionCommandMapper commands;
    MesInspectionProductionBasisMapper rows;
    MesQmsInspectionCommandEntity command;
    MesInspectionProductionBasisDTO.Query query;

    @BeforeEach
    void setup() throws Exception {
        f = new MesParameterDispatchContextTest();
        f.setup();
        FactoryContextHolder.setFactoryCode("F1");
        var json = f.json.readTree(f.task.getEngineeringBundleSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("bom")).put("unit", "EA");
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("bom"))
                .put("productCode", "MAT1");
        f.task.setEngineeringBundleSnapshot(f.json.writeValueAsString(json));
        f.task.setEngineeringContentHash(f.hash(f.task.getEngineeringBundleSnapshot()));
        f.operation.setMatCode("MAT1");
        commands = mock(MesQmsInspectionCommandMapper.class);
        rows = mock(MesInspectionProductionBasisMapper.class);
        command = new MesQmsInspectionCommandEntity();
        command.setId(88L);
        command.setTenantId(1L);
        command.setFactoryCode("F1");
        command.setDelFlag("0");
        command.setRequestId("MES_QMS_V2:OP1:FIRST:1");
        command.setSourceTaskCode("OP1");
        command.setCommandVersion(InspectionProcedureSourceContract.VERSION);
        command.setStatus("OUTBOUND_STARTED");
        var p =
                new InspectionProcedureCreateParam()
                        .setSourceRequestId(command.getRequestId())
                        .setSourceTaskCode("OP1")
                        .setTaskCode("OP1")
                        .setOrderNo("MT1")
                        .setOrderId("1")
                        .setMatCode("MAT1")
                        .setProcessCode("WIRE")
                        .setNum(2)
                        .setLabelType("FIRST");
        command.setCommandSnapshot(InspectionProcedureSourceContract.snapshot(p));
        command.setCommandFingerprint(InspectionProcedureSourceContract.fingerprint(p));
        query =
                new MesInspectionProductionBasisDTO.Query(
                        command.getRequestId(),
                        "OP1",
                        command.getCommandSnapshot(),
                        command.getCommandFingerprint());
        when(commands.original(1L, command.getRequestId())).thenReturn(command);
        when(rows.operation(1L, "F1", "OP1")).thenReturn(List.of(f.operation));
        when(rows.order(1L, "F1", "MT1")).thenReturn(List.of(f.task));
        service = new MesInspectionProductionBasisService(commands, rows, f.engineering);
    }

    @AfterEach
    void close() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void readsTheStoredEngineeringNodeAndBomUnitWithoutQueryingTodaysMdm() {
        var v = service.query(query);
        assertEquals("FROZEN", v.availability());
        assertEquals("RN-1", v.routeNodeCode());
        assertEquals("EA", v.quantityUnit());
        assertEquals("9", v.engineeringReleaseId());
        assertEquals("13", v.bomId());
        assertEquals(command.getCommandFingerprint(), v.commandFingerprint());
    }

    @Test
    void anOriginalV2CommandWithoutOptionalOrderIdStillResolvesTheActualOwner() {
        var p =
                new InspectionProcedureCreateParam()
                        .setTaskCode("OP1")
                        .setSourceTaskCode("OP1")
                        .setSourceRequestId(command.getRequestId())
                        .setOrderNo("MT1")
                        .setMatCode("MAT1")
                        .setProcessCode("WIRE")
                        .setNum(2)
                        .setLabelType("MIDDLE");
        command.setCommandSnapshot(InspectionProcedureSourceContract.snapshot(p));
        command.setCommandFingerprint(InspectionProcedureSourceContract.fingerprint(p));
        var q =
                new MesInspectionProductionBasisDTO.Query(
                        command.getRequestId(),
                        "OP1",
                        command.getCommandSnapshot(),
                        command.getCommandFingerprint());
        assertEquals("1", service.query(q).orderId());
    }

    @Test
    void missingFrozenUnitNeverDefaultsToPieces() throws Exception {
        var j = f.json.readTree(f.task.getEngineeringBundleSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode) j.get("bom")).remove("unit");
        f.task.setEngineeringBundleSnapshot(f.json.writeValueAsString(j));
        f.task.setEngineeringContentHash(f.hash(f.task.getEngineeringBundleSnapshot()));
        var v = service.query(query);
        assertEquals("PARTIAL", v.availability());
        assertNull(v.quantityUnit());
        assertEquals(List.of("QUANTITY_UNIT_MISSING"), v.dataGaps());
    }

    @Test
    void aBomForAnotherProductCannotSupplyTheInspectionQuantityUnit() throws Exception {
        var j = f.json.readTree(f.task.getEngineeringBundleSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode) j.get("bom")).put("productCode", "OTHER");
        f.task.setEngineeringBundleSnapshot(f.json.writeValueAsString(j));
        f.task.setEngineeringContentHash(f.hash(f.task.getEngineeringBundleSnapshot()));
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void noOriginalBundleIsExplicitLegacyUnavailable() {
        f.task.setEngineeringBundleSnapshot(null);
        f.task.setEngineeringReleaseId(null);
        f.task.setEngineeringContentHash(null);
        f.task.setEngineeringVersionNo(null);
        assertEquals("LEGACY_UNAVAILABLE", service.query(query).availability());
    }

    @Test
    void partialMissingEngineeringIdentityCannotMasqueradeAsLegacy() {
        f.task.setEngineeringBundleSnapshot(null);
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void aRepeatedProcessCannotReplaceTheActualStableNode() {
        f.operation.setSourceRouteNodeCode("RN-2");
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void aCorruptOriginalCannotBeRebuiltFromCurrentMasterData() {
        f.task.setEngineeringBundleSnapshot("{}");
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGISTERED", "FAILED", ""})
    void anUnsentCommandIsNotProductionEvidence(String status) {
        command.setStatus(status);
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void changedCommandBodyUnderSameFingerprintIsRejected() {
        command.setCommandSnapshot(command.getCommandSnapshot().replace("MAT1", "MAT2"));
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void aValidButDifferentQueryCannotSubstituteThePermanentCommand() {
        query =
                new MesInspectionProductionBasisDTO.Query(
                        query.requestId(),
                        "OP2",
                        query.commandSnapshot(),
                        query.commandFingerprint());
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void foreignStoredOwnerIsRejected() {
        command.setFactoryCode("F2");
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void foreignOperationIsRejected() {
        f.operation.setTenantId(2L);
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void noScopeCannotReadAStoredCommand() {
        FactoryContextHolder.clear();
        assertThrows(RuntimeException.class, () -> service.query(query));
    }

    @Test
    void reworkCannotBeCertifiedFromItsUnmodifiedProductionPackage() {
        f.task.setReworkRequestId("REWORK-1");
        var v = service.query(query);
        assertEquals("REWORK_UNAVAILABLE", v.availability());
        assertNull(v.routeNodeCode());
        assertNull(v.quantityUnit());
    }
}
