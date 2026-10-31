package com.kitex.mom.wms.repository;

import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;
import com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 每次读取都显式携带实际租户工厂，永久操作记录不依赖逻辑删除过滤。
 */
@Repository
@RequiredArgsConstructor
public class WmsProductionNoticeAcceptanceRepository {
    private final JdbcTemplate db;

    public record Head(int revision, String state) {
    }

    public record Accepted(Long id, Long noticeId, String noticePayload, String receiptPayload) {
    }

    public record Operation(String factory, Long documentId, int revision, String warehouse, String action,
                            String payload, String result) {
    }

    public record Notice(Long id, String state, BigDecimal quantity, BigDecimal out, String deleted) {
    }

    public record Detail(Long id, Long documentId, String documentTable, String warehouse, String material, String unit,
                         BigDecimal quantity, BigDecimal requirementQuantity, BigDecimal substituteRatio) {
    }

    public void lock(Long tenant, String key) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "WMS_PROD_NOTICE:" + tenant + ":" + key);
    }

    public Head head(Scope s, Long document, boolean lock) {
        return one(db.query(
                "SELECT current_revision,state FROM mom_wms.wms_production_notice_source WHERE tenant_id=? AND factory_code=? AND document_id=?" +
                        (lock ? " FOR UPDATE" : ""), (r, i) -> new Head(r.getInt(1), r.getString(2)), s.tenant(),
                s.factory(), document));
    }

    public int insertHead(Scope s, Long document, int revision, String state) {
        return db.update("INSERT INTO mom_wms.wms_production_notice_source VALUES(?,?,?,?,?,?)", s.tenant(),
                s.factory(), document, revision, state, LocalDateTime.now());
    }

    public int updateHead(Scope s, Long document, int expected, int revision, String state) {
        return db.update(
                "UPDATE mom_wms.wms_production_notice_source SET current_revision=?,state=?,updated_time=? WHERE tenant_id=? " +
                        "AND factory_code=? AND document_id=? AND current_revision=?", revision, state,
                LocalDateTime.now(), s.tenant(), s.factory(), document, expected);
    }

    public int latestNoticeRevision(Scope scope, Long document) {
        return db.queryForObject("SELECT COALESCE(MAX(COALESCE(source_acceptance_revision,1)),0) "
                        + "FROM mom_wms.wms_out_notice WHERE tenant_id=? AND factory_code=? "
                        + "AND document_table='mes_picking' AND document_id=?", Integer.class,
                scope.tenant(), scope.factory(), document);
    }

    public Accepted acceptance(Scope s, Long document, int revision, String warehouse) {
        return one(db.query(
                "SELECT id,notice_id,notice_payload,receipt_payload FROM mom_wms.wms_production_notice_acceptance WHERE " +
                        "tenant_id=? AND factory_code=? AND document_id=? AND source_revision=? AND warehouse_code=?",
                (r, i) -> new Accepted(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4)), s.tenant(),
                s.factory(), document, revision, warehouse));
    }

    public Operation operation(Long tenant, String request) {
        return one(db.query(
                "SELECT factory_code,document_id,source_revision,warehouse_code,action,request_payload,result_payload FROM " +
                        "mom_wms.wms_production_notice_operation WHERE tenant_id=? AND request_id=?",
                (r, i) -> new Operation(r.getString(1), r.getLong(2), r.getInt(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7)), tenant, request));
    }

    public int insertOperation(Scope s, String request, Long document, int revision, String warehouse, String action,
                               String payload, String result) {
        return db.update("INSERT INTO mom_wms.wms_production_notice_operation VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                s.tenant(), request, s.factory(), document, revision, warehouse, action, payload, result, s.userId(),
                s.operator(), LocalDateTime.now());
    }

    public int insertAcceptance(Scope s, Long id, Long document, int revision, String warehouse, Long notice,
                                String request, String payload, String fingerprint, String result, LocalDateTime time) {
        return db.update("INSERT INTO mom_wms.wms_production_notice_acceptance VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", id,
                s.tenant(), s.factory(), document, revision, warehouse, notice, request, payload, fingerprint, result,
                time);
    }

    public int insertLine(Scope scope, Long document, int revision, Long acceptance, int ordinal, Line line) {
        return db.update("INSERT INTO mom_wms.wms_production_notice_line VALUES(?,?,?,?,?,?,?,?,?,?,?)", acceptance,
                ordinal, line.sourceDocumentDetailId(), line.noticeDetailId(), line.materialCode(), line.unit(),
                line.quantity(), scope.tenant(), scope.factory(), document, revision);
    }

    public boolean sourceLineUsed(Scope s, Long document, int revision, List<Long> lines) {
        if (lines.isEmpty()) return false;
        return new NamedParameterJdbcTemplate(db).queryForObject(
                "SELECT count(*) FROM mom_wms.wms_production_notice_line WHERE tenant_id=:tenant AND factory_code=:factory AND " +
                        "document_id=:document AND source_revision=:revision AND source_document_detail_id IN (:lines)",
                Map.of("tenant", s.tenant(), "factory", s.factory(), "document", document, "revision", revision,
                        "lines", lines), Long.class) > 0;
    }

    public List<Notice> notices(Scope s, Long document, int revision, String warehouse, boolean lock) {
        String filter = warehouse == null ? "" : " AND warehouse_code=?";
        var args = new ArrayList<Object>(List.of(s.tenant(), s.factory(), document, revision));
        if (warehouse != null) args.add(warehouse);
        return db.query(
                "SELECT id,state,notice_out_num,all_out_num,del_flag FROM mom_wms.wms_out_notice WHERE tenant_id=? AND " +
                        "factory_code=? AND document_id=? AND document_table='mes_picking' AND COALESCE(source_acceptance_revision,1)=?" +
                        filter + " ORDER BY id LIMIT 1001" + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new Notice(r.getLong(1), r.getString(2), r.getBigDecimal(3), r.getBigDecimal(4),
                        r.getString(5)), args.toArray());
    }

    public Notice notice(Scope s, Long document, int revision, String warehouse, Long id, boolean lock) {
        return notices(s, document, revision, warehouse, lock).stream().filter(
                n -> n.id().equals(id)).findFirst().orElse(null);
    }

    public List<Detail> details(Scope s, Long notice) {
        return db.query(
                "SELECT id,document_id,document_table,warehouse_code,mat_code,mat_unit,notice_out_num,requirement_mat_num,substitute_ratio FROM " +
                        "mom_wms.wms_out_notice_detail WHERE tenant_id=? AND factory_code=? AND notice_id=? AND del_flag='0' ORDER BY " +
                        "id",
                (r, i) -> new Detail(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getBigDecimal(7), r.getBigDecimal(8), r.getBigDecimal(9)), s.tenant(),
                s.factory(), notice);
    }

    public boolean hasOutBills(Scope s, List<Long> notices) {
        if (notices.isEmpty()) return false;
        var params = Map.of("tenant", s.tenant(), "factory", s.factory(), "ids", notices);
        return new NamedParameterJdbcTemplate(db).queryForObject(
                "SELECT count(*) FROM mom_wms.wms_out_bill WHERE tenant_id=:tenant AND factory_code=:factory AND notice_id IN (:ids) AND del_flag='0'",
                params, Long.class) > 0;
    }

    private <T> T one(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
