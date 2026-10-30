package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MdmMaterialIdentifierTemplateValidatorTest {
    @Test
    void blankTemplatePreservesManualBatchWorkflow() {
        var material = new MdmMaterialEntity();
        material.setBatchCodeTemplate(" ");

        assertDoesNotThrow(() -> MdmMaterialIdentifierTemplateValidator.validate(material));
    }

    @Test
    void invalidConfiguredTemplateIsRejectedBeforeMaterialSave() {
        var material = new MdmMaterialEntity();
        material.setBatchCodeTemplate("{MATERIAL}-{UNKNOWN}");

        assertThrows(CheckedException.class, () -> MdmMaterialIdentifierTemplateValidator.validate(material));
    }

    @Test
    void validTemplateIsStoredInTheSameCanonicalFormUsedForValidation() {
        var material = new MdmMaterialEntity();
        material.setBatchCodeTemplate("  {MATERIAL}-{SEQ:6}  ");

        MdmMaterialIdentifierTemplateValidator.validate(material);

        assertEquals("{MATERIAL}-{SEQ:6}", material.getBatchCodeTemplate());
    }

    @Test
    void singlePieceCodeTemplateIsValidatedAndCanonicalized() {
        var material = new MdmMaterialEntity();
        material.setSerialCodeTemplate("  SN-{FACTORY}-{SEQ:8}  ");

        MdmMaterialIdentifierTemplateValidator.validate(material);

        assertEquals("SN-{FACTORY}-{SEQ:8}", material.getSerialCodeTemplate());
        material.setSerialCodeTemplate("SN-{MATERIAL}");
        assertThrows(CheckedException.class, () -> MdmMaterialIdentifierTemplateValidator.validate(material));
    }
}
