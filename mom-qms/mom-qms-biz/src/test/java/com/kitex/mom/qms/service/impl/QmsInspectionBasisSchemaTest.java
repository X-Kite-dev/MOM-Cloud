package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

/**
 * Installation contract probes only; execution of PostgreSQL triggers is separate deployment
 * acceptance.
 */
class QmsInspectionBasisSchemaTest {
    private String sql() throws Exception {
        return Files.readString(
                Path.of("../../../docs/database/pending/qms-inspection-basis-freeze.sql"));
    }

    @Test
    void standaloneMigrationIsTheExactAdditiveCumulativeBlock() throws Exception {
        String
                full =
                        Files.readString(
                                Path.of("../../../docs/database/mom-cumulative-upgrade-pg-v4.sql")),
                block = sql();
        assertTrue(full.contains(block));
        assertEquals(1, full.split("-- inspection_basis_freeze_377_begin", -1).length - 1);
        assertTrue(block.contains("current_database()<>'kitex_mom'"));
        assertTrue(block.contains("同名未知检验依据表"));
    }

    @Test
    void immutableRowsAndExactBytesAreRequiredForReadiness() throws Exception {
        var s = sql();
        assertTrue(s.contains("IF TG_OP<>'INSERT'"));
        assertTrue(s.contains("BEFORE INSERT OR UPDATE OR DELETE"));
        assertTrue(s.contains("BEFORE TRUNCATE"));
        assertTrue(s.contains("ENABLE ALWAYS TRIGGER trg_qms_basis_row_377"));
        assertTrue(s.contains("ENABLE ALWAYS TRIGGER trg_qms_basis_truncate_377"));
        assertTrue(
                s.contains("payload_fingerprint=encode(sha256(convert_to(payload,'UTF8')),'hex')"));
        assertTrue(s.contains("actual IS DISTINCT FROM"));
        assertTrue(s.contains("md5(p.prosrc)"));
        assertFalse(s.matches("(?s).*UPDATE\\s+mom_qms\\.qms_inspection_basis\\s+SET.*"));
    }

    @Test
    void actualParentCompleteProjectsAndVersionChainAreGuarded() throws Exception {
        var s = sql();
        assertTrue(s.contains("factory_code=NEW.factory_code AND del_flag='0' FOR UPDATE"));
        assertTrue(s.contains("actual_count<>jsonb_array_length(j->'bindings')"));
        assertTrue(s.contains("NEW.revision IS DISTINCT FROM COALESCE(head.revision,0)+1"));
        assertTrue(
                s.contains("NEW.previous_fingerprint IS DISTINCT FROM head.payload_fingerprint"));
        assertTrue(s.contains("total_bytes+octet_length(NEW.payload)>8388608"));
        assertTrue(s.contains("r->'inspectionNum' IS DISTINCT FROM j->'plan'->'requestedCount'"));
    }
}
