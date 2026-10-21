package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteMdmParticipantService;
import com.kitex.mom.mes.dto.MesShiftHandoverCommand;
import com.kitex.mom.mes.entity.MesShiftHandover;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.agent.service.MesStartPreparationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 正式交接的身份和班次源。历史事实按已冻结版本阅读，接班只重核指定账号的当前资格。
 * 班次成员不是员工档案，也不调用会自动写绑定的currentBindings接口。
 */
@Service
@RequiredArgsConstructor
public class MesFormalHandoverSourceService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final RemoteClassesService classes;
    private final RemoteMdmParticipantService people;
    private final MesTaskArrangeMapper arranges;
    private final MesActualFactoryAccessService access;
    private final MesShiftLeftoverService leftovers;
    private final MesStartPreparationQueryService preparation;

    public record Shift(String id, String code, String name, String start, String end) { }
    public record Occurrence(String occurrenceId, String masterId, String code, String name,
                             LocalDate date, LocalDateTime start, LocalDateTime end) { }
    public record FormalSource(String snapshotJson, String fingerprint, String receiverId,
                               LocalDateTime windowStart, LocalDateTime windowEnd) { }

    /** 班次目录与真实参与人各自分页；不把全部人员塞进表单。 */
    public List<Shift> shifts() {
        String factory = access.require("read");
        return activeShifts(factory).stream().map(row -> new Shift(row.getId().toString(), row.getShiftCode(),
                row.getShiftName(), row.getStartTime().toString(), row.getEndTime().toString())).toList();
    }

    public MdmParticipantModels.ParticipantPage participants(String keyword, long current, long size) {
        String factory = access.require("read");
        var result = people.search(new MdmParticipantModels.Search(factory, "HANDOVER", keyword, current, size));
        require(result != null && result.getCode() == 0 && result.getData() != null, "接班账号权限服务不可用");
        return result.getData();
    }

    public MdmParticipantModels.Participant verifyPerson(String factory, String id) {
        require(id != null && id.matches("[1-9][0-9]{0,18}"), "请选择真实接班账号");
        var response = people.verify(new MdmParticipantModels.Verify(factory, "HANDOVER", List.of(id)));
        require(response != null && response.getCode() == 0 && response.getData() != null
                && response.getData().size() == 1, "接班人员账号、工厂或办理权限已失效，请重新选择");
        var person = response.getData().get(0);
        require(id.equals(person.userId()) && factory.equals(person.factoryCode())
                && "HANDOVER".equals(person.purpose()), "接班人员返回范围不匹配");
        return person;
    }

    /** 预览/提交均取同一组权威主数据；不把observedAt计入摘要。 */
    public FormalSource resolve(String factory, MesShiftHandoverCommand command) {
        require(command != null, "交接命令不能为空");
        List<MdmClassesEntity> rules = activeShifts(factory);
        Occurrence source = occurrence(rules, command.getSourceShiftCode(), command.getSourceShiftDate());
        Occurrence target = occurrence(rules, command.getTargetShiftCode(), command.getTargetShiftDate());
        require((command.getWindowStart() == null || source.start().equals(command.getWindowStart()))
                        && (command.getWindowEnd() == null || source.end().equals(command.getWindowEnd())),
                "提交时间窗与真实班次不一致，请重取预览");
        require(!source.end().isAfter(LocalDateTime.now()), "交班班次尚未结束");
        require(!target.start().isBefore(source.end()) && !target.start().isAfter(source.end().plusDays(1)),
                "接班起点不得早于交班结束，也不得相隔超过24小时");
        var receiver = verifyPerson(factory, command.getDesignatedReceiverId());
        require(!String.valueOf(SecurityUtils.getUser().getId()).equals(receiver.userId()), "交班人与指定接班负责人不能是同一人");
        require(command.getArrangeCodes() != null && !command.getArrangeCodes().isEmpty()
                && command.getArrangeCodes().size() <= 100, "请选择1至100个明确派工单");
        require(command.getArrangeCodes().stream().allMatch(code -> code != null && !code.isBlank() && code.length() <= 100), "派工编码不能为空或超过100字符");
        List<String> codes = command.getArrangeCodes().stream().map(String::trim).sorted().toList();
        require(new HashSet<>(codes).size() == codes.size(), "派工范围不能重复");
        List<MesTaskArrangeEntity> selected = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).in(MesTaskArrangeEntity::getCode, codes)
                .orderByAsc(MesTaskArrangeEntity::getCode));
        require(selected.size() == codes.size(), "派工范围不存在或不属于当前工厂");
        String datedCode = source.date().format(DateTimeFormatter.ofPattern("yyMMdd")) + source.code();
        List<Map<String, Object>> wip = new ArrayList<>();
        for (var arrange : selected) {
            boolean dated = datedCode.equals(arrange.getShiftCode());
            boolean plain = source.code().equals(arrange.getShiftCode()) && arrange.getPlannedStartTime() != null
                    && !arrange.getPlannedStartTime().isBefore(source.start()) && arrange.getPlannedStartTime().isBefore(source.end());
            require(dated || plain, "派工" + arrange.getCode() + "没有本次真实班次归属；不能按同名或无日期推断");
            Map<String, Object> row = new TreeMap<>();
            row.put("arrangeId", arrange.getId().toString());
            row.put("arrangeCode", arrange.getCode());
            row.put("taskCode", arrange.getTaskCode());
            row.put("status", arrange.getStatus());
            row.put("shiftCode", arrange.getShiftCode());
            wip.add(row);
        }
        Map<String, Object> proof = new TreeMap<>();
        proof.put("contract", "FORMAL_SHIFT_HANDOVER_V3");
        proof.put("tenantId", String.valueOf(TenantContextHolder.getTenantId()));
        proof.put("factoryCode", factory);
        proof.put("sourceShift", source);
        proof.put("targetShift", target);
        proof.put("designatedReceiver", receiver);
        proof.put("signatureRule", "ONE_DESIGNATED_RECEIVER_REPRESENTS_TEAM");
        proof.put("declarations", Map.of("sourceType", "HUMAN_DECLARED", "safety", note(command.getSafetyNote()),
                "equipment", note(command.getEquipmentNote()), "quality", note(command.getQualityNote()),
                "material", note(command.getMaterialNote())));
        proof.put("currentWip", wip);
        List<Map<String, Object>> readiness = new ArrayList<>();
        for (var arrange : selected.stream().limit(20).toList()) {
            // 只移除该固定合同的观察外壳时钟，保留放行时间、有效期及正式源签名等真实业务证据。
            // MATERIAL门的asOf已由准备服务移除；不能无差别删除所有时间或来源指纹来掩盖真实变化。
            Map<String, Object> observed = preparation.observe(arrange.getId());
            require("MES_START_PREPARATION_OBSERVATION_V1".equals(observed.get("contract")), "准备观察合同不兼容，不能静默忽略字段变化");
            var stable = new TreeMap<>(observed);
            stable.remove("observedAt");
            readiness.add(stable);
        }
        proof.put("currentReadiness", readiness);
        proof.put("observationTimePolicy", "HANDOVER_CREATE_TIME_NOT_BOOKED_WINDOW");
        proof.put("observationGaps", selected.size() > 20
                ? List.of("当前准备门观察只覆盖稳定排序前20个派工，其余须另行核查，不能宣称全班准备就绪") : List.of());
        command.setWindowStart(source.start());
        command.setWindowEnd(source.end());
        proof.put("leftovers", leftovers.preview(factory, command));
        proof.put("scopeBoundary", "产量/工时按独立已结束时间窗核算；在制/准备门为交班提交时观察，不回填成过去班次事实。作业资格针对交班本人而非全班。四项现场声明属人工责任，不代表跨系统全量自动核验。");
        String snapshot = json(proof);
        require(snapshot.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024, "正式交接现场证据超过4MiB，请拆分明确派工范围");
        return new FormalSource(snapshot, hash(snapshot), receiver.userId(), source.start(), source.end());
    }

    /** 旧版本保持原异人规则；V3只允许指定负责人，撤权后不能凭旧快照接班。 */
    public void requireReceiver(MesShiftHandover handover, String factory) {
        if (handover.getFormalSnapshotJson() == null) return;
        require(handover.getFormalFingerprint() != null
                && hash(handover.getFormalSnapshotJson()).equals(handover.getFormalFingerprint()), "正式交接元数据损坏");
        require(Objects.equals(handover.getDesignatedReceiverId(), SecurityUtils.getUser().getId()),
                "此版本须由指定接班负责人处理，其他人不能代签");
        verifyPerson(factory, handover.getDesignatedReceiverId().toString());
    }

    private List<MdmClassesEntity> activeShifts(String factory) {
        var response = classes.listActive();
        require(response != null && response.getCode() == 0 && response.getData() != null, "MDM班次源不可用");
        var rows = response.getData().stream().filter(row -> Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
                && factory.equals(row.getFactoryCode()) && Boolean.TRUE.equals(row.getStatus())).toList();
        require(rows.size() <= 200, "启用班次超过200个，请先治理班次主数据");
        require(rows.stream().allMatch(row -> row.getId() != null && row.getShiftCode() != null
                && row.getStartTime() != null && row.getEndTime() != null
                && !row.getStartTime().equals(row.getEndTime())), "启用班次存在不完整/零长度规则");
        require(rows.stream().map(MdmClassesEntity::getShiftCode).distinct().count() == rows.size(), "当前工厂启用班次编码重复");
        return rows.stream().sorted(Comparator.comparing(MdmClassesEntity::getShiftCode)).toList();
    }

    private Occurrence occurrence(List<MdmClassesEntity> rows, String code, LocalDate date) {
        require(code != null && date != null, "请选择真实班次及所属日期");
        var row = rows.stream().filter(item -> code.equals(item.getShiftCode())).findFirst()
                .orElseThrow(() -> new CheckedException("所选班次不存在或已停用"));
        LocalDateTime start = date.atTime(row.getStartTime());
        LocalDateTime end = date.atTime(row.getEndTime());
        if (end.isBefore(start)) end = end.plusDays(1);
        return new Occurrence(row.getId() + ":" + date, row.getId().toString(), row.getShiftCode(), row.getShiftName(), date, start, end);
    }

    private String note(String note) {
        require(note != null && !note.isBlank() && note.length() <= 2000, "安全、设备、质量、物料现场情况各须填写1至2000字；未核查应如实说明");
        return note.trim();
    }

    private String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw new CheckedException("正式交接证据无法序列化"); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
