package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;
import com.kitex.mom.tpm.mapper.TpmDeviceMoldRelaMapper;
import com.kitex.mom.tpm.service.TpmDeviceMoldRelaService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TPM设备-模具关联表。组织归属由服务端明确写入，历史无归属关系不做读时认领。
 *
 * @author kitex
 * @date 2025-03-18 10:00:49
 */
@Service
@AllArgsConstructor
public class TpmDeviceMoldRelaServiceImpl extends ServiceImpl<TpmDeviceMoldRelaMapper, TpmDeviceMoldRelaEntity> implements TpmDeviceMoldRelaService {

    private final RemoteDeviceService remoteDeviceService;

    @Override
    public Page<TpmDeviceMoldRelaEntity> getTpmDeviceMoldRelaPage(Page toMpPage, TpmDeviceMoldRelaEntity tpmDeviceMoldRela) {
        require(tpmDeviceMoldRela != null, "请选择设备");
        String deviceCode = codes(List.of(Objects.toString(tpmDeviceMoldRela.getDeviceCode(), ""))).get(0);
        LambdaQueryWrapper<TpmDeviceMoldRelaEntity> queryWrapper = scopedQuery()
                .eq(TpmDeviceMoldRelaEntity::getDeviceCode, deviceCode)
                .orderByAsc(TpmDeviceMoldRelaEntity::getMoldCode);
        Page<TpmDeviceMoldRelaEntity> page = this.page(toMpPage, queryWrapper);
        if (CollUtil.isNotEmpty(page.getRecords())) {
            List<String> moldCodes = page.getRecords().stream().map(TpmDeviceMoldRelaEntity::getMoldCode).distinct().toList();
            Map<String, MdmDeviceEntity> mdmDeviceEntityMap = scopedDevices(moldCodes);
            page.getRecords().forEach(item -> {
                MdmDeviceEntity mdmDeviceEntity = mdmDeviceEntityMap.get(item.getMoldCode());
                if (mdmDeviceEntity != null) {
                    item.setMoldName(mdmDeviceEntity.getDeviceName());
                    item.setSpec(mdmDeviceEntity.getSpec());
                    item.setModel(mdmDeviceEntity.getModel());
                    item.setClassCode(mdmDeviceEntity.getClassCode());
                    item.setClassName(mdmDeviceEntity.getClassName());
                }
            });
        }
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveRela(TpmDeviceMoldRelaEntity tpmDeviceMoldRelaEntity) {
        require(tpmDeviceMoldRelaEntity != null && tpmDeviceMoldRelaEntity.getId() == null,
                "设备模具关系只允许新增，请勿携带既有关系ID改绑");
        require(StrUtil.isNotBlank(tpmDeviceMoldRelaEntity.getMoldCode()), "请选择模具");
        String deviceCode = codes(List.of(Objects.toString(tpmDeviceMoldRelaEntity.getDeviceCode(), ""))).get(0);
        List<String> codeList = codes(Arrays.asList(tpmDeviceMoldRelaEntity.getMoldCode().split(",", -1)));
        require(!codeList.contains(deviceCode), "设备不能与自身建立模具关系，请选择实际适配模具");
        Set<String> physicalCodes = new LinkedHashSet<>(codeList);
        physicalCodes.add(deviceCode);
        Map<String, MdmDeviceEntity> devices = scopedDevices(new ArrayList<>(physicalCodes));
        for (String code : physicalCodes) {
            MdmDeviceEntity physical = devices.get(code);
            require(physical != null && Boolean.TRUE.equals(physical.getStatus())
                            && !DeviceStateEnum.SCRAP.getCode().equals(physical.getDeviceState()),
                    "设备或模具不存在于当前组织、未启用或已报废：" + code);
        }
        Long tenant = TenantContextHolder.getTenantId();
        String factory = currentFactory();
        Set<String> existing = list(scopedQuery().eq(TpmDeviceMoldRelaEntity::getDeviceCode, deviceCode)
                .in(TpmDeviceMoldRelaEntity::getMoldCode, codeList)).stream()
                .map(TpmDeviceMoldRelaEntity::getMoldCode).collect(Collectors.toSet());
        List<TpmDeviceMoldRelaEntity> results = new ArrayList<>();
        for (String code : codeList) {
            if (existing.contains(code)) continue;
            TpmDeviceMoldRelaEntity entity = new TpmDeviceMoldRelaEntity();
            entity.setTenantId(tenant);
            entity.setFactoryCode(factory);
            entity.setDeviceCode(deviceCode);
            entity.setMoldCode(code);
            results.add(entity);
        }
        // 请求内去重、已有关系幂等；并发重复最终由组织复合唯一约束阻止，整个批次回滚。
        return results.isEmpty() || this.saveBatch(results);
    }

    @Override
    public List<TpmDeviceMoldRelaEntity> getListByDeviceCodes(List<String> deviceCodes) {
        LambdaQueryWrapper<TpmDeviceMoldRelaEntity> query = scopedQuery();
        List<String> normalized = codes(deviceCodes);
        if (normalized.isEmpty()) return List.of();
        return list(query.in(TpmDeviceMoldRelaEntity::getDeviceCode, normalized)
                .orderByAsc(TpmDeviceMoldRelaEntity::getDeviceCode)
                .orderByAsc(TpmDeviceMoldRelaEntity::getMoldCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeScoped(List<Long> ids) {
        LambdaQueryWrapper<TpmDeviceMoldRelaEntity> query = scopedQuery();
        require(ids != null && !ids.isEmpty() && ids.size() <= 2000
                && ids.stream().allMatch(id -> id != null && id > 0), "关系ID集合不合法");
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        List<TpmDeviceMoldRelaEntity> rows = list(query.in(TpmDeviceMoldRelaEntity::getId, distinctIds)
                .last("FOR UPDATE"));
        require(rows.size() == distinctIds.size(), "部分设备模具关系不存在或不属于当前组织，整批删除已拒绝");
        return remove(scopedQuery().in(TpmDeviceMoldRelaEntity::getId, distinctIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeByMoldCodes(List<String> moldCodes) {
        LambdaQueryWrapper<TpmDeviceMoldRelaEntity> query = scopedQuery();
        List<String> normalized = codes(moldCodes);
        if (normalized.isEmpty()) return true;
        return remove(query.in(TpmDeviceMoldRelaEntity::getMoldCode, normalized));
    }

    private LambdaQueryWrapper<TpmDeviceMoldRelaEntity> scopedQuery() {
        String factory = currentFactory();
        return new LambdaQueryWrapper<TpmDeviceMoldRelaEntity>()
                .eq(TpmDeviceMoldRelaEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(TpmDeviceMoldRelaEntity::getFactoryCode, factory);
    }

    private String currentFactory() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
                "设备模具关系操作需要明确当前租户和工厂");
        return factory;
    }

    private List<String> codes(List<String> values) {
        require(values != null && values.size() <= 2000 && values.stream().allMatch(StrUtil::isNotBlank),
                "设备或模具编码不可含空值且不能超过2000项");
        return values.stream().map(String::trim).distinct().toList();
    }

    /**
     * 调用失败、空响应及组织不匹配均属于读取错误，不能伪装成没有可选模具。
     */
    private Map<String, MdmDeviceEntity> scopedDevices(List<String> physicalCodes) {
        String factory = currentFactory();
        Long tenant = TenantContextHolder.getTenantId();
        R<List<MdmDeviceEntity>> response = remoteDeviceService.scopedListByCodes(physicalCodes);
        require(response != null && response.getCode() == 0 && response.getData() != null,
                "读取当前组织实物设备失败，请检查主数据服务");
        Map<String, MdmDeviceEntity> result = new LinkedHashMap<>();
        for (MdmDeviceEntity device : response.getData()) {
            require(device != null && Objects.equals(device.getTenantId(), tenant)
                            && Objects.equals(device.getFactoryCode(), factory) && physicalCodes.contains(device.getDeviceCode()),
                    "主数据返回了不属于当前租户/工厂的实物设备");
            require(result.putIfAbsent(device.getDeviceCode(), device) == null,
                    "当前组织存在重复实物设备编码，请先修复主数据");
        }
        return result;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

}
