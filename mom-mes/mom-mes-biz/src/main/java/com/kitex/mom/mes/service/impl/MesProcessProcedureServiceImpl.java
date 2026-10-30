package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.dto.MesProcessProcedureMoldDTO;
import com.kitex.mom.mes.dto.MesRouteMoldConfigurationDTO;
import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;
import com.kitex.mom.mes.mapper.MesProcessProcedureMoldMapper;
import com.kitex.mom.mes.mapper.MesRouteMoldScopeMapper;
import com.kitex.mom.mes.service.MesProcessProcedureService;
import com.kitex.mom.tpm.api.feign.RemoteTpmDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 路线节点模具的工程维护。
 *
 * <p>绑定与主工序能力分离：同主工序多次出现也有各自清单。维护采用实际父路线锁、
 * 原图版本和差异更新；已有失效绑定可明确移除，不在候选读取失败时默默清空。
 * 这里只校验工程适配，现场设备/工装运行状态仍由生产执行门负责。</p>
 */
@Service
@RequiredArgsConstructor
public class MesProcessProcedureServiceImpl extends ServiceImpl<MesProcessProcedureMoldMapper, MesProcessProcedureMoldEntity>
        implements MesProcessProcedureService {

    private final RemoteProcessRouteService processRouteService;
    private final RemoteTpmDeviceService tpmDeviceService;
    private final RemoteDeviceService deviceService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesRouteMoldScopeMapper scopeMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesRouteMoldConfigurationDTO configuration(Long id) {
        MesRouteMoldConfigurationDTO node = context(id, null, null, null, false);
        node.setMoldList(bindings(node));
        return node;
    }

    /**
     * 保持原按ID接口形状，但读取域已改为精确节点。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MesProcessProcedureMoldEntity> getMold(Long id) {
        return bindings(context(id, null, null, null, false));
    }

    /**
     * 旧编码请求只能在同路线主工序恰好一个节点时桥接。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MesProcessProcedureMoldEntity> getMoldByCode(String routeCode, String processCode) {
        require(StringUtils.hasText(routeCode) && StringUtils.hasText(processCode), "请选择真实路线和主工序");
        return bindings(context(null, routeCode, null, processCode, false));
    }

    /**
     * 新选路线用稳定节点读取；不能再回退到同名主工序。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MesProcessProcedureMoldEntity> getMoldByNode(String routeCode, String routeNodeCode) {
        require(StringUtils.hasText(routeCode) && StringUtils.hasText(routeNodeCode), "请选择真实路线节点");
        return bindings(context(null, routeCode, routeNodeCode, null, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Boolean saveMold(MesProcessProcedureMoldDTO command) {
        require(command != null && command.getId() != null && command.getMoldList() != null,
                "节点与模具清单不能为空；清空请明确提交空列表");
        require(command.getExpectedGraphRevision() != null, "缺少原配置版本，请刷新新版维护页面");
        require(command.getMoldList().size() <= 500, "单节点最多绑定500个模具");
        MesRouteMoldConfigurationDTO node = context(command.getId(), null, null, null, true);
        require(Objects.equals(command.getExpectedGraphRevision(), node.getExpectedGraphRevision()),
                "路线或模具配置已变化，请重新读取核对，不能覆盖新配置");
        Map<String, MesProcessProcedureMoldEntity> current = new LinkedHashMap<>();
        for (MesProcessProcedureMoldEntity row : bindings(node)) {
            require(current.putIfAbsent(row.getMoldCode(), row) == null, "当前节点模具绑定重复，请先核对原数据");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (MesProcessProcedureMoldEntity row : command.getMoldList()) {
            require(row != null && StringUtils.hasText(row.getMoldCode())
                            && row.getMoldCode().equals(row.getMoldCode().trim()) && requested.add(row.getMoldCode()),
                    "模具编码不能为空、重复或包含首尾空格");
        }
        Set<String> added = new LinkedHashSet<>(requested);
        added.removeAll(current.keySet());
        // 删除/保留原绑定不依赖远程候选服务；只有新增项必须重新核实际工程候选。
        Map<String, MdmDeviceEntity> eligible = new LinkedHashMap<>();
        if (!added.isEmpty()) {
            for (MdmDeviceEntity row : candidates(node)) eligible.put(row.getDeviceCode(), row);
            require(eligible.keySet().containsAll(added), "部分新增模具已不属于当前节点有效候选，请刷新后选择");
        }
        for (MesProcessProcedureMoldEntity row : current.values()) {
            if (!requested.contains(row.getMoldCode())) {
                require(baseMapper.delete(moldScope(node).eq(MesProcessProcedureMoldEntity::getId, row.getId())) == 1,
                        "原模具绑定移除失败，整笔修改已回滚");
            }
        }
        for (String code : added) {
            MdmDeviceEntity actual = eligible.get(code);
            MesProcessProcedureMoldEntity row = new MesProcessProcedureMoldEntity();
            row.setTenantId(node.getTenantId());
            row.setFactoryCode(node.getFactoryCode());
            row.setRouteCode(node.getRouteCode());
            row.setRouteNodeCode(node.getRouteNodeCode());
            row.setProcessCode(node.getProcessCode());
            row.setMoldCode(actual.getDeviceCode());
            row.setMoldName(actual.getDeviceName());
            row.setDelFlag("0");
            require(baseMapper.insert(row) == 1, "模具绑定保存失败，整笔修改已回滚");
        }
        return Boolean.TRUE;
    }

    /**
     * 返回可新增工程绑定的候选；真实旧绑定另由configuration返回，不能以候选代替绑定。
     */
    @Override
    public List<MdmDeviceEntity> queryMoldList(Long id) {
        return candidates(context(id, null, null, null, false));
    }

    private List<MdmDeviceEntity> candidates(MesRouteMoldConfigurationDTO node) {
        R<List<String>> devices = processRouteService.getDeviceList(node.getId());
        require(devices != null && devices.isOk() && devices.getData() != null,
                "无法核验节点的真实设备能力，请稍后重新读取");
        require(devices.getData().size() <= 2000
                        && devices.getData().stream().allMatch(StringUtils::hasText),
                "节点设备候选数量或编码无效");
        Set<String> deviceCodes = new LinkedHashSet<>(devices.getData());
        if (deviceCodes.isEmpty()) return List.of();
        R<List<TpmDeviceMoldRelaEntity>> response = tpmDeviceService.getListByDeviceCodes(new ArrayList<>(deviceCodes));
        require(response != null && response.isOk() && response.getData() != null,
                "无法读取同工厂的设备模具适配关系");
        Set<String> moldCodes = new LinkedHashSet<>();
        Set<String> pairs = new LinkedHashSet<>();
        for (TpmDeviceMoldRelaEntity row : response.getData()) {
            require(row != null && Objects.equals(node.getTenantId(), row.getTenantId())
                            && Objects.equals(node.getFactoryCode(), row.getFactoryCode())
                            && deviceCodes.contains(row.getDeviceCode()) && StringUtils.hasText(row.getMoldCode()),
                    "设备模具关系未明确归属当前组织，请先完成关系升级");
            require(pairs.add(row.getDeviceCode() + "\u0000" + row.getMoldCode()), "设备模具适配关系重复，请核对原数据");
            moldCodes.add(row.getMoldCode());
        }
        require(moldCodes.size() <= 2000, "节点模具候选超过2000，请收敛设备适配范围");
        if (moldCodes.isEmpty()) return List.of();
        R<List<MdmDeviceEntity>> molds = deviceService.scopedListByCodes(new ArrayList<>(moldCodes));
        require(molds != null && molds.isOk() && molds.getData() != null, "无法读取同工厂真实模具主数据");
        Set<String> seen = new LinkedHashSet<>();
        List<MdmDeviceEntity> result = new ArrayList<>();
        for (MdmDeviceEntity row : molds.getData()) {
            require(row != null && Objects.equals(node.getTenantId(), row.getTenantId())
                            && Objects.equals(node.getFactoryCode(), row.getFactoryCode())
                            && moldCodes.contains(row.getDeviceCode()) && seen.add(row.getDeviceCode())
                            && "0".equals(row.getDelFlag()),
                    "模具主数据存在跨组织、重复编码或无效身份");
            if (Boolean.TRUE.equals(row.getStatus()) && !"3".equals(row.getDeviceState())) result.add(row);
        }
        return result;
    }

    /**
     * 数据库函数复核实际父/子归属；写入口同一事务先锁父图，不等待反向子锁。
     */
    private MesRouteMoldConfigurationDTO context(Long id, String route, String nodeCode, String process, boolean lock) {
        String factory = factoryAccess.require(lock ? "write" : "read");
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0, "节点模具维护需要真实租户");
        if (id != null) require(id > 0, "请选择已保存的路线节点");
        String raw = scopeMapper.context(tenant, factory, id, route, nodeCode, process, lock);
        MesRouteMoldConfigurationDTO node;
        try {
            node = new ObjectMapper().readValue(raw, MesRouteMoldConfigurationDTO.class);
        } catch (Exception exception) {
            throw new CheckedException("无法读取节点维护上下文，请确认283升级及实际组织");
        }
        require(node != null && node.getId() != null && node.getRouteId() != null
                        && Objects.equals(node.getTenantId(), tenant) && Objects.equals(node.getFactoryCode(), factory)
                        && Objects.equals(node.getRouteNodeCode(), "RN-" + node.getId())
                        && node.getExpectedGraphRevision() != null && node.getExpectedGraphRevision() >= 0,
                "路线节点未唯一归属当前实际组织");
        require(id == null || Objects.equals(id, node.getId()), "路线节点身份不一致");
        return node;
    }

    private List<MesProcessProcedureMoldEntity> bindings(MesRouteMoldConfigurationDTO node) {
        return baseMapper.selectList(moldScope(node).orderByAsc(MesProcessProcedureMoldEntity::getMoldCode)
                .orderByAsc(MesProcessProcedureMoldEntity::getId));
    }

    private LambdaQueryWrapper<MesProcessProcedureMoldEntity> moldScope(MesRouteMoldConfigurationDTO node) {
        return new LambdaQueryWrapper<MesProcessProcedureMoldEntity>()
                .eq(MesProcessProcedureMoldEntity::getTenantId, node.getTenantId())
                .eq(MesProcessProcedureMoldEntity::getFactoryCode, node.getFactoryCode())
                .eq(MesProcessProcedureMoldEntity::getRouteCode, node.getRouteCode())
                .eq(MesProcessProcedureMoldEntity::getRouteNodeCode, node.getRouteNodeCode());
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
