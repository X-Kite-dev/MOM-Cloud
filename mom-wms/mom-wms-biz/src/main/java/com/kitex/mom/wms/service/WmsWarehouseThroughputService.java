package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** 最近一段时间按仓单据审核量与审核历时。 */
@Service
public class WmsWarehouseThroughputService {
    private static final String DEFINITION = "仅统计审核时间落在查询窗口且状态为已通过(2)的单据；"
            + "审核历时=审核时间-建单时间，仅对时间完整且非负的单据求平均；缺时单据单独计数。"
            + "入库和出库按各自权限展示，未授权不返回数量；该指标不是拣货工时或库存周转率。";

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public WmsWarehouseThroughputService(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public Throughput query(String warehouseCode, int days) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        String warehouse = StrUtil.trim(warehouseCode);
        if (tenant == null || StrUtil.isBlank(factory) || StrUtil.isBlank(warehouse) || warehouse.contains("*")
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || days < 1 || days > 90) {
            throw new CheckedException("作业效率查询必须指定有效的租户、工厂、仓库和1至90天窗口");
        }
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(days);
        Metric inbound = canView("in", warehouse) ? aggregate("wms_in_bill", tenant, factory, warehouse, from, to)
                : null;
        Metric outbound = canView("out", warehouse) ? aggregate("wms_out_bill", tenant, factory, warehouse, from, to)
                : null;
        return new Throughput(warehouse, from, to, days, inbound, outbound, DEFINITION);
    }

    private boolean canView(String direction, String warehouse) {
        String scoped = "wms_bill_view_" + direction + "_" + warehouse;
        String global = "in".equals(direction) ? "wms_wmsInBill_view" : "wms_wmsOutBill_view";
        return permissions.hasPermission(scoped) || permissions.hasPermission(global);
    }

    private Metric aggregate(String table, Long tenant, String factory, String warehouse,
                             LocalDateTime from, LocalDateTime to) {
        // table只来自两个固定调用点，所有业务维度和时间均使用绑定参数。
        String sql = "SELECT COUNT(*) AS bill_count, "
                + "COUNT(CASE WHEN create_time IS NOT NULL AND review_time >= create_time THEN 1 END) AS timed_count, "
                + "COALESCE(SUM(CASE WHEN create_time IS NOT NULL AND review_time >= create_time "
                + "THEN EXTRACT(EPOCH FROM (review_time - create_time)) ELSE 0 END), 0) AS total_seconds "
                + "FROM " + table + " WHERE tenant_id = ? AND factory_code = ? AND warehouse_code = ? "
                + "AND state = '2' AND del_flag = '0' AND review_time >= ? AND review_time < ?";
        Metric result = jdbc.queryForObject(sql, (rs, ignored) -> {
            long bills = rs.getLong("bill_count");
            long timed = rs.getLong("timed_count");
            BigDecimal seconds = rs.getBigDecimal("total_seconds");
            if (timed > bills || seconds == null || seconds.signum() < 0) {
                throw new CheckedException("单据审核时效统计结果无效");
            }
            BigDecimal average = timed == 0 ? null
                    : seconds.divide(BigDecimal.valueOf(timed * 60), 1, RoundingMode.HALF_UP);
            return new Metric(bills, timed, bills - timed, average);
        }, tenant, factory, warehouse, from, to);
        if (result == null) {
            throw new CheckedException("单据审核时效统计未返回结果");
        }
        return result;
    }

    public record Throughput(String warehouseCode, LocalDateTime from, LocalDateTime to, int days,
                             Metric inbound, Metric outbound, String definition) {
    }

    public record Metric(long reviewedBills, long timedBills, long missingTimeBills,
                         BigDecimal averageReviewMinutes) {
    }
}
