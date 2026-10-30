package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.mapper.WmsBatchCodeSequenceMapper;
import com.kitex.mom.wms.mapper.WmsIdentifierPolicyMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsSerialCodeSequenceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsIdentifierPolicyServiceTest {
    private final WmsIdentifierPolicyMapper policies = mock(WmsIdentifierPolicyMapper.class);
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
    private final RemoteMaterialService materials = mock(RemoteMaterialService.class);
    private final WmsSerialIdentityLock serialLock = mock(WmsSerialIdentityLock.class);
    private final WmsBatchCodeSequenceMapper batchSequences = mock(WmsBatchCodeSequenceMapper.class);
    private final WmsSerialCodeSequenceMapper serialSequences = mock(WmsSerialCodeSequenceMapper.class);
    private final WmsIdentifierPolicyService service = new WmsIdentifierPolicyService(
            policies, stocks, materials, serialLock, batchSequences, serialSequences);

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        when(materials.getDetails(anyString())).thenAnswer(call -> R.ok(material(call.getArgument(0))));
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void exactMaterialAndBillRuleBeatsWildcardRules() {
        var generic = rule("*", "*");
        var material = rule("*", "M1");
        var exact = rule("other_in", "M1");
        when(policies.selectList(any())).thenReturn(List.of(generic, material, exact));

        assertSame(exact, service.resolve("other_in", "M1"));
        assertSame(material, service.resolve("purchase", "M1"));
    }

    @Test
    void skippedOrganizationScopeCannotResolveInboundIdentifierRule() {
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> service.resolve("purchase", "M1"));
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactorySkip();
        assertThrows(CheckedException.class, () -> service.resolve("purchase", "M1"));
        verifyZeroInteractions(policies);
    }

    @Test
    void configuredBatchAndSerialRulesAreEnforcedAtInbound() {
        var policy = rule("other_in", "M1").setBatchRequired(true).setBatchPattern("B[0-9]{3}")
                .setBarcodeRequired(true).setBarcodePattern("SN-[0-9]{4}").setSerialManaged(true);
        when(policies.selectList(any())).thenReturn(List.of(policy));
        when(stocks.selectCount(any())).thenReturn(0L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d)
                .setMatBatch("B001").setMatBarcode("SN-0001");

        assertDoesNotThrow(() -> service.validateInbound("other_in", detail));
        detail.setMatBatch("BAD");
        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
        detail.setMatBatch("B001").setMatNum(2d);
        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
    }

    @Test
    void serializedBarcodeCannotAlreadyExistInCurrentStock() {
        var policy = rule("other_in", "M1").setBarcodeRequired(true).setSerialManaged(true);
        when(policies.selectList(any())).thenReturn(List.of(policy));
        when(stocks.selectCount(any())).thenReturn(1L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d).setMatBarcode("SN-1");

        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
    }

    @Test
    void materialBatchManagementCannotBeDisabledByInboundRule() {
        var managed = material("M1");
        managed.setBatchManaged(true);
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of(rule("other_in", "M1")));
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(2d);

        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
        detail.setMatBatch("B001");
        assertDoesNotThrow(() -> service.validateInbound("other_in", detail));
    }

    @Test
    void emptyBatchIsGeneratedOnlyWhenInboundRuleOptsIn() {
        var managed = material("M1");
        managed.setBatchManaged(true);
        managed.setBatchCodeTemplate("{FACTORY}-{MATERIAL}-{SEQ:4}");
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of(
                rule("other_in", "M1").setBatchRequired(true).setAutoBatchOnMissing(true)));
        when(batchSequences.nextValue()).thenReturn(42L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(2d);

        service.fillMissingIdentifiers("other_in", detail);
        service.validateInbound("other_in", detail);

        assertEquals("F1-M1-0042", detail.getMatBatch());
        verify(batchSequences).nextValue();
    }

    @Test
    void explicitBatchIsNeverReplacedByAutomaticRule() {
        when(policies.selectList(any())).thenReturn(List.of(
                rule("other_in", "M1").setBatchRequired(true).setAutoBatchOnMissing(true)));
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(2d).setMatBatch("SUPPLIER-7");

        service.fillMissingIdentifiers("other_in", detail);

        assertEquals("SUPPLIER-7", detail.getMatBatch());
        verify(batchSequences, never()).nextValue();
    }

    @Test
    void legacyMaterialTemplateWithOuterSpacesUsesCanonicalRendering() {
        var managed = material("M1");
        managed.setBatchCodeTemplate(" {MATERIAL}-{SEQ:3} ");
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of(
                rule("other_in", "M1").setBatchRequired(true).setAutoBatchOnMissing(true)));
        when(batchSequences.nextValue()).thenReturn(7L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d);

        service.fillMissingIdentifiers("other_in", detail);

        assertEquals("M1-007", detail.getMatBatch());
    }

    @Test
    void singlePieceInboundGeneratesMissingSerialFromMaterialTemplate() {
        var managed = material("M1");
        managed.setSerialManaged(true);
        managed.setSerialCodeTemplate("SN-{FACTORY}-{MATERIAL}-{SEQ:6}");
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of(rule("other_in", "M1")
                .setBarcodeRequired(true).setSerialManaged(true).setAutoSerialOnMissing(true)));
        when(serialSequences.nextValue()).thenReturn(12L);
        when(stocks.selectCount(any())).thenReturn(0L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d);

        service.fillMissingIdentifiers("other_in", detail);
        service.validateInbound("other_in", detail);

        assertEquals("SN-F1-M1-000012", detail.getMatBarcode());
        verify(serialSequences).nextValue();
    }

    @Test
    void suppliedSerialIsKeptWhenAutomaticRuleIsEnabled() {
        when(policies.selectList(any())).thenReturn(List.of(rule("other_in", "M1")
                .setBarcodeRequired(true).setSerialManaged(true).setAutoSerialOnMissing(true)));
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d).setMatBarcode("EXT-1");

        service.fillMissingIdentifiers("other_in", detail);

        assertEquals("EXT-1", detail.getMatBarcode());
        verify(serialSequences, never()).nextValue();
    }

    @Test
    void bulkRuleCannotEnableSinglePieceCodeCreation() {
        var policy = rule("other_in", "M1").setBarcodeRequired(true).setAutoSerialOnMissing(true);

        assertThrows(CheckedException.class, () -> service.savePolicy(policy));
        verify(policies, never()).insert(any(WmsIdentifierPolicyEntity.class));
    }

    @Test
    void materialSerialManagementRequiresSinglePieceAndUniqueBarcode() {
        var managed = material("M1");
        managed.setSerialManaged(true);
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of());
        when(stocks.selectCount(any())).thenReturn(0L, 1L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(2d);

        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
        detail.setMatNum(1d).setMatBarcode("SN-1");
        assertDoesNotThrow(() -> service.validateInbound("other_in", detail));
        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
    }

    @Test
    void inboundPreviewIncludesMaterialBaselineWithoutChangingStoredRule() {
        var managed = material("M1");
        managed.setBatchManaged(true);
        managed.setSerialManaged(true);
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        var configured = rule("other_in", "M1");
        when(policies.selectList(any())).thenReturn(List.of(configured));

        var preview = service.resolveEffective("other_in", "M1");

        assertTrue(preview.getBatchRequired());
        assertTrue(preview.getSerialManaged());
        assertTrue(preview.getBarcodeRequired());
        assertFalse(configured.getSerialManaged());
    }

    @Test
    void materialIdentificationFromAnotherFactoryCannotBeApplied() {
        var wrongFactory = material("M1");
        wrongFactory.setFactoryCode("F2");
        when(materials.getDetails("M1")).thenReturn(R.ok(wrongFactory));
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d);

        assertThrows(CheckedException.class, () -> service.validateInbound("other_in", detail));
    }

    @Test
    void inactiveMaterialWithoutIdentificationRequirementKeepsPriorInboundBehavior() {
        var inactive = material("M1");
        inactive.setStatus(false);
        when(materials.getDetails("M1")).thenReturn(R.ok(inactive));
        when(policies.selectList(any())).thenReturn(List.of());
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(2d);

        assertDoesNotThrow(() -> service.validateInbound("other_in", detail));
    }

    @Test
    void postingSerializedMaterialFailsBeforeStockCheckWhenIdentityLockFails() {
        var managed = material("M1");
        managed.setSerialManaged(true);
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of());
        doThrow(new CheckedException("锁定序列号失败"))
                .when(serialLock).lock(1L, "F1", "SN-1");
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d).setMatBarcode("SN-1");

        assertThrows(CheckedException.class, () -> service.validateInboundForPosting("other_in", detail));
        verify(stocks, never()).selectCount(any());
    }

    @Test
    void bulkInboundCannotReuseActiveSerializedBarcode() {
        when(policies.selectList(any())).thenReturn(List.of());
        when(stocks.selectCount(any())).thenReturn(1L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(5d).setMatBarcode("SN-1");

        assertThrows(CheckedException.class, () -> service.validateInboundForPosting("other_in", detail));
        verify(serialLock).lock(1L, "F1", "SN-1");
    }

    @Test
    void postingNormalizesTheBarcodeThatWillBePersisted() {
        var managed = material("M1");
        managed.setSerialManaged(true);
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectList(any())).thenReturn(List.of());
        when(stocks.selectCount(any())).thenReturn(0L);
        var detail = new WmsInBillDetailEntity().setMatCode("M1").setMatNum(1d)
                .setMatBarcode(" SN-1 ").setMatBatch(" B001 ");

        assertTrue(service.validateInboundForPosting("other_in", detail));
        assertEquals("SN-1", detail.getMatBarcode());
        assertEquals("B001", detail.getMatBatch());
        verify(serialLock).lock(1L, "F1", "SN-1");
    }

    @Test
    void invalidRegexCannotBeSaved() {
        var policy = rule("other_in", "M1").setStatus(true).setBatchPattern("[");

        assertThrows(CheckedException.class, () -> service.savePolicy(policy));
        verify(policies, never()).insert(any(WmsIdentifierPolicyEntity.class));
    }

    @Test
    void materialSpecificRuleCannotReferenceUnknownMaterial() {
        when(materials.getDetails("UNKNOWN")).thenReturn(R.failed("not found"));

        var error = assertThrows(CheckedException.class,
                () -> service.savePolicy(rule("other_in", "UNKNOWN")));

        assertTrue(error.getMessage().contains("物料[UNKNOWN]"), error.getMessage());
        verify(policies, never()).insert(any(WmsIdentifierPolicyEntity.class));
    }

    @Test
    void materialSpecificAutomaticBatchRuleNeedsUsableTemplate() {
        var policy = rule("other_in", "M1").setBatchRequired(true).setAutoBatchOnMissing(true);

        var error = assertThrows(CheckedException.class, () -> service.savePolicy(policy));

        assertTrue(error.getMessage().contains("批次编码模板"), error.getMessage());
        verify(policies, never()).insert(any(WmsIdentifierPolicyEntity.class));
    }

    @Test
    void materialSpecificAutomaticSerialRuleNeedsUsableTemplate() {
        var managed = material("M1");
        managed.setSerialCodeTemplate("SN-{UNKNOWN}");
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        var policy = rule("other_in", "M1").setBarcodeRequired(true)
                .setSerialManaged(true).setAutoSerialOnMissing(true);

        var error = assertThrows(CheckedException.class, () -> service.savePolicy(policy));

        assertTrue(error.getMessage().contains("单件条码编码配置无效"), error.getMessage());
        verify(policies, never()).insert(any(WmsIdentifierPolicyEntity.class));
    }

    @Test
    void materialSpecificAutomaticBatchRuleAcceptsValidTemplate() {
        var managed = material("M1");
        managed.setBatchCodeTemplate("{FACTORY}-{MATERIAL}-{SEQ:4}");
        when(materials.getDetails("M1")).thenReturn(R.ok(managed));
        when(policies.selectCount(any())).thenReturn(0L);
        when(policies.insert(any(WmsIdentifierPolicyEntity.class))).thenReturn(1);
        var policy = rule("other_in", "M1").setBatchRequired(true).setAutoBatchOnMissing(true);

        assertSame(policy, service.savePolicy(policy));
        verify(policies).insert(policy);
    }

    @Test
    void editingRuleCanClearPreviouslyConfiguredPattern() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "identifier-policy"),
                WmsIdentifierPolicyEntity.class);
        var policy = rule("other_in", "M1").setBatchPattern(" ");
        policy.setId(9L);
        var existing = rule("other_in", "M1").setBatchPattern("B[0-9]+").setFactoryCode("F1");
        existing.setId(9L);
        existing.setTenantId(1L);
        when(policies.selectById(9L)).thenReturn(existing);
        when(policies.selectCount(any())).thenReturn(0L);
        when(policies.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertDoesNotThrow(() -> service.savePolicy(policy));

        @SuppressWarnings("unchecked")
        var wrapper = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(policies).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("batch_pattern"));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(null));
    }

    private WmsIdentifierPolicyEntity rule(String bill, String material) {
        return new WmsIdentifierPolicyEntity().setBillType(bill).setMaterialCode(material)
                .setBatchRequired(false).setBarcodeRequired(false).setSerialManaged(false).setStatus(true);
    }

    private MdmMaterialEntity material(String code) {
        var material = new MdmMaterialEntity();
        material.setMaterialCode(code);
        material.setFactoryCode("F1");
        material.setTenantId(1L);
        material.setStatus(true);
        return material;
    }
}
