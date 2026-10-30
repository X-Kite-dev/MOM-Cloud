package com.kitex.mom.mdm.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.entity.MdmStationSettingEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.mapper.MdmProcessRouteMapper;
import com.kitex.mom.mdm.mapper.MdmStationSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 节点工程设备候选：节点身份与主工序能力分开解析，不根据瞬时运行状态过滤。
 */
@Component
@RequiredArgsConstructor
public class MdmRouteNodeDeviceSupport {

    private final MdmRouteNodeResolver nodeResolver;
    private final MdmProcessRouteMapper routeMapper;
    private final MdmStationSettingMapper stationMapper;
    private final MdmScopedDeviceQuerySupport deviceQuery;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<String> getDeviceList(Long id) {
        MdmProcessRouteWorkingEntity node = nodeResolver.byId(id, false);
        // Resolver已核实父路线唯一归属；同一只读快照内读取其产线约束。
        MdmProcessRouteEntity route = routeMapper.selectOne(new LambdaQueryWrapper<MdmProcessRouteEntity>()
                .eq(MdmProcessRouteEntity::getTenantId, node.getTenantId())
                .eq(MdmProcessRouteEntity::getFactoryCode, node.getFactoryCode())
                .eq(MdmProcessRouteEntity::getRouteCode, node.getRouteCode()));
        List<MdmStationSettingEntity> stations = stationMapper.selectList(new LambdaQueryWrapper<MdmStationSettingEntity>()
                .eq(MdmStationSettingEntity::getTenantId, node.getTenantId())
                .eq(MdmStationSettingEntity::getFactoryCode, node.getFactoryCode())
                .eq(MdmStationSettingEntity::getProcedureCode, node.getProcessCode())
                .eq(MdmStationSettingEntity::getStatus, true)
                .eq(StrUtil.isNotBlank(node.getWorkshopCode()), MdmStationSettingEntity::getWorkshopCode, node.getWorkshopCode())
                .eq(StrUtil.isNotBlank(route.getProductLineCode()), MdmStationSettingEntity::getLineCode, route.getProductLineCode()));
        Set<String> codes = new LinkedHashSet<>();
        for (MdmStationSettingEntity station : stations) {
            if (station.getDeviceCodes() == null) continue;
            for (String code : station.getDeviceCodes()) {
                if (StrUtil.isNotBlank(code)) codes.add(code);
            }
        }
        if (codes.isEmpty()) return List.of();
        return deviceQuery.listByCodes(new ArrayList<>(codes)).stream()
                .filter(device -> Boolean.TRUE.equals(device.getStatus()))
                .filter(device -> !DeviceStateEnum.SCRAP.getCode().equals(device.getDeviceState()))
                .map(MdmDeviceEntity::getDeviceCode).toList();
    }
}
