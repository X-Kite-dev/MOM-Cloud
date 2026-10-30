package com.kitex.mom.plan.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner;
import com.kitex.mom.mes.dto.MesTaskIssueReceiptDTO;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.fixture.PlanningEngineFixture;
import com.kitex.mom.plan.service.impl.PlnApsFormalContractResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真正的合同解析器及普通/APS共用下达载荷方法；持久化与策略/合同端口受控。 */
class PlnFormalPlanMakeCoverageTest extends PlanningEngineFixture {
    private PlnApsFormalContractResolver resolver;
    private PlnFormalPlanContractDTO contract;
    private MdmFactoryPlanningPolicyDTO policy;
    private PlnIssueEntity issue;

    @BeforeEach
    void contractPorts() {
        var contracts = mock(PlnFormalPlanContractMapper.class);
        var service = mock(PlnFormalPlanContractService.class);
        var authority = mock(PlnFactoryPlanningAuthorityService.class);
        policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10");
        policy.setVersionNo(1);
        policy.setFactoryCode("F1");
        policy.setContentFingerprint("a".repeat(64));
        policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        contract = new PlnFormalPlanContractDTO();
        contract.setContractNo("C1");
        contract.setContractVersion(1);
        contract.setSourceContentFingerprint("b".repeat(64));
        contract.setSourceSystemCode("MOM");
        contract.setSourcePlanNo("MRP-1");
        contract.setSourcePlanVersion("1");
        contract.setLines(new ArrayList<>(List.of(line("22", "L2", "D1", "3"), line("21", "L1", "D1", "2"))));
        var row = new PlnFormalPlanContractEntity();
        row.setContractNo("C1");
        row.setContractVersion(1);
        when(contracts.selectList(any())).thenReturn(List.of(row));
        when(service.getVerifiedContract("C1", 1)).thenReturn(contract);
        when(authority.requireCurrentPolicy("F1")).thenReturn(policy);
        resolver = new PlnApsFormalContractResolver(mock(PlnSchedulingTaskDetailMapper.class),
                plnIssueMapper, contracts, service, authority);
        issue = issue(1L, "D1", "5");
    }

    private PlnFormalPlanContractDTO.Line line(String id, String no, String demand, String qty) {
        var line = new PlnFormalPlanContractDTO.Line();
        line.setId(id);
        line.setLineNo(no);
        line.setSourceDemandNo(demand);
        line.setSupplyType("MAKE");
        line.setProductCode("P1");
        line.setPlannedQuantity(new BigDecimal(qty));
        line.setUnitCode("EA");
        line.setContentFingerprint("c".repeat(64));
        return line;
    }

    private PlnIssueEntity issue(long id, String demand, String qty) {
        var item = new PlnIssueEntity();
        item.setId(id);
        item.setFactoryCode("F1");
        item.setSourceDemandNo(demand);
        item.setIssueNo("ISSUE-" + id);
        item.setProductCode("P1");
        item.setNum(new BigDecimal(qty));
        return item;
    }

    private MesTaskReceiveDTO command(List<PlnIssueEntity> issues) {
        var resolution = resolver.resolveIssues(issues, "F1");
        var receive = new MesTaskReceiveDTO();
        receive.setMatCode("P1");
        receive.setNoticeNum(BigDecimal.ONE);
        ReflectionTestUtils.invokeMethod(plnSchedulingEngineService, "applyFormalContract", receive, resolution,
                issues.stream().map(PlnIssueEntity::getId).toList(), issues.get(0));
        return receive;
    }

    @Test
    void wireRepresentativeIsStableAndWholeQuantityCoversTwoMakePaths() {
        var receive = command(List.of(issue));
        assertEquals(21L, receive.getFormalPlanContractId());
        assertEquals(0, new BigDecimal("5").compareTo(receive.getProductionOrderQuantity()));
        assertEquals("ISSUE-1", receive.getProductionOrder());
    }

    @Test
    void splitBatchKeepsWholeIssueOrderQuantity() {
        var receive = command(List.of(issue));
        assertEquals(BigDecimal.ONE, receive.getNoticeNum());
        assertEquals(0, new BigDecimal("5").compareTo(receive.getProductionOrderQuantity()));
    }

    @Test
    void erpOrderTotalIsStableWhenItsBucketsAreIssuedSeparately() {
        policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        contract.getLines().forEach(row -> row.setErpProductionOrderNo("ERP-1"));
        var first = command(List.of(issue(1L, "D1", "2")));
        var second = command(List.of(issue(2L, "D1", "3")));
        assertEquals("ERP-1", first.getProductionOrder());
        assertEquals("ERP-1", second.getProductionOrder());
        assertEquals(0, new BigDecimal("5").compareTo(first.getProductionOrderQuantity()));
        assertEquals(0, new BigDecimal("5").compareTo(second.getProductionOrderQuantity()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit", "bom", "route", "fingerprint", "duplicateId", "duplicateLine"})
    void formalPublicationRejectsIncompatibleOrDamagedCoverage(String damage) {
        var row = contract.getLines().get(1);
        switch (damage) {
            case "unit" -> row.setUnitCode("KG");
            case "bom" -> row.setBomVersion("B2");
            case "route" -> row.setRouteVersion("R2");
            case "fingerprint" -> row.setContentFingerprint(null);
            case "duplicateId" -> row.setId("22");
            case "duplicateLine" -> row.setLineNo("L2");
            default -> fail(damage);
        }
        assertThrows(CheckedException.class, () -> resolver.resolveIssues(List.of(issue), "F1"));
    }

    @Test
    void selectedIssueTotalCannotExceedFiveAcrossSeveralBuckets() {
        assertThrows(CheckedException.class, () -> resolver.resolveIssues(
                List.of(issue(1L, "D1", "3"), issue(2L, "D1", "3")), "F1"));
    }

    @Test
    void mergedUnitCannotHideAnotherDemandBehindRepresentative() {
        policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        contract.getLines().forEach(row -> row.setErpProductionOrderNo("ERP-1"));
        var extra = line("23", "L3", "D2", "2");
        extra.setErpProductionOrderNo("ERP-1");
        contract.getLines().add(extra);
        assertThrows(CheckedException.class, () -> command(List.of(issue(1L, "D1", "2"), issue(2L, "D2", "2"))));
    }

    @Test
    void issuesMustBelongToRequestedFactory() {
        issue.setFactoryCode("OTHER");
        assertThrows(CheckedException.class, () -> resolver.resolveIssues(List.of(issue), "F1"));
    }

    private PlnIssueModuleDTO dispatchRequest() {
        issue.setStatus("0"); issue.setScheduledDate(LocalDate.of(2026, 11, 1));
        issue.setDemandTime(LocalDate.of(2026, 11, 2));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "formalContractResolver", resolver);
        when(plnIssueMapper.selectBatchIds(anyCollection())).thenReturn(List.of(issue));
        when(plnIssueMapper.updateById(any(PlnIssueEntity.class))).thenReturn(1);
        var request = new PlnIssueModuleDTO(); request.setIds(new Long[]{1L});
        request.setWorkshopCode("WS1"); request.setWorkshopName("车间1");
        return request;
    }

    private MesTaskIssueReceiptDTO receipt(MesTaskReceiveDTO cmd) {
        return new MesTaskIssueReceiptDTO().setRequestId(cmd.getSourceRequestId())
                .setRequestFingerprint(cmd.getSourceRequestFingerprint()).setPlanNo(cmd.getPlanNo())
                .setTaskId(991L).setTaskCode("OLD-MES-1")
                .setAcceptedTime(LocalDateTime.of(2026, 10, 25, 1, 0)).setReplayed(true);
    }

    @Test
    void lostLegacyErpReceiptCanBeRestoredWithoutChangingOldPayload() {
        policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        contract.getLines().forEach(row -> row.setErpProductionOrderNo("ERP-1"));
        issue.setNum(new BigDecimal("2"));
        var request = dispatchRequest();
        var emitted = new AtomicReference<MesTaskReceiveDTO>();
        var firstWire = mock(RemoteMesTaskService.class, withSettings().defaultAnswer(call -> {
            if (call.getMethod().getName().equals("recoverReceiveReceipt")) return R.ok();
            if (call.getMethod().getName().equals("add")) {
                var cmd = (MesTaskReceiveDTO) call.getArgument(0);
                emitted.set(cn.hutool.core.bean.BeanUtil.copyProperties(cmd, MesTaskReceiveDTO.class));
                throw new CheckedException("模拟MES已接收但响应丢失");
            }
            return RETURNS_DEFAULTS.answer(call);
        }));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteTaskService", firstWire);
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.issueToModule(request));
        var legacy = emitted.get(); assertNotNull(legacy);
        legacy.setProductionOrderQuantity(new BigDecimal("2"));
        MesTaskReceiveRequestSigner.seal(legacy, legacy.getSourceRequestId());
        var oldReceipt = receipt(legacy);
        // 端口保留旧算法生成的原载荷及已接收回执；生产新方法出现前也能有效RED。
        var wire = mock(RemoteMesTaskService.class, withSettings().defaultAnswer(call -> {
            if (call.getMethod().getName().equals("recoverReceiveReceipt")) {
                Object argument = call.getArgument(0);
                var candidates = argument instanceof List<?> list ? list : List.of(argument);
                if (candidates.stream().map(MesTaskReceiveDTO.class::cast).anyMatch(candidate ->
                        Objects.equals(legacy.getSourceRequestFingerprint(), candidate.getSourceRequestFingerprint()))) return R.ok(oldReceipt);
                throw new CheckedException("旧请求指纹不一致");
            }
            if (call.getMethod().getName().equals("add")) throw new CheckedException("旧MES原请求不能改为新总量");
            return RETURNS_DEFAULTS.answer(call);
        }));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteTaskService", wire);
        issue.setStatus("0"); // 模拟PLAN事务回滚，MES永久回执已保留。
        assertDoesNotThrow(() -> plnSchedulingEngineService.issueToModule(request));
        assertEquals(legacy.getSourceRequestFingerprint(), issue.getMesIssueFingerprint());
        assertEquals(991L, issue.getMesTaskId());
        assertEquals("OLD-MES-1", issue.getMesTaskCode());
    }

    @Test
    void genuinelyNewErpRequestUsesWholeQuantityAfterNoOldReceiptObserved() {
        policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        contract.getLines().forEach(row -> row.setErpProductionOrderNo("ERP-1"));
        issue.setNum(new BigDecimal("2"));
        var request = dispatchRequest();
        var sent = new AtomicReference<MesTaskReceiveDTO>();
        var wire = mock(RemoteMesTaskService.class, withSettings().defaultAnswer(call -> {
            if (call.getMethod().getName().equals("recoverReceiveReceipt")) return R.ok();
            if (call.getMethod().getName().equals("add")) {
                var cmd = (MesTaskReceiveDTO) call.getArgument(0);
                sent.set(cmd); return R.ok(receipt(cmd).setReplayed(false));
            }
            return RETURNS_DEFAULTS.answer(call);
        }));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteTaskService", wire);
        assertTrue(plnSchedulingEngineService.issueToModule(request));
        assertEquals(0, new BigDecimal("5").compareTo(sent.get().getProductionOrderQuantity()));
        assertEquals(sent.get().getSourceRequestFingerprint(), issue.getMesIssueFingerprint());
    }

    @Test
    void lostNewFullQuantityReceiptAlsoReplaysAfterUpgrade() {
        policy.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        contract.getLines().forEach(row -> row.setErpProductionOrderNo("ERP-1"));
        issue.setNum(new BigDecimal("2"));
        var request = dispatchRequest();
        var accepted = new AtomicReference<MesTaskIssueReceiptDTO>();
        var wire = mock(RemoteMesTaskService.class, withSettings().defaultAnswer(call -> {
            if (call.getMethod().getName().equals("recoverReceiveReceipt")) {
                if (accepted.get() == null) return R.ok();
                Object argument = call.getArgument(0);
                var candidates = argument instanceof List<?> list ? list : List.of(argument);
                if (candidates.stream().map(MesTaskReceiveDTO.class::cast).anyMatch(candidate ->
                        Objects.equals(accepted.get().getRequestFingerprint(), candidate.getSourceRequestFingerprint()))) return R.ok(accepted.get());
                throw new CheckedException("新版本原载荷不能退回旧总量");
            }
            if (call.getMethod().getName().equals("add")) {
                accepted.set(receipt((MesTaskReceiveDTO) call.getArgument(0)));
                throw new CheckedException("模拟新请求已接收但回执响应丢失");
            }
            return RETURNS_DEFAULTS.answer(call);
        }));
        ReflectionTestUtils.setField(plnSchedulingEngineService, "remoteTaskService", wire);
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.issueToModule(request));
        issue.setStatus("0");
        assertDoesNotThrow(() -> plnSchedulingEngineService.issueToModule(request));
        assertEquals(accepted.get().getRequestFingerprint(), issue.getMesIssueFingerprint());
    }
}
