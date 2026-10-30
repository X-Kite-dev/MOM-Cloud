package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesMobileExecutionDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTerminalExecutionCommandEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTerminalExecutionCommandMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesTerminalExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 移动派工永久原命令协议。三个独立事务依次登记、提交占用、执行领域动作。
 *
 * <p>EXECUTING不意味着成功。只确认本地回滚且没有生产远端写才登记REJECTED；
 * 提交/回滚或远端结果不明均保留UNKNOWN，崩溃留下EXECUTING不自动重投。</p>
 */
@Service
@RequiredArgsConstructor
public class MesTerminalExecutionServiceImpl
        extends ServiceImpl<MesTerminalExecutionCommandMapper, MesTerminalExecutionCommandEntity>
        implements MesTerminalExecutionService {

    private static final String VERSION = "MES_TERMINAL_EXECUTION_V1";
    private static final ObjectMapper COMMAND_JSON = new ObjectMapper();
    private static final List<String> ACTIONS = List.of("RECEIVE", "START", "PAUSE", "RESUME", "FINISH");
    private final MesActualFactoryAccessService factoryAccess;
    private final MesExecutionTaskLock taskLock;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesTaskArrangeService arrangements;
    private final MesExecutionTemplateService templates;
    private final MesTerminalExecutionGuard guard;
    private final PermissionService permissions;
    private final PlatformTransactionManager transactionManager;

    @Override
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') || @pms.hasPermission('mesTerminalExecution:operate')")
    public MesMobileExecutionDTO.View view(String arrangeId) {
        var arrange = scopedArrange(id(arrangeId), false);
        require(assigned(arrange), "当前用户不是派工指定执行员工");
        var view = new MesMobileExecutionDTO.View();
        view.setTenantId(text(arrange.getTenantId()));
        view.setFactoryCode(arrange.getFactoryCode());
        view.setActorUserId(text(SecurityUtils.getUser().getId()));
        view.setActorCode(SecurityUtils.getUser().getUsername());
        view.setArrangeId(text(arrange.getId()));
        view.setFacts(facts(arrange));
        view.setFactsFingerprint(factsFingerprint(arrange));
        var contract = templates.contract(arrange);
        view.setContractSnapshot(json(textualTree(BeanUtil.beanToMap(contract))));
        view.setActualWorksiteCode(Objects.requireNonNullElse(arrange.getActualWorksiteCode(), ""));
        List<String> codes = csv(arrange.getWorksiteCodes());
        List<String> names = csv(arrange.getWorksiteNames());
        List<MesMobileExecutionDTO.Worksite> candidates = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            var candidate = new MesMobileExecutionDTO.Worksite();
            candidate.setCode(codes.get(i));
            candidate.setName(i < names.size() ? names.get(i) : codes.get(i));
            candidates.add(candidate);
        }
        view.setWorksiteCandidates(candidates);
        var pending = pending(arrange);
        if (pending != null) {
            var occupancy = new MesMobileExecutionDTO.Pending();
            occupancy.setRequestId(pending.getRequestId());
            occupancy.setAction(pending.getAction());
            occupancy.setStatus(pending.getStatus());
            occupancy.setActorCode(pending.getActorCode());
            occupancy.setOwnedByCurrentActor(Objects.equals(pending.getActorId(), SecurityUtils.getUser().getId()));
            view.setPending(occupancy);
        }
        List<String> allowed = new ArrayList<>();
        Map<String, String> blocked = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            String reason = unavailable(arrange, action);
            if (pending != null) reason = "已有未决原号，请查询原回执，不得另号操作";
            if (reason == null) allowed.add(action);
            else blocked.put(action, reason);
        }
        view.setAllowedActions(allowed);
        view.setBlockedActions(blocked);
        return view;
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesTerminalExecution:operate')")
    public MesMobileExecutionDTO.Receipt command(MesMobileExecutionDTO.Command input) {
        validate(input);
        Identity owner = identity();
        assertExpectedOwner(input, owner);
        String snapshot = commandSnapshot(input);
        String fingerprint = sha(VERSION + "|" + snapshot);
        MesTerminalExecutionCommandEntity registered = transaction(() -> register(input, owner, snapshot, fingerprint));
        if (!"PREPARED".equals(registered.getStatus())) return receiptOf(registered);
        Claim claimed = transaction(() -> claim(input, owner, snapshot, fingerprint));
        if (!claimed.newClaim()) return receiptOf(claimed.command());

        AtomicBoolean remoteRisk = new AtomicBoolean(false);
        AtomicInteger completion = new AtomicInteger(-1);
        try {
            return receiptOf(transaction(() -> execute(input, owner, snapshot, fingerprint, remoteRisk, completion)));
        } catch (RuntimeException failure) {
            // afterCompletion(ROLLED_BACK)是确认本地回滚的证据，不用异常类型猜事务结果。
            boolean safeRollback = completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK
                    && !remoteRisk.get();
            String outcome = safeRollback ? "REJECTED" : "UNKNOWN";
            String message = safeRollback
                    ? "本次MES动作已确认本地回滚；独立质量任务/门禁观察可能已登记。原因：" + message(failure)
                    : "本次结果不明，禁止另号重试或按当前状态销账，请核对原号及远端事实。原因：" + message(failure);
            // 若失败登记也无法提交，仍留下EXECUTING；客户端保留完整原号继续只读查证。
            return receiptOf(transaction(() -> failure(input, owner, snapshot, fingerprint,
                    outcome, message, remoteRisk.get())));
        }
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public MesMobileExecutionDTO.Receipt receipt(String requestId) {
        requestId(requestId);
        Identity owner = identity();
        var row = baseMapper.permanent(owner.tenantId(), requestId);
        if (row == null) {
            var absent = new MesMobileExecutionDTO.Receipt();
            absent.setCommandVersion(VERSION);
            absent.setRequestId(requestId);
            absent.setTenantId(text(owner.tenantId()));
            absent.setFactoryCode(owner.factoryCode());
            absent.setActorUserId(text(owner.actorId()));
            absent.setActorCode(owner.actorCode());
            absent.setStatus("ABSENT");
            absent.setMessage("未找到原号不证明未投递；只允许完整原正文按原号登记，不得据当前状态另号执行");
            return absent;
        }
        assertOwner(row, owner);
        return receiptOf(row);
    }

    /**
     * 原号锁→真实工单锁→派工行→命令行。已登记的原回执优先，不重释当前模板。
     */
    private MesTerminalExecutionCommandEntity register(MesMobileExecutionDTO.Command input, Identity owner,
                                                       String snapshot, String fingerprint) {
        lockRequest(owner, input.getRequestId());
        var existing = baseMapper.permanent(owner.tenantId(), input.getRequestId());
        if (existing != null) {
            assertOriginal(existing, owner, input, snapshot, fingerprint);
            return existing;
        }
        var arrange = lockedArrange(id(input.getArrangeId()));
        guard.assertUnoccupied(arrange);
        require(assigned(arrange), "当前用户不是派工指定执行员工");
        var row = new MesTerminalExecutionCommandEntity().setFactoryCode(owner.factoryCode())
                .setRequestId(input.getRequestId()).setCommandVersion(VERSION)
                .setRequestFingerprint(fingerprint).setCommandSnapshot(snapshot)
                .setArrangeId(arrange.getId()).setArrangeCode(arrange.getCode()).setTaskCode(arrange.getTaskCode())
                .setAction(input.getAction()).setActorId(owner.actorId()).setActorCode(owner.actorCode())
                .setBeforeFactsSnapshot(json(facts(arrange)))
                .setBeforeFactsFingerprint(factsFingerprint(arrange)).setStatus("PREPARED")
                .setRemoteWritePossible(false).setRegisteredTime(now());
        row.setTenantId(owner.tenantId());
        row.setCreateBy(owner.actorCode());
        row.setDelFlag("0");
        require(baseMapper.insert(row) == 1, "移动原命令登记失败，禁止执行");
        return row;
    }

    /**
     * 占用独立提交；再次看到EXECUTING/UNKNOWN绝不执行。
     */
    private Claim claim(MesMobileExecutionDTO.Command input, Identity owner, String snapshot, String fingerprint) {
        lockRequest(owner, input.getRequestId());
        var arrange = lockedArrange(id(input.getArrangeId()));
        var row = baseMapper.locked(owner.tenantId(), input.getRequestId());
        assertOriginal(row, owner, input, snapshot, fingerprint);
        if (!"PREPARED".equals(row.getStatus())) return new Claim(row, false);
        try {
            assertPreconditions(row, arrange, input);
        } catch (CheckedException rejected) {
            resolve(row, "REJECTED", "执行前拒绝：" + message(rejected), false, null);
            return new Claim(row, false);
        }
        row.setStatus("EXECUTING").setClaimedTime(now());
        update(row);
        return new Claim(row, true);
    }

    /**
     * 原领域动作和APPLIED在同一MES事务中；当前状态相同不能充当原号回执。
     */
    private MesTerminalExecutionCommandEntity execute(MesMobileExecutionDTO.Command input, Identity owner,
                                                      String snapshot, String fingerprint, AtomicBoolean remoteRisk,
                                                      AtomicInteger completion) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                completion.set(status);
            }
        });
        lockRequest(owner, input.getRequestId());
        var arrange = lockedArrange(id(input.getArrangeId()));
        var row = baseMapper.locked(owner.tenantId(), input.getRequestId());
        assertOriginal(row, owner, input, snapshot, fingerprint);
        require("EXECUTING".equals(row.getStatus()), "原号占用已变化，禁止执行");
        assertPreconditions(row, arrange, input);
        return guard.execute(row, remoteRisk, () -> {
            switch (input.getAction()) {
                case "RECEIVE" -> arrangements.receive(arrange.getId(), input.getWorksiteCode());
                case "START", "RESUME" -> arrangements.start(arrange.getId());
                case "PAUSE" -> arrangements.pauseFromTerminal(arrange.getId(), input.getRequestId());
                case "FINISH" -> arrangements.finish(arrange.getId());
                default -> throw new CheckedException("不支持的移动执行动作");
            }
            var applied = scopedArrange(arrange.getId(), true);
            String target = switch (input.getAction()) {
                case "RECEIVE" -> "RECEIVE";
                case "START", "RESUME" -> "PRODUCT";
                case "PAUSE" -> "PAUSE";
                case "FINISH" -> "FINISH";
                default -> throw new CheckedException("未知动作");
            };
            require(target.equals(applied.getStatus()), "领域动作未保存精确目标状态");
            require(Objects.equals(input.getWorksiteCode(), Objects.requireNonNullElse(applied.getActualWorksiteCode(), "")),
                    "领域动作实际工位与原命令不一致");
            if (List.of("START", "RESUME").contains(input.getAction()))
                require(applied.getStartTime() != null, "开工缺少本地实际时刻");
            if ("FINISH".equals(input.getAction())) require(applied.getFinishTime() != null, "完工缺少本地实际时刻");
            resolve(row, "APPLIED", "本原命令及MES动作已同事务保存；不代表PLC物理确认", remoteRisk.get(), applied);
            return row;
        });
    }

    /**
     * 只更新仍EXECUTING的原号；已提交APPLIED保持原结果，绝不覆盖或倒推销账。
     */
    private MesTerminalExecutionCommandEntity failure(MesMobileExecutionDTO.Command input, Identity owner,
                                                      String snapshot, String fingerprint, String outcome,
                                                      String message, boolean remoteRisk) {
        lockRequest(owner, input.getRequestId());
        lockedArrange(id(input.getArrangeId()));
        var row = baseMapper.locked(owner.tenantId(), input.getRequestId());
        assertOriginal(row, owner, input, snapshot, fingerprint);
        if ("EXECUTING".equals(row.getStatus())) resolve(row, outcome, message, remoteRisk, null);
        return row;
    }

    private void assertPreconditions(MesTerminalExecutionCommandEntity row, MesTaskArrangeEntity arrange,
                                     MesMobileExecutionDTO.Command input) {
        require(Objects.equals(row.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(row.getArrangeCode(), arrange.getCode()), "派工实际归属变化，禁止执行");
        var occupancy = pending(arrange);
        require(occupancy != null && Objects.equals(occupancy.getId(), row.getId()), "原号未决占用不唯一");
        require(Objects.equals(input.getExpectedFingerprint(), row.getBeforeFactsFingerprint())
                        && Objects.equals(row.getBeforeFactsFingerprint(), factsFingerprint(arrange))
                        && Objects.equals(row.getBeforeFactsSnapshot(), json(facts(arrange))),
                "派工事实已经变化，请保留原号拒绝回执，再读取新事实发起新的操作");
        String reason = unavailable(arrange, input.getAction());
        require(reason == null, reason);
        if ("RECEIVE".equals(input.getAction())) {
            require(csv(arrange.getWorksiteCodes()).contains(input.getWorksiteCode()), "请选择原派工候选中的实际工位");
        } else {
            require(Objects.equals(input.getWorksiteCode(), Objects.requireNonNullElse(arrange.getActualWorksiteCode(), "")),
                    "实际工位已经变化，禁止按新工位重释原号");
            require("PAUSE".equals(input.getAction()) || StrUtil.isNotBlank(arrange.getActualWorksiteCode()),
                    "派工缺少实际工位，请先完成接收");
        }
    }

    private String unavailable(MesTaskArrangeEntity arrange, String action) {
        if (!permissions.hasPermission("mesTerminalExecution:operate")) return "缺少移动派工执行权限";
        if (!assigned(arrange)) return "当前实名不是派工指定执行员工";
        String permission = switch (action) {
            case "RECEIVE" -> "mesProduceTask:receive";
            case "START", "RESUME" -> "mesProduceTask:start";
            case "PAUSE" -> "mesProduceTask:pause";
            case "FINISH" -> "mesProduceTask:finish";
            default -> "";
        };
        if (!permissions.hasPermission(permission)) return "缺少该动作原生产权限";
        if (!"PAUSE".equals(action) && !Boolean.TRUE.equals(templates.contract(arrange).getMobileAllowed()))
            return "派工冻结模板未开放移动执行";
        String state = switch (action) {
            case "RECEIVE" -> "UN_RECEIVE";
            case "START" -> "RECEIVE";
            case "RESUME" -> "PAUSE";
            case "PAUSE", "FINISH" -> "PRODUCT";
            default -> "";
        };
        return state.equals(arrange.getStatus()) ? null : "当前派工状态不允许此动作（要求" + state + "）";
    }

    private MesTerminalExecutionCommandEntity pending(MesTaskArrangeEntity arrange) {
        var rows = baseMapper.pending(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getId());
        require(rows.size() <= 1, "同派工未决占用重复，禁止猜测执行");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MesTaskArrangeEntity lockedArrange(Long id) {
        var before = taskLock.arrange(id);
        var locked = scopedArrange(id, true);
        taskLock.assertArrange(before, locked);
        return locked;
    }

    private MesTaskArrangeEntity scopedArrange(Long id, boolean lock) {
        String factory = factoryAccess.require(lock ? "write" : "read");
        var query = new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        var row = arrangeMapper.selectOne(query);
        require(row != null, "当前租户工厂未找到精确派工任务");
        return row;
    }

    private void resolve(MesTerminalExecutionCommandEntity row, String status, String message,
                         boolean remoteRisk, MesTaskArrangeEntity after) {
        var result = new LinkedHashMap<String, Object>();
        result.put("requestId", row.getRequestId());
        result.put("tenantId", text(row.getTenantId()));
        result.put("factoryCode", row.getFactoryCode());
        result.put("actorUserId", text(row.getActorId()));
        result.put("arrangeId", text(row.getArrangeId()));
        result.put("action", row.getAction());
        result.put("beforeFingerprint", row.getBeforeFactsFingerprint());
        result.put("afterFingerprint", after == null ? null : factsFingerprint(after));
        result.put("facts", after == null ? null : facts(after));
        result.put("outcome", status);
        row.setStatus(status).setMessage(StrUtil.maxLength(message, 1000)).setRemoteWritePossible(remoteRisk)
                .setResultSnapshot(json(result)).setResolvedTime(now());
        update(row);
    }

    private void update(MesTerminalExecutionCommandEntity row) {
        require(baseMapper.updateById(row) == 1, "原回执保存失败，不得据当前状态销账");
    }

    /**
     * 完整原正文固定字段顺序，保留空工位和所有原因字符，拒绝静默裁剪。
     */
    private String commandSnapshot(MesMobileExecutionDTO.Command input) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", input.getRequestId());
        body.put("arrangeId", input.getArrangeId());
        body.put("action", input.getAction());
        body.put("expectedFingerprint", input.getExpectedFingerprint());
        body.put("expectedTenantId", input.getExpectedTenantId());
        body.put("expectedFactoryCode", input.getExpectedFactoryCode());
        body.put("expectedActorUserId", input.getExpectedActorUserId());
        body.put("worksiteCode", input.getWorksiteCode());
        body.put("reason", input.getReason());
        return json(body);
    }

    private Map<String, String> facts(MesTaskArrangeEntity arrange) {
        Map<String, String> result = new TreeMap<>();
        BeanUtil.beanToMap(arrange).forEach((key, value) -> result.put(key, scalar(value)));
        return result;
    }

    private String factsFingerprint(MesTaskArrangeEntity arrange) {
        return sha("MES_ARRANGE_FACTS_V1|" + json(facts(arrange)));
    }

    /**
     * 冻结契约内的所有数值转文本，数组及布尔语义保持，递归不损失数量。
     */
    private Object textualTree(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number || value instanceof TemporalAccessor) return scalar(value);
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), textualTree(item)));
            return result;
        }
        if (value instanceof Collection<?> list) return list.stream().map(this::textualTree).toList();
        return textualTree(BeanUtil.beanToMap(value));
    }

    private String scalar(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal number) return number.stripTrailingZeros().toPlainString();
        require(value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof TemporalAccessor, "派工事实出现未支持字段类型，禁止生成不完整指纹");
        return value.toString();
    }

    private Identity identity() {
        String factory = factoryAccess.require("write");
        var user = SecurityUtils.getUser();
        require(StrUtil.isNotBlank(user.getUsername()), "缺少实名账号");
        return new Identity(TenantContextHolder.getTenantId(), factory, user.getId(), user.getUsername());
    }

    private boolean assigned(MesTaskArrangeEntity arrange) {
        return SecurityUtils.getUser() != null && csv(arrange.getWorkerCode()).contains(SecurityUtils.getUser().getUsername());
    }

    private void assertExpectedOwner(MesMobileExecutionDTO.Command input, Identity owner) {
        require(Objects.equals(input.getExpectedTenantId(), text(owner.tenantId()))
                        && Objects.equals(input.getExpectedFactoryCode(), owner.factoryCode())
                        && Objects.equals(input.getExpectedActorUserId(), text(owner.actorId())),
                "登录租户、工厂或实名已变化，禁止投递旧页面命令");
    }

    private void assertOwner(MesTerminalExecutionCommandEntity row, Identity owner) {
        require(row != null && Objects.equals(row.getTenantId(), owner.tenantId())
                        && Objects.equals(row.getFactoryCode(), owner.factoryCode())
                        && Objects.equals(row.getActorId(), owner.actorId()) && Objects.equals(row.getActorCode(), owner.actorCode()),
                "原号不属于当前实名、租户及工厂，禁止串账");
    }

    private void assertOriginal(MesTerminalExecutionCommandEntity row, Identity owner,
                                MesMobileExecutionDTO.Command input, String snapshot, String fingerprint) {
        assertOwner(row, owner);
        require(VERSION.equals(row.getCommandVersion()) && Objects.equals(row.getCommandSnapshot(), snapshot)
                && Objects.equals(row.getRequestFingerprint(), fingerprint)
                && Objects.equals(row.getArrangeId(), id(input.getArrangeId()))
                && Objects.equals(row.getAction(), input.getAction()), "同一原号正文或版本不一致，禁止修改/覆盖原命令");
    }

    private void validate(MesMobileExecutionDTO.Command input) {
        require(input != null, "移动原命令不能为空");
        requestId(input.getRequestId());
        if ("PAUSE".equals(input.getAction())) {
            require(input.getRequestId().matches("[A-Za-z0-9_-]{16,64}"), "暂停原号须为16～64位字母、数字、下划线或短横线");
        }
        id(input.getArrangeId());
        id(input.getExpectedTenantId());
        id(input.getExpectedActorUserId());
        require(ACTIONS.contains(input.getAction()), "仅支持接收、开工、暂停、复工、完工");
        require(input.getExpectedFingerprint() != null && input.getExpectedFingerprint().matches("[0-9a-f]{64}"),
                "请先读取完整派工事实指纹");
        require(clean(input.getExpectedFactoryCode(), 100, false), "工厂编码为空、超长或包含控制字符");
        require(clean(input.getWorksiteCode(), 100, true), "实际工位超长或包含控制字符");
        require(clean(input.getReason(), 1000, false), "请输入1～1000字符原因，不含控制字符");
    }

    private boolean clean(String value, int limit, boolean blankAllowed) {
        return value != null && value.length() <= limit && (blankAllowed || StrUtil.isNotBlank(value))
                && value.chars().noneMatch(Character::isISOControl);
    }

    private void requestId(String value) {
        require(value != null && value.matches("[A-Za-z0-9_.:-]{1,64}"), "原请求号格式无效");
    }

    private Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "资源身份必须为完整十进制文本");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new CheckedException("资源身份超出服务端Long范围");
        }
    }

    private void lockRequest(Identity owner, String requestId) {
        baseMapper.lockRequest(JSONUtil.toJsonStr(List.of(VERSION, text(owner.tenantId()), requestId)));
    }

    /**
     * 无代理自调用问题；调用时没有持有外层业务事务，三个阶段各自提交。
     */
    private <T> T transaction(Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "移动原命令入口禁止嵌入外层事务，避免独立占用自阻塞");
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private MesMobileExecutionDTO.Receipt receiptOf(MesTerminalExecutionCommandEntity row) {
        var result = new MesMobileExecutionDTO.Receipt();
        result.setCommandVersion(row.getCommandVersion());
        result.setRequestId(row.getRequestId());
        result.setTenantId(text(row.getTenantId()));
        result.setFactoryCode(row.getFactoryCode());
        result.setActorUserId(text(row.getActorId()));
        result.setActorCode(row.getActorCode());
        result.setArrangeId(text(row.getArrangeId()));
        result.setAction(row.getAction());
        result.setRequestFingerprint(row.getRequestFingerprint());
        result.setCommandSnapshot(row.getCommandSnapshot());
        result.setBeforeFactsFingerprint(row.getBeforeFactsFingerprint());
        result.setStatus(row.getStatus());
        result.setResultSnapshot(row.getResultSnapshot());
        result.setMessage(row.getMessage());
        result.setRegisteredTime(text(row.getRegisteredTime()));
        result.setClaimedTime(text(row.getClaimedTime()));
        result.setResolvedTime(text(row.getResolvedTime()));
        return result;
    }

    private List<String> csv(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 标准紧凑JSON、明确保留null；与JSON.stringify同字段顺序，不采用可变全局日期/转义配置。
     */
    private String json(Object value) {
        try {
            return COMMAND_JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new CheckedException("无法冻结完整移动原正文或派工事实，禁止发送");
        }
    }

    /**
     * 只用JDK内置SHA-256，不为原号协议新增密码学依赖。
     */
    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK缺少标准SHA-256", failure);
        }
    }

    private String message(RuntimeException failure) {
        // 只交付业务异常说明，数据库/网络栈不泄漏给客户端。
        return failure instanceof CheckedException ? Objects.requireNonNullElse(failure.getMessage(), "业务校验未通过")
                : "执行或事务确认异常（" + failure.getClass().getSimpleName() + "）";
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Identity(Long tenantId, String factoryCode, Long actorId, String actorCode) {
    }

    private record Claim(MesTerminalExecutionCommandEntity command, boolean newClaim) {
    }
}
