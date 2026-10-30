package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.mapper.WmsWarehouseLocationMapMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按仓持久化 MDM 库位坐标，实时聚合库存占用；不推算无容量口径的使用率。 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseLocationMapService {
    private static final int MAX_PLACEMENTS = 500;
    private static final int MAX_LOCATIONS = 2000;
    private static final String DEFINITION = "仅统计当前仓库未删除且实物量大于零的库存行。"
            + "有货/空置以物理库位是否有正数库存行为准；未配置容量与单位换算，不能表示容量使用率。";
    private static final TypeReference<List<Placement>> PLACEMENTS = new TypeReference<>() {
    };

    private final WmsWarehouseLocationMapMapper maps;
    private final WmsWarehouseMenuMapper menus;
    private final RemoteWarehouseLocationService locations;
    private final RemoteWarehouseService warehouses;
    private final WmsLiveStockReadAccess readAccess;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MapView load(String warehouseCode) {
        Scope scope = scope(warehouseCode);
        Map<String, MdmWarehouseLocationEntity> master = master(scope);
        WmsWarehouseLocationMapMapper.Row saved = maps.find(scope.tenant(), scope.factory(), scope.warehouse());
        List<Placement> placed = saved == null ? List.of() : parse(saved.layoutJson());
        Map<String, Footprint> stock = stock(scope);
        Set<String> placedCodes = new HashSet<>();
        for (Placement placement : placed) {
            placedCodes.add(placement.locationCode());
        }
        List<LocationView> views = new ArrayList<>();
        int occupied = 0;
        for (MdmWarehouseLocationEntity location : master.values()) {
            if (!Integer.valueOf(3).equals(location.getLocationLevel())) {
                continue;
            }
            Footprint footprint = stock.get(location.getLocationCode());
            long rows = footprint == null ? 0 : footprint.rows();
            if (placedCodes.contains(location.getLocationCode()) && rows > 0) {
                occupied++;
            }
            views.add(new LocationView(location.getLocationCode(), location.getLocationName(),
                    Boolean.TRUE.equals(location.getStatus()), rows, footprint == null ? 0 : footprint.materials(),
                    footprint == null ? 0 : footprint.unknownAgeRows(),
                    footprint == null || footprint.oldest() == null ? null
                            : ChronoUnit.DAYS.between(footprint.oldest(), LocalDateTime.now())));
        }
        views.sort((left, right) -> left.locationCode().compareTo(right.locationCode()));
        long unplacedRows = 0;
        long unknownRows = 0;
        for (Map.Entry<String, Footprint> entry : stock.entrySet()) {
            if (!placedCodes.contains(entry.getKey())) {
                unplacedRows += entry.getValue().rows();
            }
            if (!master.containsKey(entry.getKey())) {
                unknownRows += entry.getValue().rows();
            }
        }
        long stalePlacements = placed.stream().filter(item -> !master.containsKey(item.locationCode())).count();
        return new MapView(scope.warehouse(), saved == null ? 0 : saved.version(), placed, views,
                occupied, unplacedRows, unknownRows, stalePlacements, LocalDateTime.now(), DEFINITION);
    }

    @Transactional(rollbackFor = Exception.class)
    public Saved save(SaveCommand command) {
        if (command == null || command.items() == null || command.expectedVersion() < 0) {
            throw new CheckedException("库位图保存内容不完整");
        }
        Scope scope = scope(command.warehouseCode());
        if (!permissions.hasPermission("wms_wmsWarehouseMenu_add")) {
            throw new CheckedException("无仓库配置权限");
        }
        menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
        requireActiveWarehouse(scope);
        Map<String, MdmWarehouseLocationEntity> master = master(scope);
        validate(command.items(), master);
        WmsWarehouseLocationMapMapper.Row previous = maps.find(scope.tenant(), scope.factory(), scope.warehouse());
        int version = previous == null ? 0 : previous.version();
        if (version != command.expectedVersion() || version == Integer.MAX_VALUE) {
            throw new CheckedException("库位图已被其他人更新，请刷新后重试");
        }
        var user = SecurityUtils.getUser();
        if (user == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("无法识别当前操作人");
        }
        String serialized;
        try {
            serialized = json.writeValueAsString(command.items());
        } catch (JsonProcessingException exception) {
            throw new CheckedException("库位图无法保存");
        }
        int updated = maps.upsert(scope.tenant(), scope.factory(), scope.warehouse(), serialized,
                version, user.getUsername());
        if (updated != 1) {
            throw new CheckedException("库位图已被其他人更新，请刷新后重试");
        }
        return new Saved(scope.warehouse(), version + 1);
    }

    private Scope scope(String code) {
        String warehouse = StrUtil.trim(code);
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory) || StrUtil.isBlank(warehouse)
                || warehouse.contains("*") || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库位图必须指定当前租户、工厂和仓库");
        }
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouse);
        return new Scope(tenant, factory, warehouse);
    }

    private Map<String, MdmWarehouseLocationEntity> master(Scope scope) {
        R<List<MdmWarehouseLocationEntity>> response = locations.getLayoutLocations(scope.warehouse());
        if (response == null || response.getCode() != 0 || response.getData() == null
                || response.getData().size() > MAX_LOCATIONS) {
            throw new CheckedException("库位主数据读取失败或数量过多");
        }
        Map<String, MdmWarehouseLocationEntity> result = new HashMap<>();
        for (MdmWarehouseLocationEntity row : response.getData()) {
            if (row == null || !Objects.equals(scope.tenant(), row.getTenantId())
                    || !Objects.equals(scope.factory(), row.getFactoryCode())
                    || !Objects.equals(scope.warehouse(), row.getWarehouseCode())
                    || StrUtil.isBlank(row.getLocationCode())
                    || result.putIfAbsent(row.getLocationCode(), row) != null) {
                throw new CheckedException("库位主数据范围不一致或编码重复");
            }
        }
        return result;
    }

    private void validate(List<Placement> placements, Map<String, MdmWarehouseLocationEntity> master) {
        if (placements.size() > MAX_PLACEMENTS) {
            throw new CheckedException("一次最多布置500个库位");
        }
        Set<String> seen = new HashSet<>();
        for (Placement item : placements) {
            MdmWarehouseLocationEntity location = item == null ? null : master.get(item.locationCode());
            if (item == null || StrUtil.isBlank(item.locationCode()) || !seen.add(item.locationCode())
                    || location == null || !Integer.valueOf(3).equals(location.getLocationLevel())
                    || !Boolean.TRUE.equals(location.getStatus())) {
                throw new CheckedException("库位图只能布置当前仓库已启用且不重复的物理库位");
            }
            if (item.x() < 0 || item.x() > 5000 || item.y() < 0 || item.y() > 5000
                    || item.width() < 80 || item.width() > 600 || item.height() < 60 || item.height() > 400) {
                throw new CheckedException("库位图坐标或尺寸不在允许范围内");
            }
        }
    }

    private List<Placement> parse(String value) {
        try {
            if (StrUtil.isBlank(value)) {
                throw new CheckedException("库位图历史配置为空");
            }
            List<Placement> placements = json.readValue(value, PLACEMENTS);
            if (placements == null || placements.size() > MAX_PLACEMENTS) {
                throw new CheckedException("库位图历史配置不完整");
            }
            return placements;
        } catch (JsonProcessingException exception) {
            throw new CheckedException("库位图历史配置无法读取");
        }
    }

    private Map<String, Footprint> stock(Scope scope) {
        List<Footprint> rows = jdbc.query("SELECT site_code, COUNT(*) AS stock_rows, "
                + "COUNT(DISTINCT mat_code) AS material_count, MIN(in_time) AS oldest_time, "
                + "SUM(CASE WHEN in_time IS NULL THEN 1 ELSE 0 END) AS unknown_age_rows "
                + "FROM wms_live_stock WHERE tenant_id = ? AND factory_code = ? AND warehouse_code = ? "
                + "AND del_flag = '0' AND mat_num > 0 GROUP BY site_code LIMIT 2001",
                (rs, ignored) -> new Footprint(StrUtil.nullToEmpty(rs.getString("site_code")),
                        rs.getLong("stock_rows"), rs.getLong("material_count"),
                        rs.getLong("unknown_age_rows"),
                        rs.getTimestamp("oldest_time") == null ? null : rs.getTimestamp("oldest_time").toLocalDateTime()),
                scope.tenant(), scope.factory(), scope.warehouse());
        if (rows.size() > MAX_LOCATIONS) {
            throw new CheckedException("库存库位分组过多，请先核对异常数据");
        }
        Map<String, Footprint> result = new HashMap<>();
        for (Footprint row : rows) {
            result.put(row.siteCode(), row);
        }
        return result;
    }

    private void requireActiveWarehouse(Scope scope) {
        R<List<MdmWarehouseEntity>> response = warehouses.getMenuConfigurationWarehouses();
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new CheckedException("仓库状态读取失败，禁止保存布局");
        }
        int matches = 0;
        for (MdmWarehouseEntity row : response.getData()) {
            if (row == null || !Objects.equals(scope.tenant(), row.getTenantId())
                    || !Objects.equals(scope.factory(), row.getFactoryCode())) {
                throw new CheckedException("仓库状态范围不一致");
            }
            if (scope.warehouse().equals(row.getWarehouseCode())) {
                matches++;
                if (!Boolean.TRUE.equals(row.getStatus())) {
                    throw new CheckedException("停用仓库不能保存库位图");
                }
            }
        }
        if (matches != 1) {
            throw new CheckedException("仓库不存在或无权配置");
        }
    }

    private record Scope(Long tenant, String factory, String warehouse) {
    }

    private record Footprint(String siteCode, long rows, long materials, long unknownAgeRows,
                             LocalDateTime oldest) {
    }

    public record Placement(String locationCode, int x, int y, int width, int height) {
    }

    public record SaveCommand(String warehouseCode, int expectedVersion, List<Placement> items) {
    }

    public record Saved(String warehouseCode, int version) {
    }

    public record LocationView(String locationCode, String locationName, boolean enabled,
                               long stockRows, long materialCount, long unknownAgeRows,
                               Long oldestStockAgeDays) {
    }

    public record MapView(String warehouseCode, int version, List<Placement> items,
                          List<LocationView> locations, int occupiedLocations, long unplacedStockRows,
                          long unknownLocationStockRows, long stalePlacements, LocalDateTime observedAt,
                          String definition) {
    }
}
