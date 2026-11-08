package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.feign.RemoteFactoryModelService;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import com.kitex.mom.wms.dto.WmsLineDeliveryCommand;
import com.kitex.mom.wms.dto.WmsLineDeliveryEvidenceDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.bill.WmsOutBillAccess;
import com.kitex.mom.wms.service.bill.WmsOutBillAccess.Action;
import com.kitex.mom.wms.service.bill.WmsOutBillAccess.Family;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 仓库正式发料到生产工位之间的可执行配送、保管责任和差异闭环。
 */
@Service
@RequiredArgsConstructor
public class WmsLineDeliveryService {
    private static final String READY = "READY", DISPATCHED = "DISPATCHED", ACCEPTED = "ACCEPTED",
            IN_TRANSIT = "IN_TRANSIT", ARRIVED = "ARRIVED", EXCEPTION = "EXCEPTION", CLOSED = "CLOSED";
    private final WmsLineDeliveryTaskMapper taskMapper;
    private final WmsLineDeliveryDetailMapper detailMapper;
    private final WmsLineDeliveryOperationMapper operationMapper;
    private final WmsOutBillMapper outBillMapper;
    private final WmsOutBillDetailMapper outBillDetailMapper;
    private final RemoteFactoryModelService factoryModelService;
	private final WmsOutBillAccess outBillAccess;

    /**
     * 新生产发料成功时同事务建立READY任务；重复调用只核对，不重复建账。
     */
    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity ensureTask(WmsOutBillEntity source) {
        require(source != null && source.getId() != null, "生产发料出库单不能为空");
        WmsOutBillEntity bill = lockBill(source.getId());
        require(OutBillTypeEnum.PRODUCE_OUT.getCode().equals(bill.getBillType())
                        && Set.of(InOutBillStateEnum.PASSED.getCode(), InOutBillStateEnum.FINISHED.getCode()).contains(bill.getState()),
                "仅已通过或完成的生产发料可建立线边配送任务");
        List<WmsOutBillDetailEntity> sourceLines = sourceLines(bill.getId(), true);
        String fingerprint = sourceFingerprint(bill, sourceLines);
        WmsLineDeliveryTaskEntity existing = taskMapper.selectOne(new LambdaQueryWrapper<WmsLineDeliveryTaskEntity>()
                .eq(WmsLineDeliveryTaskEntity::getOutBillId, bill.getId()).last("FOR UPDATE"), false);
        if (existing != null) {
            require(fingerprint.equals(existing.getSourceFingerprint()), "生产发料来源在配送任务建立后发生变化");
            markRequired(bill, existing.getId());
            return existing;
        }
        BigDecimal total = sum(sourceLines, d -> qty(d.getMatNum()));
        WmsLineDeliveryTaskEntity task = new WmsLineDeliveryTaskEntity().setTaskNo("LD-" + bill.getId())
                .setOutBillId(bill.getId()).setOutBillNo(bill.getBillNo()).setState(READY)
                .setOperationSeq(1)
                .setSourceFingerprint(fingerprint).setPlannedNum(total).setDeliveredNum(BigDecimal.ZERO)
                .setExceptionNum(BigDecimal.ZERO).setRecoveredNum(BigDecimal.ZERO).setFactoryCode(bill.getFactoryCode());
        require(taskMapper.insert(task) == 1, "线边配送任务建立失败");
        for (WmsOutBillDetailEntity d : sourceLines) {
            WmsLineDeliveryDetailEntity line = new WmsLineDeliveryDetailEntity().setTaskId(task.getId())
                    .setOutBillDetailId(d.getId()).setMatCode(d.getMatCode()).setMatName(d.getMatName())
                    .setMatBatch(d.getMatBatch()).setMatUnit(d.getMatUnit()).setPlannedNum(qty(d.getMatNum()))
                    .setDeliveredNum(BigDecimal.ZERO).setShortNum(BigDecimal.ZERO).setDamagedNum(BigDecimal.ZERO)
                    .setRecoveredNum(BigDecimal.ZERO).setReturnedNum(BigDecimal.ZERO).setLossNum(BigDecimal.ZERO);
            require(detailMapper.insert(line) == 1, "线边配送明细建立失败");
        }
        markRequired(bill, task.getId());
        MomUser user = optionalUser();
        operation(task, "AUTO-LD-" + bill.getId(), "CREATE", READY, READY, "生产发料完成",
                "生产发料出库与线边配送任务同事务建立", JSONUtil.toJsonStr(Arrays.asList(bill.getId(), fingerprint)), user);
        return task;
    }

	/**
	 * 按真实生产发料原单的租户、实名工厂及仓级查看权限读取配送；未建任务才返回空。
	 */
    public WmsLineDeliveryTaskEntity viewByOutBill(Long outBillId) {
		requireOutBillAccess(outBillId, false);
        WmsOutBillEntity bill = outBillMapper.selectById(outBillId);
        require(bill != null, "生产发料出库单不存在");
        requireFactory(bill.getFactoryCode());
        WmsLineDeliveryTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<WmsLineDeliveryTaskEntity>()
                .eq(WmsLineDeliveryTaskEntity::getOutBillId, outBillId), false);
        return task == null ? null : populate(task);
    }

	/**
	 * 按任务实际原单核查看范围后返回明细和履历，任务ID不能替代原仓授权。
	 */
    public WmsLineDeliveryTaskEntity view(Long id) {
        WmsLineDeliveryTaskEntity task = taskMapper.selectById(id);
        require(task != null, "线边配送任务不存在");
		requireTaskAccess(task, false);
        return populate(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity dispatch(Long id, WmsLineDeliveryCommand.Dispatch command) {
        require(command != null && command.getRequiredArrivalTime() != null, "派单要求到达时间不能为空");
        String request = required(command.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(command);
        WmsLineDeliveryOperationEntity replay = replay(request, "DISPATCH", snapshot);
        if (replay != null) return view(replay.getTaskId());
        WmsLineDeliveryTaskEntity task = lock(id);
        require(READY.equals(task.getState()), "仅待派单任务可派单");
        require(command.getRequiredArrivalTime().isAfter(LocalDateTime.now()), "要求到达时间必须晚于当前时间");
        String priority = StrUtil.blankToDefault(command.getPriority(), "NORMAL").toUpperCase(Locale.ROOT);
        require(Set.of("NORMAL", "URGENT", "EMERGENCY").contains(priority), "配送优先级非法");
        MdmFactoryModelTreeVO site = uniqueStation(task.getFactoryCode(), required(command.getTargetSiteCode(), 100, "目标工位"));
        verifySource(task, true);
        MomUser user = user();
        task.setTargetSiteCode(site.getCode()).setTargetSiteName(site.getName()).setPriority(priority)
                .setRequiredArrivalTime(command.getRequiredArrivalTime()).setDispatchBy(user.getUsername())
                .setDispatchByName(name(user)).setDispatchTime(LocalDateTime.now())
                .setDispatchReason(required(command.getReason(), 500, "派单原因"))
                .setDispatchEvidence(required(command.getEvidence(), 500, "派单证据")).setState(DISPATCHED)
                .setOperationSeq(task.getOperationSeq() + 1);
        require(taskMapper.updateById(task) == 1, "线边配送派单保存失败");
        operation(task, request, "DISPATCH", READY, DISPATCHED, command.getReason(), command.getEvidence(), snapshot, user);
        return populate(task);
    }

    /**
     * 未启运前由第三人改派；保留原派单/接单履历，但清空旧配送责任快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity redispatch(Long id, WmsLineDeliveryCommand.Dispatch command) {
        require(command != null && command.getRequiredArrivalTime() != null, "改派要求到达时间不能为空");
        String request = required(command.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(command);
        WmsLineDeliveryOperationEntity replay = replay(request, "REDISPATCH", snapshot);
        if (replay != null) return view(replay.getTaskId());
        WmsLineDeliveryTaskEntity task = lock(id);
        require(Set.of(DISPATCHED, ACCEPTED).contains(task.getState()), "仅未启运的已派单或已接单任务可改派");
        MomUser user = user();
        require(!Objects.equals(task.getDispatchBy(), user.getUsername())
                && !Objects.equals(task.getTransporterCode(), user.getUsername()), "改派人必须与原派单人、原配送人分离");
        require(command.getRequiredArrivalTime().isAfter(LocalDateTime.now()), "改派要求到达时间必须晚于当前时间");
        String priority = StrUtil.blankToDefault(command.getPriority(), "NORMAL").toUpperCase(Locale.ROOT);
        require(Set.of("NORMAL", "URGENT", "EMERGENCY").contains(priority), "配送优先级非法");
        MdmFactoryModelTreeVO site = uniqueStation(task.getFactoryCode(), required(command.getTargetSiteCode(), 100, "目标工位"));
        verifySource(task, true);
        String before = task.getState();
        task.setTargetSiteCode(site.getCode()).setTargetSiteName(site.getName()).setPriority(priority)
                .setRequiredArrivalTime(command.getRequiredArrivalTime()).setDispatchBy(user.getUsername())
                .setDispatchByName(name(user)).setDispatchTime(LocalDateTime.now())
                .setDispatchReason(required(command.getReason(), 500, "改派原因"))
                .setDispatchEvidence(required(command.getEvidence(), 500, "改派证据"))
                .setTransporterCode(null).setTransporterName(null).setAcceptedTime(null).setTransportMode(null)
                .setVehicleCode(null).setContainerCode(null).setSealNo(null).setDepartedTime(null)
                .setState(DISPATCHED).setOperationSeq(task.getOperationSeq() + 1);
        require(taskMapper.updateById(task) == 1, "线边配送改派保存失败");
        operation(task, request, "REDISPATCH", before, DISPATCHED, command.getReason(), command.getEvidence(), snapshot, user);
        return populate(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity accept(Long id, WmsLineDeliveryCommand.Accept command) {
        require(command != null, "接单命令不能为空");
        String request = required(command.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(command);
        WmsLineDeliveryOperationEntity replay = replay(request, "ACCEPT", snapshot);
        if (replay != null) return view(replay.getTaskId());
        WmsLineDeliveryTaskEntity task = lock(id);
        require(DISPATCHED.equals(task.getState()), "仅已派单任务可接单");
        MomUser user = user();
        require(!Objects.equals(task.getDispatchBy(), user.getUsername()), "派单人与配送接单人必须分离");
        verifySource(task, true);
        String mode = required(command.getTransportMode(), 30, "运输方式").toUpperCase(Locale.ROOT);
        require(Set.of("MANUAL", "TROLLEY", "FORKLIFT", "AGV", "OTHER").contains(mode), "运输方式非法");
        if (!"MANUAL".equals(mode))
            require(StrUtil.isNotBlank(command.getVehicleCode()), "非人工配送必须填写车辆或设备编码");
        task.setTransporterCode(user.getUsername()).setTransporterName(name(user)).setAcceptedTime(LocalDateTime.now())
                .setTransportMode(mode).setVehicleCode(trim(command.getVehicleCode(), 100, "车辆或设备编码"))
                .setContainerCode(trim(command.getContainerCode(), 100, "容器编码"))
                .setSealNo(trim(command.getSealNo(), 100, "封签号")).setState(ACCEPTED)
                .setOperationSeq(task.getOperationSeq() + 1);
        require(taskMapper.updateById(task) == 1, "线边配送接单保存失败");
        operation(task, request, "ACCEPT", DISPATCHED, ACCEPTED, null,
                required(command.getEvidence(), 500, "接单证据"), snapshot, user);
        return populate(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity transit(Long id, WmsLineDeliveryCommand.Transit command) {
        require(command != null, "启运命令不能为空");
        String request = required(command.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(command);
        WmsLineDeliveryOperationEntity replay = replay(request, "TRANSIT", snapshot);
        if (replay != null) return view(replay.getTaskId());
        WmsLineDeliveryTaskEntity task = lock(id);
        require(ACCEPTED.equals(task.getState()), "仅已接单任务可启运");
        MomUser user = user();
        require(Objects.equals(task.getTransporterCode(), user.getUsername()), "仅实际接单配送人可确认启运");
        verifySource(task, true);
        task.setDepartedTime(LocalDateTime.now()).setState(IN_TRANSIT).setOperationSeq(task.getOperationSeq() + 1);
        require(taskMapper.updateById(task) == 1, "线边配送启运保存失败");
        operation(task, request, "TRANSIT", ACCEPTED, IN_TRANSIT, null,
                required(command.getEvidence(), 500, "启运证据"), snapshot, user);
        return populate(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity arrive(Long id, WmsLineDeliveryCommand.Arrival command) {
        require(command != null && command.getLines() != null && !command.getLines().isEmpty(), "到达逐行数量不能为空");
        String request = required(command.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(command);
        WmsLineDeliveryOperationEntity replay = replay(request, "ARRIVE", snapshot);
        if (replay != null) return view(replay.getTaskId());
        WmsLineDeliveryTaskEntity task = lock(id);
        require(IN_TRANSIT.equals(task.getState()), "仅在途任务可登记到达");
        MomUser user = user();
        require(!Objects.equals(task.getTransporterCode(), user.getUsername()), "配送人与目的工位到达确认人必须分离");
        verifySource(task, true);
        List<WmsLineDeliveryDetailEntity> details = details(task.getId(), true);
        Map<Long, WmsLineDeliveryCommand.ArrivalLine> input = command.getLines().stream().collect(Collectors.toMap(
                WmsLineDeliveryCommand.ArrivalLine::getOutBillDetailId, Function.identity(), (a, b) -> {
                    throw new CheckedException("同一出库明细不能重复登记到达");
                }));
        require(input.size() == details.size() && details.stream().allMatch(d -> input.containsKey(d.getOutBillDetailId())),
                "到达登记必须完整覆盖全部配送明细");
        BigDecimal delivered = BigDecimal.ZERO, exception = BigDecimal.ZERO;
        for (WmsLineDeliveryDetailEntity d : details) {
            WmsLineDeliveryCommand.ArrivalLine x = input.get(d.getOutBillDetailId());
            BigDecimal actual = nonnegative(x.getDeliveredNum(), "实到量"), shortage = nonnegative(x.getShortNum(), "短少量"),
                    damaged = nonnegative(x.getDamagedNum(), "破损量");
            require(actual.add(shortage).add(damaged).compareTo(d.getPlannedNum()) == 0,
                    "配送明细" + d.getOutBillDetailId() + "的实到、短少和破损数量不守恒");
            BigDecimal abnormal = shortage.add(damaged);
            if (abnormal.signum() > 0) {
                require(Set.of("WAREHOUSE", "TRANSPORT", "PRODUCTION", "QUALITY", "UNKNOWN")
                        .contains(StrUtil.blankToDefault(x.getResponsibility(), "")), "配送异常责任方非法");
                d.setExceptionReason(required(x.getReason(), 500, "异常原因"));
            } else {
                require(StrUtil.isBlank(x.getResponsibility()) && StrUtil.isBlank(x.getReason()), "无异常数量的行不得填写异常责任或原因");
            }
            d.setDeliveredNum(actual).setShortNum(shortage).setDamagedNum(damaged)
                    .setResponsibility(StrUtil.blankToDefault(x.getResponsibility(), null));
            require(detailMapper.updateById(d) == 1, "线边配送到达明细保存失败");
            delivered = delivered.add(actual);
            exception = exception.add(abnormal);
        }
        String after = exception.signum() == 0 ? ARRIVED : EXCEPTION;
        task.setDeliveredNum(delivered).setExceptionNum(exception).setArrivalBy(user.getUsername())
                .setArrivalByName(name(user)).setArrivedTime(LocalDateTime.now())
                .setArrivalEvidence(required(command.getEvidence(), 500, "到达证据")).setState(after)
                .setOperationSeq(task.getOperationSeq() + 1);
        require(taskMapper.updateById(task) == 1, "线边配送到达状态保存失败");
        operation(task, request, "ARRIVE", IN_TRANSIT, after, exception.signum() == 0 ? null : "存在短少或破损",
                command.getEvidence(), snapshot, user);
        return populate(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsLineDeliveryTaskEntity resolve(Long id, WmsLineDeliveryCommand.Resolve command) {
        require(command != null && command.getLines() != null && !command.getLines().isEmpty(), "异常核销逐行方案不能为空");
        String request = required(command.getRequestId(), 64, "请求号"), snapshot = JSONUtil.toJsonStr(command);
        WmsLineDeliveryOperationEntity replay = replay(request, "RESOLVE", snapshot);
        if (replay != null) return view(replay.getTaskId());
        WmsLineDeliveryTaskEntity task = lock(id);
        require(EXCEPTION.equals(task.getState()), "仅存在到达差异的配送任务可核销");
        MomUser user = user();
        require(!Objects.equals(task.getArrivalBy(), user.getUsername()) && !Objects.equals(task.getTransporterCode(), user.getUsername()),
                "异常核销人必须与配送人、到达确认人分离");
        verifySource(task, true);
        List<WmsLineDeliveryDetailEntity> details = details(task.getId(), true);
        Map<Long, WmsLineDeliveryCommand.ResolveLine> input = command.getLines().stream().collect(Collectors.toMap(
                WmsLineDeliveryCommand.ResolveLine::getDetailId, Function.identity(), (a, b) -> {
                    throw new CheckedException("同一配送异常明细不能重复核销");
                }));
        require(input.size() == details.size() && details.stream().allMatch(d -> input.containsKey(d.getId())),
                "异常核销必须完整覆盖全部配送明细");
        BigDecimal recoveredTotal = BigDecimal.ZERO;
        for (WmsLineDeliveryDetailEntity d : details) {
            WmsLineDeliveryCommand.ResolveLine x = input.get(d.getId());
            BigDecimal recovered = nonnegative(x.getRecoveredNum(), "追回送达量"), returned = nonnegative(x.getReturnedNum(), "退回仓库量"),
                    loss = nonnegative(x.getLossNum(), "批准损失量"), abnormal = d.getShortNum().add(d.getDamagedNum());
            require(recovered.compareTo(d.getShortNum()) <= 0, "追回送达量不能包含原破损实物");
            require(returned.compareTo(d.getDamagedNum()) <= 0, "退回仓库量不能超过原破损量");
            require(recovered.add(returned).add(loss).compareTo(abnormal) == 0,
                    "配送明细" + d.getId() + "的追回、退回和批准损失必须完整核销异常量");
            if (abnormal.signum() > 0) requireText(x.getResolutionReference(), 200, "核销依据编号");
            d.setRecoveredNum(recovered).setReturnedNum(returned).setLossNum(loss)
                    .setResolutionReference(trim(x.getResolutionReference(), 200, "核销依据编号"))
                    .setResolutionRemark(trim(x.getRemark(), 500, "核销备注"));
            require(detailMapper.updateById(d) == 1, "线边配送异常核销明细保存失败");
            recoveredTotal = recoveredTotal.add(recovered);
        }
        task.setRecoveredNum(recoveredTotal).setCloseBy(user.getUsername()).setCloseByName(name(user))
                .setClosedTime(LocalDateTime.now()).setCloseSummary(required(command.getSummary(), 500, "核销摘要"))
                .setCloseEvidence(required(command.getEvidence(), 500, "核销证据")).setState(CLOSED)
                .setOperationSeq(task.getOperationSeq() + 1);
        require(taskMapper.updateById(task) == 1, "线边配送异常结案保存失败");
        operation(task, request, "RESOLVE", EXCEPTION, CLOSED, command.getSummary(), command.getEvidence(), snapshot, user);
        return populate(task);
    }

    /**
     * 内部证据不依赖客户端快照；MES只能签收已到达或经异岗追回的数量。
     */
    public WmsLineDeliveryEvidenceDTO evidence(Long outBillDetailId) {
        WmsOutBillDetailEntity source = outBillDetailMapper.selectById(outBillDetailId);
        require(source != null, "WMS生产发料明细不存在");
        WmsOutBillEntity bill = outBillMapper.selectById(source.getBillId());
        require(bill != null && OutBillTypeEnum.PRODUCE_OUT.getCode().equals(bill.getBillType()), "WMS生产发料主单不存在或类型不符");
        require(Boolean.TRUE.equals(bill.getLineDeliveryRequired()) && bill.getLineDeliveryTaskId() != null,
                "该新生产发料缺少强制线边配送任务");
        WmsLineDeliveryTaskEntity task = taskMapper.selectById(bill.getLineDeliveryTaskId());
        require(task != null && Objects.equals(task.getOutBillId(), bill.getId()), "生产发料与线边配送任务关联异常");
        List<WmsOutBillDetailEntity> sourceLines = sourceLines(bill.getId(), false);
        require(sourceFingerprint(bill, sourceLines).equals(task.getSourceFingerprint()), "线边配送来源事实已变化");
        require(Set.of(ARRIVED, EXCEPTION, CLOSED).contains(task.getState()), "物料尚未完成目的工位到达确认");
        WmsLineDeliveryDetailEntity line = detailMapper.selectOne(new LambdaQueryWrapper<WmsLineDeliveryDetailEntity>()
                .eq(WmsLineDeliveryDetailEntity::getTaskId, task.getId())
                .eq(WmsLineDeliveryDetailEntity::getOutBillDetailId, outBillDetailId), false);
        require(line != null, "线边配送明细来源缺失");
        BigDecimal recovered = nz(line.getRecoveredNum());
        return new WmsLineDeliveryEvidenceDTO().setTaskId(task.getId()).setTaskNo(task.getTaskNo()).setState(task.getState())
                .setOutBillId(bill.getId()).setOutBillDetailId(source.getId()).setFactoryCode(bill.getFactoryCode())
                .setMatCode(source.getMatCode()).setPlannedNum(line.getPlannedNum()).setDeliveredNum(nz(line.getDeliveredNum()))
                .setRecoveredNum(recovered).setReceivableNum(nz(line.getDeliveredNum()).add(recovered))
                .setSourceFingerprint(task.getSourceFingerprint());
    }

    private void markRequired(WmsOutBillEntity bill, Long taskId) {
        require(outBillMapper.update(null, new LambdaUpdateWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getId, bill.getId())
                .and(w -> w.isNull(WmsOutBillEntity::getLineDeliveryTaskId).or().eq(WmsOutBillEntity::getLineDeliveryTaskId, taskId))
                .set(WmsOutBillEntity::getLineDeliveryRequired, true)
                .set(WmsOutBillEntity::getLineDeliveryTaskId, taskId)) == 1, "生产发料线边配送强制标记保存失败");
    }

    private WmsLineDeliveryTaskEntity populate(WmsLineDeliveryTaskEntity task) {
        task.setDetails(details(task.getId(), false));
        task.setOperations(operationMapper.selectList(new LambdaQueryWrapper<WmsLineDeliveryOperationEntity>()
                .eq(WmsLineDeliveryOperationEntity::getTaskId, task.getId())
                .orderByAsc(WmsLineDeliveryOperationEntity::getSequenceNo)));
        return task;
    }

    private void verifySource(WmsLineDeliveryTaskEntity task, boolean lock) {
        WmsOutBillEntity bill = lock ? lockBill(task.getOutBillId()) : outBillMapper.selectById(task.getOutBillId());
        require(bill != null && Boolean.TRUE.equals(bill.getLineDeliveryRequired())
                && Objects.equals(bill.getLineDeliveryTaskId(), task.getId()), "生产发料与线边配送任务未双向绑定");
        require(sourceFingerprint(bill, sourceLines(bill.getId(), lock)).equals(task.getSourceFingerprint()),
                "生产发料来源事实已变化，禁止继续配送");
    }

    private MdmFactoryModelTreeVO uniqueStation(String factory, String code) {
        R<List<MdmFactoryModelTreeVO>> response = factoryModelService.getNodesByType("STATION", factory);
        require(response != null && response.isOk() && response.getData() != null, "MDM目标工位不可核验");
        List<MdmFactoryModelTreeVO> matches = response.getData().stream().filter(x -> Boolean.TRUE.equals(x.getStatus())
                && "STATION".equals(x.getType()) && Objects.equals(factory, x.getFactoryCode()) && Objects.equals(code, x.getCode())).toList();
        require(matches.size() == 1, "目标工位不存在、停用、跨工厂或编码重复");
        return matches.get(0);
    }

    private String sourceFingerprint(WmsOutBillEntity bill, List<WmsOutBillDetailEntity> details) {
        return hash(JSONUtil.toJsonStr(Arrays.asList(bill.getId(), bill.getBillNo(), bill.getFactoryCode(), bill.getBillType(),
                bill.getState(), bill.getOutTime(), details.stream().map(d -> Arrays.asList(d.getId(), d.getMatCode(), d.getMatBatch(),
                        d.getMatUnit(), d.getWarehouseCode(), d.getSiteCode(), d.getContainerCode(), qty(d.getMatNum()).stripTrailingZeros().toPlainString(),
                        d.getQualityResult(), d.getReservationNo())).toList())));
    }

    private WmsOutBillEntity lockBill(Long id) {
		requireOutBillAccess(id, true);
        WmsOutBillEntity bill = outBillMapper.selectOne(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getId, id).last("FOR UPDATE"), false);
        require(bill != null, "生产发料出库单不存在");
        requireFactory(bill.getFactoryCode());
        return bill;
    }

    private WmsLineDeliveryTaskEntity lock(Long id) {
        WmsLineDeliveryTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<WmsLineDeliveryTaskEntity>()
                .eq(WmsLineDeliveryTaskEntity::getId, id).last("FOR UPDATE"), false);
        require(task != null, "线边配送任务不存在");
		requireTaskAccess(task, true);
        return task;
    }

    private List<WmsOutBillDetailEntity> sourceLines(Long billId, boolean lock) {
        List<WmsOutBillDetailEntity> lines = outBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                .eq(WmsOutBillDetailEntity::getBillId, billId).orderByAsc(WmsOutBillDetailEntity::getId).last(lock ? "FOR SHARE" : ""));
        require(!lines.isEmpty() && lines.stream().allMatch(d -> d.getId() != null && d.getMatNum() != null
                        && Double.isFinite(d.getMatNum()) && d.getMatNum() > 0 && StrUtil.isNotBlank(d.getMatCode())),
                "生产发料明细缺少物料或正数量");
        return lines;
    }

    private List<WmsLineDeliveryDetailEntity> details(Long taskId, boolean lock) {
        return detailMapper.selectList(new LambdaQueryWrapper<WmsLineDeliveryDetailEntity>()
                .eq(WmsLineDeliveryDetailEntity::getTaskId, taskId).orderByAsc(WmsLineDeliveryDetailEntity::getId)
                .last(lock ? "FOR UPDATE" : ""));
    }

    private WmsLineDeliveryOperationEntity replay(String request, String type, String snapshot) {
        WmsLineDeliveryOperationEntity row = operationMapper.selectOne(new LambdaQueryWrapper<WmsLineDeliveryOperationEntity>()
                .eq(WmsLineDeliveryOperationEntity::getRequestId, request), false);
		if (row != null) {
			WmsLineDeliveryTaskEntity task = taskMapper.selectById(row.getTaskId());
			require(task != null, "线边配送任务不存在");
			// 回放只读原结果，仍先核当前实名工厂写准入和原仓查看范围，不能凭请求号越权读取履历。
			requireTaskAccess(task, true);
			require(type.equals(row.getOperationType()) && snapshot.equals(row.getFactSnapshot()),
					"请求号已被不同线边配送事实使用");
		}
        return row;
	}

	/**
	 * 复用固定生产发料族的真实原单授权；写入口由原事务核工厂写权限并锁原单。
	 * 配送各动作权限仍由原Controller独立校验，不以查看权限替代派单、到达或核销权限。
	 */
	private void requireOutBillAccess(Long outBillId, boolean write) {
		require(outBillId != null, "生产发料出库单不能为空");
		outBillAccess.originals(Family.PRODUCE, List.of(outBillId), Action.VIEW, write);
	}

	/**
	 * 任务及回放按实际关联原单核准后，再确认任务工厂与当前实名工厂一致。
	 */
	private void requireTaskAccess(WmsLineDeliveryTaskEntity task, boolean write) {
		requireOutBillAccess(task.getOutBillId(), write);
		requireFactory(task.getFactoryCode());
    }

    private void operation(WmsLineDeliveryTaskEntity task, String request, String type, String before, String after,
                           String reason, String evidence, String snapshot, MomUser user) {
        String username = user == null ? "SYSTEM" : user.getUsername(), display = user == null ? "系统" : name(user);
        WmsLineDeliveryOperationEntity row = new WmsLineDeliveryOperationEntity()
                .setTaskId(task.getId()).setSequenceNo(task.getOperationSeq()).setRequestId(request).setOperationType(type).setBeforeState(before).setAfterState(after)
                .setOperatorCode(username).setOperatorName(display).setOperatedTime(LocalDateTime.now()).setReason(StrUtil.trim(reason))
                .setEvidence(required(evidence, 500, "操作证据")).setFactSnapshot(snapshot).setFactoryCode(task.getFactoryCode());
        row.setTenantId(task.getTenantId());
        require(operationMapper.insert(row) == 1, "线边配送永久操作履历保存失败");
    }

    private <T> BigDecimal sum(Collection<T> rows, Function<T, BigDecimal> mapper) {
        return rows.stream().map(mapper).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal qty(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonnegative(BigDecimal value, String label) {
        BigDecimal v = nz(value);
        require(v.signum() >= 0, label + "不能为负");
        return v;
    }

    private void requireText(String value, int max, String label) {
        required(value, max, label);
    }

    private String required(String value, int max, String label) {
        String v = StrUtil.trim(value);
        require(StrUtil.isNotBlank(v) && v.length() <= max, label + "不能为空且不能超过" + max + "字");
        return v;
    }

    private String trim(String value, int max, String label) {
        if (StrUtil.isBlank(value)) return null;
        String v = value.trim();
        require(v.length() <= max, label + "不能超过" + max + "字");
        return v;
    }

    private MomUser user() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前操作人");
        return user;
    }

    private MomUser optionalUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String name(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
		require(StrUtil.isNotBlank(factory) && StrUtil.isNotBlank(selected) && Objects.equals(selected, factory),
				"当前工厂无权操作线边配送任务");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new CheckedException("线边配送来源指纹计算失败", e);
        }
    }
}
