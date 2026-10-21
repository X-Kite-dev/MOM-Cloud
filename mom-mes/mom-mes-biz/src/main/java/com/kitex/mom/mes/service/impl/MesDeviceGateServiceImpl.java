package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mes.dto.MesDeviceGateDecision;
import com.kitex.mom.mes.dto.MesDeviceUnavailableWindowDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.MesDeviceGateStageEnum;
import com.kitex.mom.mes.service.MesDeviceGateRecordService;
import com.kitex.mom.mes.service.MesDeviceGateService;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;
import com.kitex.mom.tpm.feign.RemoteTpmDeviceAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MesDeviceGateServiceImpl implements MesDeviceGateService {

    private static final Set<String> STARTABLE_STATES = Set.of(
            DeviceStateEnum.IDLE.getCode(), DeviceStateEnum.PAUSE.getCode(), DeviceStateEnum.RUNNING.getCode());

    private final RemoteDeviceService remoteDeviceService;
    private final RemoteTpmDeviceAvailabilityService availabilityService;
    private final MesDeviceGateRecordService recordService;

    /** 仅当前线程的纯读预览范围；退出必恢复，正式执行仍写核验账。 */
    private final ThreadLocal<Boolean> observationOnly = new ThreadLocal<>();

    @Override
    public MesDeviceGateDecision previewStart(MesTaskArrangeEntity arrange) {
        Boolean previous = observationOnly.get();
        observationOnly.set(true);
        try {
            return check(MesDeviceGateStageEnum.START, arrange, null, arrange.getActualWorksiteCode());
        } finally {
            if (previous == null) observationOnly.remove();
            else observationOnly.set(previous);
        }
    }

    @Override
    public MesDeviceGateDecision check(MesDeviceGateStageEnum stage, MesTaskArrangeEntity arrange,
                                       String sourceDeviceCode, String worksiteCode) {
        String plannedDeviceCode = arrange == null ? null : arrange.getPlannedDeviceCode();
        String confirmedWorksite = arrange == null ? null : arrange.getActualWorksiteCode();
        String actualWorksite = StrUtil.blankToDefault(worksiteCode,
                StrUtil.blankToDefault(confirmedWorksite,
                        arrange == null ? null : firstCode(arrange.getWorksiteCodes())));
        MesDeviceGateDecision decision = baseDecision(stage, arrange, plannedDeviceCode, actualWorksite);
        if (arrange != null && StrUtil.isNotBlank(worksiteCode)
                && ((StrUtil.isNotBlank(confirmedWorksite) && !confirmedWorksite.equals(worksiteCode.trim()))
                || !containsCode(arrange.getWorksiteCodes(), worksiteCode))) {
            return finish(decision.setAllowed(false).setMessage("来源工位[" + worksiteCode
                    + "]与派工任务确认工位不一致"), arrange);
        }
        if (StrUtil.isBlank(plannedDeviceCode)) {
            if (StrUtil.isNotBlank(sourceDeviceCode)) {
                return finish(decision.setAllowed(false)
                        .setMessage("派工任务未锁定设备，禁止接受来源设备[" + sourceDeviceCode + "]"), arrange);
            }
            return finish(decision.setAllowed(true).setMessage("当前工序无需设备资源，设备运行门放行"), arrange);
        }
        if (StrUtil.isNotBlank(sourceDeviceCode) && !plannedDeviceCode.equals(sourceDeviceCode.trim())) {
            return finish(decision.setAllowed(false).setMessage("来源设备[" + sourceDeviceCode
                    + "]与APS锁定设备[" + plannedDeviceCode + "]不一致"), arrange);
        }
        return evaluate(decision, arrange, arrange.getFactoryCode());
    }

    @Override
    public MesDeviceGateDecision checkDeviceStart(String deviceCode, String worksiteCode, String factoryCode) {
        MesDeviceGateDecision decision = baseDecision(MesDeviceGateStageEnum.DEVICE_START, null,
                StrUtil.trim(deviceCode), StrUtil.trim(worksiteCode));
        if (StrUtil.isBlank(deviceCode) || StrUtil.isBlank(worksiteCode)) {
            return finish(decision.setAllowed(false).setMessage("设备开机必须提供设备编码和工位编码"), null, factoryCode);
        }
        return evaluate(decision, null, factoryCode);
    }

    private MesDeviceGateDecision evaluate(MesDeviceGateDecision decision, MesTaskArrangeEntity arrange,
                                           String factoryCode) {
        try {
            MdmDeviceEntity query = new MdmDeviceEntity();
            query.setDeviceCode(decision.getDeviceCode());
            R<MdmDeviceEntity> response = remoteDeviceService.getDetails(query);
            if (response == null || response.getCode() != 0 || response.getData() == null) {
                return finish(decision.setAllowed(false).setMessage("MDM中不存在设备["
                        + decision.getDeviceCode() + "]或设备服务无有效响应"), arrange, factoryCode);
            }
            MdmDeviceEntity device = response.getData();
            if (Boolean.TRUE.equals(observationOnly.get()) && (arrange == null
                    || !java.util.Objects.equals(arrange.getTenantId(), device.getTenantId())
                    || !java.util.Objects.equals(factoryCode, device.getFactoryCode())
                    || !java.util.Objects.equals(decision.getDeviceCode(), device.getDeviceCode()))) {
                return finish(decision.setAllowed(false).setMessage("设备来源租户/工厂/编码无法匹配，不能形成准备就绪证据"), arrange, factoryCode);
            }
            decision.setDeviceName(device.getDeviceName())
                    .setDeviceState(device.getDeviceState())
                    .setDeviceStateName(StrUtil.blankToDefault(
                            DeviceStateEnum.getNameByCode(device.getDeviceState()), "未知状态"));
            if (!Boolean.TRUE.equals(device.getStatus())) {
                return finish(decision.setAllowed(false).setMessage("设备已停用，禁止生产执行"), arrange, factoryCode);
            }
            if (!"1".equals(device.getDeviceUsable())) {
                return finish(decision.setAllowed(false).setMessage("MDM设备资格不可用或未知，禁止开机、接收、开工和报工；须先完成受控恢复"), arrange, factoryCode);
            }
            if (DeviceStateEnum.SCRAP.getCode().equals(device.getDeviceState())) {
                return finish(decision.setAllowed(false).setMessage("设备已报废，禁止生产执行"), arrange, factoryCode);
            }
            boolean startStage = MesDeviceGateStageEnum.DEVICE_START.name().equals(decision.getStage());
            if (startStage && !STARTABLE_STATES.contains(device.getDeviceState())) {
                return finish(decision.setAllowed(false).setMessage("设备当前状态["
                        + decision.getDeviceStateName() + "]不允许开机"), arrange, factoryCode);
            }
            if (!startStage && !DeviceStateEnum.RUNNING.getCode().equals(device.getDeviceState())) {
                return finish(decision.setAllowed(false).setMessage("APS锁定设备[" + decision.getDeviceCode()
                        + "]当前为[" + decision.getDeviceStateName() + "]，请完成点检开机后再执行"), arrange, factoryCode);
            }

            List<MesDeviceUnavailableWindowDTO> blockers = activeBlockers(decision.getDeviceCode(), startStage,
                    device.getDeviceState());
            decision.setUnavailableWindows(blockers);
            if (!blockers.isEmpty()) {
                String reasons = blockers.stream().map(MesDeviceUnavailableWindowDTO::getReason)
                        .filter(StrUtil::isNotBlank).distinct().limit(3).reduce((a, b) -> a + "；" + b).orElse("存在不可用窗口");
                return finish(decision.setAllowed(false).setMessage("设备当前被TPM阻断：" + reasons), arrange, factoryCode);
            }
            return finish(decision.setAllowed(true).setMessage("设备[" + decision.getDeviceCode() + "]状态["
                    + decision.getDeviceStateName() + "]满足" + stageName(decision.getStage()) + "条件"), arrange, factoryCode);
        } catch (Exception exception) {
            return finish(decision.setAllowed(false).setMessage("设备状态校验失败并按不可用处理："
                    + StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName())), arrange, factoryCode);
        }
    }

    private List<MesDeviceUnavailableWindowDTO> activeBlockers(String deviceCode, boolean startStage,
                                                               String currentState) {
        LocalDateTime now = LocalDateTime.now();
        TpmDeviceAvailabilityQueryDTO query = new TpmDeviceAvailabilityQueryDTO();
        query.setDeviceCodes(List.of(deviceCode));
        query.setStartTime(now.minusSeconds(1));
        query.setEndTime(now.plusSeconds(1));
        R<List<TpmDeviceUnavailableWindowDTO>> response = availabilityService.unavailableWindows(query);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new IllegalStateException(response == null ? "TPM无响应" : response.getMsg());
        }
        List<MesDeviceUnavailableWindowDTO> blockers = new ArrayList<>();
        for (TpmDeviceUnavailableWindowDTO window : response.getData()) {
            // TPM把正在执行的设备标记为不可被APS再次占用；对其所属MES任务运行校验不能据此自我阻断。
            if ("DEVICE_STATE".equals(window.getSourceType())
                    && (DeviceStateEnum.RUNNING.getCode().equals(currentState)
                    || (startStage && (DeviceStateEnum.IDLE.getCode().equals(currentState)
                    || DeviceStateEnum.PAUSE.getCode().equals(currentState))))) {
                continue;
            }
            blockers.add(new MesDeviceUnavailableWindowDTO()
                    .setSourceType(window.getSourceType())
                    .setSourceId(window.getSourceId())
                    .setSourceCode(window.getSourceCode())
                    .setStartTime(window.getStartTime())
                    .setEndTime(window.getEndTime())
                    .setOpenEnded(window.getOpenEnded())
                    .setState(window.getState())
                    .setReason(window.getReason()));
        }
        return blockers;
    }

    private MesDeviceGateDecision baseDecision(MesDeviceGateStageEnum stage, MesTaskArrangeEntity arrange,
                                               String deviceCode, String worksiteCode) {
        return new MesDeviceGateDecision()
                .setStage(stage.name())
                .setArrangeCode(arrange == null ? null : arrange.getCode())
                .setProduceTaskCode(arrange == null ? null : arrange.getProduceTaskCode())
                .setWorksiteCode(worksiteCode)
                .setDeviceCode(deviceCode)
                .setDeviceName(arrange == null ? null : arrange.getPlannedDeviceName())
                .setCheckTime(LocalDateTime.now())
                .setUnavailableWindows(List.of());
    }

    private MesDeviceGateDecision finish(MesDeviceGateDecision decision, MesTaskArrangeEntity arrange) {
        return finish(decision, arrange, arrange == null ? null : arrange.getFactoryCode());
    }

    private MesDeviceGateDecision finish(MesDeviceGateDecision decision, MesTaskArrangeEntity arrange,
                                         String factoryCode) {
        if (!Boolean.TRUE.equals(observationOnly.get())) {
            recordService.record(decision, arrange, factoryCode);
        }
        return decision;
    }

    private String firstCode(String codes) {
        if (StrUtil.isBlank(codes)) {
            return null;
        }
        return codes.split(",")[0].trim();
    }

    private boolean containsCode(String codes, String candidate) {
        if (StrUtil.isBlank(codes) || StrUtil.isBlank(candidate)) {
            return false;
        }
        return List.of(codes.split(",")).stream().map(String::trim)
                .anyMatch(candidate.trim()::equals);
    }

    private String stageName(String stage) {
        try {
            return MesDeviceGateStageEnum.valueOf(stage).getName();
        } catch (Exception ignored) {
            return stage;
        }
    }
}
