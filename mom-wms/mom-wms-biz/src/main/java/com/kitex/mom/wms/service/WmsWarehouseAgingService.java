package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 按当前库存行统计仓库库龄；不推导历史周转率或库位容量。 */
@Service
public class WmsWarehouseAgingService {
    private static final int MAX_BUCKET_ROWS = 600;
    private static final String DEFINITION = "按当前仓库未删除且实物量大于零的库存行，以入库时间距查询时间分为"
            + "0-30、31-90、91-180、180天以上、时间未知及未来时间；条形图按库存行数计算。"
            + "数量仅在同一单位内比较，不跨单位相加；这是当前库存库龄，不是库存周转率。";

    private final JdbcTemplate jdbc;
    private final WmsLiveStockReadAccess readAccess;

    public WmsWarehouseAgingService(JdbcTemplate jdbc, WmsLiveStockReadAccess readAccess) {
        this.jdbc = jdbc;
        this.readAccess = readAccess;
    }

    public Aging query(String warehouseCode) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        String warehouse = StrUtil.trim(warehouseCode);
        if (tenant == null || StrUtil.isBlank(factory) || StrUtil.isBlank(warehouse) || warehouse.contains("*")
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库龄查询必须指定有效的租户、工厂和仓库");
        }
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouse);
        LocalDateTime observedAt = LocalDateTime.now();
        String sql = "SELECT COALESCE(NULLIF(TRIM(mat_unit), ''), '__MISSING_UNIT__') AS unit_code, "
                + "CASE WHEN in_time IS NULL THEN 'UNKNOWN' "
                + "WHEN in_time > ? THEN 'FUTURE' "
                + "WHEN in_time >= ? THEN 'D0_30' "
                + "WHEN in_time >= ? THEN 'D31_90' "
                + "WHEN in_time >= ? THEN 'D91_180' ELSE 'OVER_180' END AS age_bucket, "
                + "COUNT(*) AS stock_rows, SUM(mat_num) AS physical_num "
                + "FROM wms_live_stock WHERE tenant_id = ? AND factory_code = ? AND warehouse_code = ? "
                + "AND del_flag = '0' AND mat_num > 0 "
                + "GROUP BY 1, 2 ORDER BY 1, 2 LIMIT ?";
        List<Bucket> buckets = jdbc.query(sql, (rs, ignored) -> {
            BigDecimal quantity = rs.getBigDecimal("physical_num");
            long rows = rs.getLong("stock_rows");
            if (quantity == null || quantity.signum() < 0 || rows < 1) {
                throw new CheckedException("库龄统计结果无效");
            }
            return new Bucket(rs.getString("unit_code"), rs.getString("age_bucket"), rows,
                    quantity.toPlainString());
        }, observedAt, observedAt.minusDays(30), observedAt.minusDays(90), observedAt.minusDays(180),
                tenant, factory, warehouse, MAX_BUCKET_ROWS + 1);
        if (buckets.size() > MAX_BUCKET_ROWS) {
            throw new CheckedException("当前仓库库存单位分组过多，请先清理异常单位数据");
        }
        return new Aging(warehouse, observedAt, buckets, DEFINITION);
    }

    public record Aging(String warehouseCode, LocalDateTime observedAt, List<Bucket> buckets,
                        String definition) {
    }

    public record Bucket(String unitCode, String ageBucket, long stockRows, String physicalNum) {
    }
}
