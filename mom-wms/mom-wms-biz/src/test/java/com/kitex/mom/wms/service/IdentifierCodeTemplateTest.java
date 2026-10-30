package com.kitex.mom.wms.service;

import com.kitex.mom.common.identifier.IdentifierCodeTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentifierCodeTemplateTest {
    private final IdentifierCodeTemplate.Context context = new IdentifierCodeTemplate.Context(
            "1", "F1", "M100", "RAW", "A", "G1");

    @Test
    void rendersConfiguredMaterialPartsDateAndGlobalSequence() {
        String code = IdentifierCodeTemplate.render(
                "{FACTORY}-{MATERIAL}-{ATTRIBUTE}-{DATE:yyyyMMdd}-{SEQ:6}",
                context, LocalDateTime.of(2026, 10, 23, 9, 30), 42L);

        assertEquals("F1-M100-A-20261023-000042", code);
    }

    @Test
    void sequenceWidthPadsButNeverTruncates() {
        String code = IdentifierCodeTemplate.render("B-{SEQ:2}", context,
                LocalDateTime.of(2026, 10, 23, 9, 30), 123L);

        assertEquals("B-123", code);
    }

    @Test
    void rejectsUnknownOrMissingSequenceTokens() {
        assertThrows(IllegalArgumentException.class, () -> IdentifierCodeTemplate.validate("{MATERIAL}-{UNKNOWN}-{SEQ:6}"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierCodeTemplate.validate("{MATERIAL}-{DATE:yyyyMMdd}"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierCodeTemplate.validate("{SEQ:6}-{SEQ:6}"));
    }

    @Test
    void rejectsUnsafeMaterialValueRatherThanCreatingInvalidBatch() {
        var unsafe = new IdentifierCodeTemplate.Context("1", "F1", "M/100", "RAW", "A", "G1");

        assertThrows(IllegalArgumentException.class, () -> IdentifierCodeTemplate.render(
                "{MATERIAL}-{SEQ:6}", unsafe, LocalDateTime.of(2026, 10, 23, 9, 30), 1L));
    }
}
