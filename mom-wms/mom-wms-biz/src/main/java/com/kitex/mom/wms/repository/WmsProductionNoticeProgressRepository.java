package com.kitex.mom.wms.repository;

import com.kitex.mom.wms.dto.WmsProductionNoticeProgressDTO.Bill;
import com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 通知、明细及关联单据均使用实际组织和仓库过滤，不依赖全局拦截器。
 */
@Repository
@RequiredArgsConstructor
public class WmsProductionNoticeProgressRepository {
    private final JdbcTemplate db;

    public record Notice(Long id, String warehouseName, String state, Long documentId, String documentTable,
                         Integer revision) {
    }

    public record Detail(Long id, String material, String name, String unit, BigDecimal planned,
                         BigDecimal allocated, BigDecimal remaining) {
    }

    public Notice notice(Scope scope, String warehouse, Long notice) {
        var rows = db.query("SELECT id,warehouse_name,state,document_id,document_table,source_acceptance_revision " +
                        "FROM mom_wms.wms_out_notice WHERE tenant_id=? AND factory_code=? AND warehouse_code=? " +
                        "AND id=? AND bill_type='produce' AND del_flag='0'",
                (r, i) -> new Notice(r.getLong(1), r.getString(2), r.getString(3), r.getObject(4, Long.class),
                        r.getString(5), r.getObject(6, Integer.class)), scope.tenant(), scope.factory(), warehouse,
                notice);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Detail> details(Scope scope, String warehouse, Long notice) {
        return db.query("SELECT id,mat_code,mat_name,mat_unit,notice_out_num,out_num,not_out_num " +
                        "FROM mom_wms.wms_out_notice_detail WHERE tenant_id=? AND factory_code=? AND warehouse_code=? " +
                        "AND notice_id=? AND del_flag='0' ORDER BY id LIMIT 1001",
                (r, i) -> new Detail(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getBigDecimal(5), r.getBigDecimal(6), r.getBigDecimal(7)),
                scope.tenant(), scope.factory(), warehouse, notice);
    }

    public List<Bill> bills(Scope scope, String warehouse, Long notice) {
        return db.query("SELECT id,bill_no,state,create_time FROM mom_wms.wms_out_bill " +
                        "WHERE tenant_id=? AND factory_code=? AND warehouse_code=? AND notice_id=? " +
                        "AND bill_type='produce' AND del_flag='0' ORDER BY id LIMIT 1001",
                (r, i) -> new Bill(r.getString(1), r.getString(2), r.getString(3),
                        r.getTimestamp(4) == null ? "" : r.getTimestamp(4).toLocalDateTime().toString()),
                scope.tenant(), scope.factory(), warehouse, notice);
    }
}
