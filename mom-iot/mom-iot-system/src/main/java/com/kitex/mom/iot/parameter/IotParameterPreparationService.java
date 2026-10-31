package com.kitex.mom.iot.parameter;

import static com.kitex.mom.iot.parameter.ParameterCommandCodec.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.service.impl.IotDeviceDataAccess;
import com.kitex.mom.mes.api.feign.RemoteMesParameterContextService;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.*;

/** 选择项来自实际MES派工绑定及IoT档案；不把候选列表当作冻结参数校验。 */
@Service
@RequiredArgsConstructor
@PreAuthorize(IotParameterCommandService.READ)
public class IotParameterPreparationService {
    private final IotDeviceDataAccess access;
    private final RemoteMesParameterContextService mes;
    private final IotDeviceMapper devices;

    @Value("${mom.iot.parameter-simulator-enabled:false}")
    private boolean simulatorEnabled;

    public record DeviceChoice(
            String deviceCode,
            String deviceName,
            String firmwareVersion,
            boolean available,
            String blocker) {}

    public record Options(
            String schemaVersion,
            String factoryCode,
            String taskCode,
            String arrangeCode,
            String deviceCode,
            boolean simulatorEnabled,
            boolean truncated,
            String blocker,
            List<MesParameterDispatchContextDTO.ArrangementChoice> arrangements,
            List<DeviceChoice> devices) {}

    public Options query(String task, String arrange, String device) {
        String factory = access.factory("read");
        Long tenant = access.tenant();
        exact(task, 100);
        if (arrange != null) exact(arrange, 100);
        if (device != null) exact(device, 100);
        require(device == null || arrange != null, "请先选择具体派工");
        var reply =
                mes.preparationOptions(
                        new MesParameterDispatchContextDTO.OptionsQuery()
                                .setFactoryCode(factory)
                                .setTaskCode(task)
                                .setArrangeCode(arrange)
                                .setDeviceCode(device));
        require(reply != null && reply.isOk() && reply.getData() != null, "MES派工设备候选暂不可核验");
        var source = reply.getData();
        require(
                "MES_PARAMETER_PREPARATION@1".equals(source.schemaVersion())
                        && Objects.equals(factory, source.factoryCode())
                        && Objects.equals(task, source.taskCode())
                        && Objects.equals(arrange, source.arrangeCode())
                        && Objects.equals(device, source.deviceCode())
                        && source.blocker() != null
                        && source.arrangements() != null
                        && source.arrangements().size() <= 100
                        && source.deviceCodes() != null
                        && source.deviceCodes().size() <= 100,
                "MES候选范围或格式不一致");
        Set<String> seen = new HashSet<>();
        for (var a : source.arrangements()) {
            require(a != null, "派工候选缺失");
            exact(a.arrangeCode(), 100);
            exact(a.processCode(), 100);
            exact(a.stationCode(), 100);
            exact(a.routeNodeCode(), 100);
            require(
                    seen.add(a.arrangeCode())
                            && (arrange == null || arrange.equals(a.arrangeCode()))
                            && Set.of("RECEIVE", "PRODUCT", "PAUSE")
                                    .contains(String.valueOf(a.status())),
                    "派工候选不一致");
        }
        require(
                source.deviceCodes().isEmpty()
                        || arrange != null && source.arrangements().size() == 1,
                "设备候选缺少具体派工");
        seen.clear();
        for (String code : source.deviceCodes()) {
            exact(code, 100);
            require(seen.add(code) && (device == null || device.equals(code)), "设备候选不一致");
        }
        List<IotDeviceEntity> rows =
                source.deviceCodes().isEmpty()
                        ? List.of()
                        : devices.selectList(
                                new LambdaQueryWrapper<IotDeviceEntity>()
                                        .select(
                                                IotDeviceEntity::getId,
                                                IotDeviceEntity::getTenantId,
                                                IotDeviceEntity::getFactoryCode,
                                                IotDeviceEntity::getDeviceCode,
                                                IotDeviceEntity::getDeviceName,
                                                IotDeviceEntity::getFirmwareVersion)
                                        .eq(IotDeviceEntity::getTenantId, tenant)
                                        .eq(IotDeviceEntity::getFactoryCode, factory)
                                        .in(IotDeviceEntity::getDeviceCode, source.deviceCodes())
                                        .last("LIMIT 201"));
        require(rows.size() <= 200, "设备候选档案存在过多重复，请先核查编码");
        Map<String, List<IotDeviceEntity>> byCode = new HashMap<>();
        for (var d : rows) {
            require(
                    Objects.equals(tenant, d.getTenantId())
                            && Objects.equals(factory, d.getFactoryCode())
                            && seen.contains(d.getDeviceCode()),
                    "设备档案候选范围不一致");
            byCode.computeIfAbsent(d.getDeviceCode(), k -> new ArrayList<>()).add(d);
        }
        var choices =
                source.deviceCodes().stream()
                        .map(
                                code -> {
                                    var found = byCode.getOrDefault(code, List.of());
                                    if (found.size() != 1)
                                        return new DeviceChoice(
                                                code,
                                                "",
                                                "",
                                                false,
                                                found.isEmpty() ? "设备尚未在IoT登记" : "设备编码不唯一");
                                    var d = found.get(0);
                                    String fw = d.getFirmwareVersion();
                                    boolean available =
                                            d.getId() != null
                                                    && d.getId() > 0
                                                    && fw != null
                                                    && !fw.isBlank()
                                                    && fw.equals(fw.trim())
                                                    && fw.length() <= 100
                                                    && fw.chars()
                                                            .noneMatch(Character::isISOControl);
                                    return new DeviceChoice(
                                            code,
                                            d.getDeviceName() == null ? "" : d.getDeviceName(),
                                            available ? fw : "",
                                            available,
                                            available ? "" : "设备固件或档案身份未完善");
                                })
                        .toList();
        return new Options(
                "IOT_PARAMETER_PREPARATION@1",
                factory,
                task,
                arrange,
                device,
                simulatorEnabled,
                source.truncated(),
                source.blocker(),
                source.arrangements(),
                choices);
    }
}
