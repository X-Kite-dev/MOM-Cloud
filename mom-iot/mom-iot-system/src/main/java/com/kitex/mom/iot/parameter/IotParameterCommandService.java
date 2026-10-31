package com.kitex.mom.iot.parameter;

import static com.kitex.mom.iot.parameter.ParameterCommandCodec.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.service.impl.IotDeviceDataAccess;
import com.kitex.mom.mes.api.feign.RemoteMesParameterContextService;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@PreAuthorize(IotParameterCommandService.READ)
public class IotParameterCommandService {
    public static final String READ =
            "@pms.hasPermission('mesTask:show') and @pms.hasPermission('mesProduceTask:show') and"
                    + " @pms.hasPermission('iot_device_view')";
    public static final String WRITE = READ + " and @pms.hasPermission('iot_parameter_dispatch')";
    private final IotDeviceDataAccess access;
    private final IotParameterCommandMapper mapper;
    private final RemoteMesParameterContextService mes;
    private final ObjectMapper json;

    @Value("${mom.iot.parameter-simulator-enabled:false}")
    private boolean simulatorEnabled;

    private ParameterCommandCodec codec() {
        return new ParameterCommandCodec(json);
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize(WRITE)
    public IotParameterCommand prepare(IotParameterCommandDTO.Prepare p) {
        enabled();
        String factory = scope("write");
        require(p != null && Objects.equals(factory, p.getFactoryCode()), "命令与当前工厂不一致");
        requestId(p.getRequestId());
        exact(p.getTaskCode(), 100);
        exact(p.getArrangeCode(), 100);
        exact(p.getDeviceCode(), 100);
        exact(p.getFirmwareVersion(), 100);
        require(
                "SIMULATION".equals(p.getMode())
                        && p.getScenario() != null
                        && Set.of("MATCH", "MISMATCH", "REJECT", "TIMEOUT_AFTER_EXECUTION")
                                .contains(p.getScenario()),
                "本版本仅支持明确标记的模拟场景");
        require(
                p.getReason() != null && !p.getReason().isBlank() && p.getReason().length() <= 500,
                "须提供500字以内下发依据");
        String raw = codec().encode(p);
        var old = mapper.find(access.tenant(), factory, p.getRequestId());
        if (old != null) return replay(old, raw);
        var device = access.device(null, p.getDeviceCode(), "write");
        checkDevice(device, p);
        old = mapper.lock(access.tenant(), factory, p.getRequestId());
        if (old != null) return replay(old, raw);
        require(
                mapper.active(access.tenant(), factory, p.getDeviceCode(), p.getRequestId()) == 0,
                "该设备有未决命令，请按原号核对，不能换号下发");
        var context = source(p);
        String contextJson = codec().encode(context);
        LocalDateTime now = LocalDateTime.now();
        var command =
                new IotParameterCommand()
                        .setTenantId(access.tenant())
                        .setFactoryCode(factory)
                        .setRequestId(p.getRequestId())
                        .setActorId(actor())
                        .setRequestJson(raw)
                        .setRequestHash(hash(raw))
                        .setContextJson(contextJson)
                        .setContextHash(hash(contextJson))
                        .setTaskCode(p.getTaskCode())
                        .setArrangeCode(p.getArrangeCode())
                        .setDeviceId(device.getId())
                        .setDeviceCode(p.getDeviceCode())
                        .setFirmwareVersion(p.getFirmwareVersion())
                        .setDeviceSnapshot(deviceSnapshot(device))
                        .setMode("SIMULATION")
                        .setScenario(p.getScenario())
                        .setState("PREPARED")
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
        require(mapper.insertCommand(command) == 1, "命令原件保存失败");
        return command;
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize(WRITE)
    public IotParameterCommand request(String id) {
        var command = lockOwned(id);
        if (!"PREPARED".equals(command.getState())) return command;
        verifyDispatchSource(command);
        require(
                mapper.active(
                                command.getTenantId(),
                                command.getFactoryCode(),
                                command.getDeviceCode(),
                                id)
                        == 0,
                "设备仍有其他未决命令");
        command.setState("REQUESTED").setRequestedAt(LocalDateTime.now());
        save(command);
        return command;
    }

    public IotParameterCommandDTO.Detail detail(String id) {
        var command = find(id, "read");
        verifyOriginal(command);
        var journal = mapper.journal(command.getTenantId(), command.getFactoryCode(), id);
        return new IotParameterCommandDTO.Detail(
                command,
                mapper.receipts(command.getTenantId(), command.getFactoryCode(), id),
                journal == null ? null : journal.getOutcome(),
                journal == null ? null : journal.getExecutionCount());
    }

    public Map<String, Object> list(String task, String before) {
        String factory = scope("read");
        exact(task, 100);
        if (before != null) requestId(before);
        var rows = mapper.list(access.tenant(), factory, task, before);
        boolean more = rows.size() > 20;
        if (more) rows = rows.subList(0, 20);
        return Map.of(
                "records",
                rows,
                "hasMore",
                more,
                "nextBefore",
                rows.isEmpty() ? "" : rows.get(rows.size() - 1).getRequestId(),
                "mode",
                "SIMULATION",
                "simulatorEnabled",
                simulatorEnabled);
    }

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize(WRITE)
    public void markUnknown(String id) {
        var command = lockOwned(id);
        if (Set.of("REQUESTED", "RECEIVED", "EXECUTED").contains(command.getState())) {
            command.setState("UNKNOWN");
            save(command);
        }
    }

    /** 调用方必须处于设备→命令锁顺序的本地事务；不是公开设备回执接口。 */
    IotParameterCommand lockOwned(String id) {
        enabled();
        var before = find(id, "write");
        require(Objects.equals(before.getActorId(), actor()), "须由原命令操作者核对原号");
        var device = access.device(before.getDeviceId(), before.getDeviceCode(), "write");
        require(
                Objects.equals(device.getTenantId(), before.getTenantId())
                        && Objects.equals(device.getFactoryCode(), before.getFactoryCode())
                        && Objects.equals(device.getDeviceCode(), before.getDeviceCode()),
                "命令设备归属已变化");
        var command = mapper.lock(before.getTenantId(), before.getFactoryCode(), id);
        require(command != null, "原命令不存在");
        verifyOriginal(command);
        return command;
    }

    void verifyDispatchSource(IotParameterCommand command) {
        var p = codec().decode(command.getRequestJson(), IotParameterCommandDTO.Prepare.class);
        var device = access.device(command.getDeviceId(), command.getDeviceCode(), "write");
        checkDevice(device, p);
        require(
                Objects.equals(command.getDeviceSnapshot(), deviceSnapshot(device)),
                "设备身份、产品或固件已变更");
        require(
                Objects.equals(command.getContextHash(), hash(codec().encode(source(p)))),
                "MES派工/工位/冻结工程参数已变化，请核对原命令");
    }

    void save(IotParameterCommand command) {
        command.setUpdatedAt(LocalDateTime.now());
        require(mapper.updateState(command) == 1, "命令状态保存失败");
    }

    MesParameterDispatchContextDTO.Evidence context(IotParameterCommand command) {
        return codec().decode(
                        command.getContextJson(), MesParameterDispatchContextDTO.Evidence.class);
    }

    private IotParameterCommand find(String id, String permission) {
        requestId(id);
        String factory = scope(permission);
        var command = mapper.find(access.tenant(), factory, id);
        require(command != null, "原命令不存在或不属于当前租户/工厂");
        return command;
    }

    private String scope(String permission) {
        Long tenant = access.tenant();
        Long user = actor();
        require(
                tenant != null
                        && tenant > 0
                        && user > 0
                        && Objects.equals(SecurityUtils.getUser().getTenantId(), tenant),
                "请以实际租户身份登录");
        String factory = access.factory(permission);
        exact(factory, 100);
        return factory;
    }

    private Long actor() {
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && user.getId() > 0, "参数命令须实际人员登录");
        return user.getId();
    }

    private void enabled() {
        require(simulatorEnabled, "参数模拟通道未启用");
    }

    private IotParameterCommand replay(IotParameterCommand command, String raw) {
        verifyOriginal(command);
        require(
                Objects.equals(command.getRequestJson(), raw)
                        && Objects.equals(command.getRequestHash(), hash(raw))
                        && Objects.equals(command.getActorId(), actor()),
                "原请求号不能替换参数范围、原因、场景或操作者");
        return command;
    }

    private void verifyOriginal(IotParameterCommand c) {
        require(
                "SIMULATION".equals(c.getMode())
                        && Objects.equals(c.getRequestHash(), hash(c.getRequestJson()))
                        && Objects.equals(c.getContextHash(), hash(c.getContextJson())),
                "原命令或冻结依据指纹不一致");
    }

    private MesParameterDispatchContextDTO.Evidence source(IotParameterCommandDTO.Prepare p) {
        var r =
                mes.context(
                        new MesParameterDispatchContextDTO.Query()
                                .setFactoryCode(p.getFactoryCode())
                                .setTaskCode(p.getTaskCode())
                                .setArrangeCode(p.getArrangeCode())
                                .setDeviceCode(p.getDeviceCode()));
        require(r != null && r.isOk() && r.getData() != null, "MES未确认可下发的原冻结依据");
        codec().verifyContext(r.getData(), p);
        return r.getData();
    }

    private void checkDevice(IotDeviceEntity d, IotParameterCommandDTO.Prepare p) {
        require(
                d != null
                        && d.getId() != null
                        && Objects.equals(d.getTenantId(), access.tenant())
                        && Objects.equals(d.getFactoryCode(), p.getFactoryCode())
                        && Objects.equals(d.getDeviceCode(), p.getDeviceCode())
                        && Objects.equals(d.getFirmwareVersion(), p.getFirmwareVersion()),
                "实际IoT设备或固件版本与确认值不一致");
    }

    private String deviceSnapshot(IotDeviceEntity d) {
        return codec().encode(
                        Arrays.asList(
                                d.getId(),
                                d.getTenantId(),
                                d.getFactoryCode(),
                                d.getDeviceCode(),
                                d.getProductId(),
                                d.getProductCode(),
                                d.getFirmwareVersion()));
    }
}
