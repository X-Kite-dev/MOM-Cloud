package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesOfflineReportPermitDTO;
import com.kitex.mom.mes.dto.MesOfflineReportReplayDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesOfflineReportPermitEntity;
import com.kitex.mom.mes.entity.MesOfflineReportReplayEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.mapper.MesOfflineReportPermitMapper;
import com.kitex.mom.mes.mapper.MesOfflineReportReplayMapper;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesOfflineReportPermitService;
import com.kitex.mom.mes.service.MesOfflineReportReplayService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.MesReportCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 移动端弱网报工授权和受控重放。
 *
 * <p>授权令牌使用256位安全随机数，数据库只保存摘要。重放时先校验实名、租户、实际工厂、
 * 授权范围、采集时间和冻结执行策略，再进入与在线报工完全相同的事务及门禁。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MesOfflineReportPermitServiceImpl
        extends ServiceImpl<MesOfflineReportPermitMapper, MesOfflineReportPermitEntity>
        implements MesOfflineReportPermitService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_BATCH_SIZE = 50;

    private final MesOfflineReportReplayService replayService;
    private final MesOfflineReportReplayMapper replayMapper;
    private final MesTaskArrangeService arrangeService;
    private final MesTaskReportService reportService;
    private final MesExecutionTemplateService executionTemplateService;
    private final MesActualFactoryAccessService factoryAccess;
    private final PlatformTransactionManager transactionManager;
    private final MesReportCommandService reportCommands;
    private final MesExecutionTaskLock taskLock;

    @Value("${mom.mes.offline-report.permit-minutes:480}")
    private int permitMinutes;

    @Override
    public MesOfflineReportPermitDTO issue(MesTaskArrangeEntity arrange, String submittedIdentifier,
                                           MesExecutionContractDTO contract) {
        MomUser user = requireUser();
        Long tenantId = requireTenant();
        String factory = factoryAccess.require("read");
        if (arrange == null || !Objects.equals(factory, arrange.getFactoryCode())
                || !Set.of(MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.FINISH.name())
                .contains(arrange.getStatus())) {
            throw new CheckedException("只有当前工厂生产中或已批准退回路线引用的原派工可以签发弱网报工授权");
        }
        if (!split(arrange.getWorkerCode()).contains(user.getUsername())) {
            throw new CheckedException("当前派工未分配给登录人员，不能签发弱网报工授权");
        }
        if (contract == null || !Boolean.TRUE.equals(contract.getMobileAllowed())
                || !contract.getAllowedTriggers().contains("MANUAL")) {
            throw new CheckedException("当前执行模板未开放移动端人工报工");
        }
        if (contract.getIdentifierNodePolicy() != null && !"NONE".equals(contract.getIdentifierNodePolicy().getAction())) {
            throw new CheckedException("动态换码没有离线实例凭证，不允许签发旧弱网授权");
        }
        String identifier = required(submittedIdentifier, "生产标识不能为空");
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        LocalDateTime now = LocalDateTime.now();
        List<String> worksites = split(arrange.getWorksiteCodes());
        if (worksites.isEmpty() && StrUtil.isNotBlank(arrange.getActualWorksiteCode())) {
            worksites = List.of(arrange.getActualWorksiteCode().trim());
        }
        String fingerprint = contractFingerprint(arrange, contract);
        MesOfflineReportPermitEntity permit = new MesOfflineReportPermitEntity()
                .setPermitNo("ORP-" + UUID.randomUUID().toString().replace("-", ""))
                .setTokenHash(sha256(token))
                .setFactoryCode(factory)
                .setOperatorId(user.getId())
                .setOperatorCode(user.getUsername())
                .setArrangeCode(arrange.getCode())
                .setTaskCode(arrange.getTaskCode())
                .setProduceTaskCode(arrange.getProduceTaskCode())
                .setSubmittedIdentifier(identifier)
                .setReportMode(contract.getReportMode())
                .setAllowedWorksiteCodes(String.join(",", worksites))
                .setContractFingerprint(fingerprint)
                .setIssuedAt(now)
                .setExpiresAt(now.plusMinutes(Math.max(15, Math.min(1440, permitMinutes))))
                .setMaxUseCount(1)
                .setUsedCount(0)
                .setStatus("ISSUED");
        permit.setTenantId(tenantId);
        if (!save(permit)) {
            throw new CheckedException("弱网报工授权保存失败，请重新扫描");
        }
        return new MesOfflineReportPermitDTO()
                .setPermitNo(permit.getPermitNo()).setPermitToken(token)
                .setIssuedAt(permit.getIssuedAt()).setExpiresAt(permit.getExpiresAt())
                .setArrangeCode(permit.getArrangeCode()).setTransferCode(identifier)
                .setAllowedWorksiteCodes(worksites).setContractFingerprint(fingerprint);
    }

    @Override
    public MesOfflineReportReplayDTO.BatchResult replay(MesOfflineReportReplayDTO.BatchRequest request) {
        List<MesOfflineReportReplayDTO.Command> commands = request == null || request.getCommands() == null
                ? Collections.emptyList() : request.getCommands();
        if (commands.isEmpty() || commands.size() > MAX_BATCH_SIZE) {
            throw new CheckedException("弱网报工每批必须包含1至50条命令");
        }
        List<MesOfflineReportReplayDTO.Result> results = new ArrayList<>();
        for (MesOfflineReportReplayDTO.Command command : commands) {
            results.add(replayOne(command));
        }
        int accepted = (int) results.stream().filter(item -> "ACCEPTED".equals(item.getStatus())).count();
        return new MesOfflineReportReplayDTO.BatchResult().setTotalCount(results.size())
                .setAcceptedCount(accepted).setRejectedCount(results.size() - accepted).setResults(results);
    }

    private MesOfflineReportReplayDTO.Result replayOne(MesOfflineReportReplayDTO.Command envelope) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return transaction.execute(status -> accept(envelope));
        } catch (Exception exception) {
            String message = StrUtil.subPre(StrUtil.blankToDefault(exception.getMessage(), "弱网报工重放失败"), 500);
            log.warn("弱网报工重放被拒绝, requestId={}, reason={}", requestId(envelope), message);
            try {
                transaction.executeWithoutResult(status -> recordRejected(envelope, message));
            } catch (Exception auditFailure) {
                log.error("弱网报工拒绝结果保存失败, requestId={}", requestId(envelope), auditFailure);
            }
            return new MesOfflineReportReplayDTO.Result().setRequestId(requestId(envelope))
                    .setPermitNo(envelope == null ? null : envelope.getPermitNo())
                    .setLocalSequence(envelope == null ? null : envelope.getLocalSequence())
                    .setStatus("REJECTED").setMessage(message);
        }
    }

    private MesOfflineReportReplayDTO.Result accept(MesOfflineReportReplayDTO.Command envelope) {
        MomUser user = requireUser();
        Long tenantId = requireTenant();
        String factory = factoryAccess.require("write");
        validateEnvelope(envelope);
        MesTaskReportVO command = envelope.getReport();
        String requestId = required(command.getRequestId(), "报工请求号不能为空");
        replayMapper.lockRequest(tenantId, factory, requestId);
        String fingerprint = commandFingerprint(envelope);
        // 原号advisory已串行化本入口；先只读确认成功，不能先锁重放/许可/派工行。
        MesOfflineReportReplayEntity existing = replayService.getOne(replayQuery(tenantId, factory, requestId), false);
        if (existing != null && "ACCEPTED".equals(existing.getStatus())) {
            if (!Objects.equals(existing.getCommandFingerprint(), fingerprint)
                    || !Objects.equals(existing.getOperatorId(), user.getId())
                    || !Objects.equals(existing.getOperatorCode(), user.getUsername())) {
                throw new CheckedException("同一报工请求号的重放载荷或实名发起人发生变化");
            }
            boolean completeOriginal = reportCommands.verifyAcceptedReplay(command, MesReportChannelEnum.MOBILE,
                    existing.getReportId());
            return result(existing, completeOriginal ? "该弱网报工已受理，完整原正文及原人员一致，本次为幂等确认"
                    : "该历史弱网报工已受理，按原凭证及原人员确认；未补造新版原正文");
        }
        if (existing != null && !Objects.equals(existing.getCommandFingerprint(), fingerprint)) {
            throw new CheckedException("同一报工请求号的重放载荷发生变化，请保留原命令并重新同步");
        }
        reportCommands.lockRequests(List.of(requestId));
        MesTaskArrangeEntity ownership = taskLock.arrange(command.getArrangeCode());
        // 工单锁已在任何行锁之前取得，统一报工的重入请求/工单锁不会反向等待。
        existing = replayService.getOne(replayQuery(tenantId, factory, requestId).last("FOR UPDATE"), false);
        if (existing != null && (!Objects.equals(existing.getCommandFingerprint(), fingerprint)
                || "ACCEPTED".equals(existing.getStatus()))) {
            throw new CheckedException("重放事实已变化，请保持原号查询原回执");
        }
        MesOfflineReportPermitEntity permit = getOne(new LambdaQueryWrapper<MesOfflineReportPermitEntity>()
                .eq(MesOfflineReportPermitEntity::getTenantId, tenantId)
                .eq(MesOfflineReportPermitEntity::getFactoryCode, factory)
                .eq(MesOfflineReportPermitEntity::getPermitNo, envelope.getPermitNo()).last("FOR UPDATE"), false);
        validatePermit(permit, envelope, command, user);
        MesTaskArrangeEntity arrange = arrangeService.getOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenantId)
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, permit.getArrangeCode()).last("FOR UPDATE"), false);
        if (arrange == null) {
            throw new CheckedException("授权派工不存在或不在当前工厂");
        }
        taskLock.assertArrange(ownership, arrange);
        MesExecutionContractDTO currentContract = executionTemplateService.contract(arrange);
        if (!"NONE".equals(currentContract.getIdentifierNodePolicy().getAction())) {
            throw new CheckedException("动态换码不接受旧弱网凭证，必须使用后续正式实例捕获协议");
        }
        if (!Objects.equals(permit.getContractFingerprint(), contractFingerprint(arrange, currentContract))) {
            throw new CheckedException("派工执行策略已变化，原离线授权失效，请联网重新扫描");
        }
        normalizeCommand(command, arrange, permit);
        reportService.reportMobile(command);
        MesTaskReportEntity report = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, tenantId)
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getRequestId, requestId), false);
        if (report == null) {
            throw new IllegalStateException("统一报工事务未返回可核验的报工事实");
        }
        LocalDateTime now = LocalDateTime.now();
        permit.setUsedCount(permit.getUsedCount() + 1).setLastUsedAt(now).setStatus("CONSUMED");
        if (!updateById(permit)) {
            throw new IllegalStateException("弱网报工授权消耗状态保存失败");
        }
        MesOfflineReportReplayEntity replay = existing == null ? new MesOfflineReportReplayEntity() : existing;
        replay.setTenantId(tenantId);
        replay.setFactoryCode(factory).setRequestId(requestId).setPermitId(permit.getId())
                .setPermitNo(permit.getPermitNo()).setLocalSequence(envelope.getLocalSequence())
                .setCapturedAt(envelope.getCapturedAt()).setReceivedAt(now).setCommandFingerprint(fingerprint)
                .setStatus("ACCEPTED").setAttemptCount(existing == null ? 1 : existing.getAttemptCount() + 1)
                .setFailureCode(null).setFailureMessage(null).setReportId(report.getId())
                .setOperatorId(user.getId()).setOperatorCode(user.getUsername()).setAcceptedAt(now);
        boolean saved = existing == null ? replayService.save(replay) : replayService.updateById(replay);
        if (!saved) {
            throw new IllegalStateException("弱网报工重放结果保存失败");
        }
        return result(replay, "弱网报工已受理并完成全部实时业务校验");
    }

    private void validatePermit(MesOfflineReportPermitEntity permit, MesOfflineReportReplayDTO.Command envelope,
                                MesTaskReportVO command, MomUser user) {
        if (permit == null) {
            throw new CheckedException("弱网报工授权不存在");
        }
        if (!constantEquals(permit.getTokenHash(), sha256(required(envelope.getPermitToken(), "授权令牌不能为空")))) {
            throw new CheckedException("弱网报工授权令牌无效");
        }
        if (!Objects.equals(permit.getOperatorId(), user.getId())
                || !Objects.equals(permit.getOperatorCode(), user.getUsername())) {
            throw new CheckedException("弱网报工授权不属于当前登录人员");
        }
        LocalDateTime now = LocalDateTime.now();
        if (permit.getExpiresAt() == null || now.isAfter(permit.getExpiresAt())) {
            throw new CheckedException("弱网报工授权已过期，请联网重新扫描");
        }
        if (!"ISSUED".equals(permit.getStatus()) || permit.getUsedCount() == null
                || permit.getMaxUseCount() == null || permit.getUsedCount() >= permit.getMaxUseCount()) {
            throw new CheckedException("弱网报工授权已被使用或撤销");
        }
        if (!Objects.equals(permit.getArrangeCode(), command.getArrangeCode())
                || !Objects.equals(permit.getSubmittedIdentifier(), StrUtil.trim(command.getTransferCode()))) {
            throw new CheckedException("报工命令超出授权的派工或生产标识范围");
        }
        List<String> worksites = split(permit.getAllowedWorksiteCodes());
        if (!worksites.isEmpty() && !worksites.contains(StrUtil.trim(command.getWorksiteCode()))) {
            throw new CheckedException("报工工位超出离线授权范围");
        }
        if (envelope.getCapturedAt().isBefore(permit.getIssuedAt().minusMinutes(5))
                || envelope.getCapturedAt().isAfter(permit.getExpiresAt())
                || envelope.getCapturedAt().isAfter(now.plusMinutes(5))) {
            throw new CheckedException("离线采集时间不在授权有效期内");
        }
    }

    private void validateEnvelope(MesOfflineReportReplayDTO.Command envelope) {
        if (envelope == null || envelope.getReport() == null) {
            throw new CheckedException("弱网报工命令不能为空");
        }
        // 包括旧ACCEPTED且尚无永久正文的早返；旧摘要未包含这些字段，不能静默忽略后确认成功。
        MesTaskReportVO report = envelope.getReport();
        if (report.getExternalIdentifier() != null || report.getExpectedBindingId() != null
                || report.getExpectedBindingVersion() != null) {
            throw new CheckedException("旧弱网协议不接受动态双码/实例输入，请保留原命令按原凭证核查");
        }
        required(envelope.getPermitNo(), "授权凭证号不能为空");
        if (envelope.getLocalSequence() == null || envelope.getLocalSequence() < 1
                || envelope.getCapturedAt() == null) {
            throw new CheckedException("本地序号和现场采集时间不能为空");
        }
        String requestId = required(envelope.getReport().getRequestId(), "报工请求号不能为空");
        if (requestId.length() > 64) {
            throw new CheckedException("报工请求号不能超过64位");
        }
    }

    private void normalizeCommand(MesTaskReportVO command, MesTaskArrangeEntity arrange,
                                  MesOfflineReportPermitEntity permit) {
        command.setArrangeCode(arrange.getCode());
        command.setTaskCode(arrange.getTaskCode());
        command.setProduceTaskCode(arrange.getProduceTaskCode());
        command.setMatCode(arrange.getMatCode());
        command.setMatName(arrange.getMatName());
        command.setMatSpec(arrange.getMatSpec());
        command.setNoticeNum(arrange.getNoticeNum());
        // 保留原扫描正文；validatePermit已验证trim后的同一标识，最终规范化由统一解析完成。
        command.setType("MANUAL");
    }

    private void recordRejected(MesOfflineReportReplayDTO.Command envelope, String message) {
        if (envelope == null || envelope.getReport() == null || StrUtil.isBlank(envelope.getReport().getRequestId())) {
            return;
        }
        MomUser user = requireUser();
        Long tenantId = requireTenant();
        String factory = factoryAccess.require("write");
        String requestId = StrUtil.subPre(envelope.getReport().getRequestId().trim(), 64);
        replayMapper.lockRequest(tenantId, factory, requestId);
        MesOfflineReportReplayEntity replay = replayService.getOne(replayQuery(tenantId, factory, requestId)
                .last("FOR UPDATE"), false);
        if (replay != null && "ACCEPTED".equals(replay.getStatus())) {
            return;
        }
        MesOfflineReportPermitEntity permit = StrUtil.isBlank(envelope.getPermitNo()) ? null
                : getOne(new LambdaQueryWrapper<MesOfflineReportPermitEntity>()
                .eq(MesOfflineReportPermitEntity::getTenantId, tenantId)
                .eq(MesOfflineReportPermitEntity::getFactoryCode, factory)
                .eq(MesOfflineReportPermitEntity::getPermitNo, envelope.getPermitNo()).last("FOR UPDATE"), false);
        boolean create = replay == null;
        if (create) {
            replay = new MesOfflineReportReplayEntity();
            replay.setTenantId(tenantId);
        }
        String incomingFingerprint = commandFingerprint(envelope);
        boolean payloadDrift = !create && !Objects.equals(replay.getCommandFingerprint(), incomingFingerprint);
        replay.setFactoryCode(factory).setRequestId(requestId).setReceivedAt(LocalDateTime.now())
                .setStatus("REJECTED").setAttemptCount(create ? 1 : replay.getAttemptCount() + 1)
                .setFailureCode(payloadDrift ? "IDEMPOTENCY_CONFLICT" : classifyFailure(message))
                .setFailureMessage(message)
                .setOperatorId(user.getId()).setOperatorCode(user.getUsername()).setAcceptedAt(null);
        if (!payloadDrift) {
            replay.setPermitId(permit == null ? null : permit.getId()).setPermitNo(envelope.getPermitNo())
                    .setLocalSequence(envelope.getLocalSequence()).setCapturedAt(envelope.getCapturedAt());
        }
        if (create) {
            replay.setCommandFingerprint(incomingFingerprint);
        }
        if (permit != null && "ISSUED".equals(permit.getStatus())
                && permit.getExpiresAt() != null && LocalDateTime.now().isAfter(permit.getExpiresAt())) {
            permit.setStatus("EXPIRED");
            updateById(permit);
        }
        if (create) {
            replayService.save(replay);
        } else {
            replayService.updateById(replay);
        }
    }

    private LambdaQueryWrapper<MesOfflineReportReplayEntity> replayQuery(Long tenantId, String factory,
                                                                         String requestId) {
        return new LambdaQueryWrapper<MesOfflineReportReplayEntity>()
                .eq(MesOfflineReportReplayEntity::getTenantId, tenantId)
                .eq(MesOfflineReportReplayEntity::getFactoryCode, factory)
                .eq(MesOfflineReportReplayEntity::getRequestId, requestId);
    }

    private String contractFingerprint(MesTaskArrangeEntity arrange, MesExecutionContractDTO contract) {
        return sha256(JSONUtil.toJsonStr(Arrays.asList("MES-OFFLINE-CONTRACT-V1", arrange.getCode(),
                arrange.getExecutionTemplateId(), arrange.getExecutionTemplateCode(), arrange.getExecutionTemplateVersion(),
                contract.getReportMode(), contract.getIdentifierResolveMode(), contract.getAllowedIdentifierTypes(),
                contract.getAllowedTriggers(), contract.getPartialReportAllowed(), contract.getOverReportAllowed(),
                contract.getOverReportRate(), contract.getMaxSingleQuantity(), contract.getPredecessorRequired(),
                contract.getMaterialReadyRequired(), contract.getQualityGateRequired(), contract.getWorksiteRequired(),
                contract.getDeviceRequired(), contract.getToolingRequired(), contract.getMobileAllowed())));
    }

    private String commandFingerprint(MesOfflineReportReplayDTO.Command envelope) {
        if (envelope == null || envelope.getReport() == null) {
            return sha256("EMPTY");
        }
        MesTaskReportVO command = envelope.getReport();
        return sha256(JSONUtil.toJsonStr(Arrays.asList("MES-OFFLINE-REPORT-V1", envelope.getPermitNo(),
                envelope.getLocalSequence(), envelope.getCapturedAt(), command.getRequestId(), command.getTransferCode(),
                command.getArrangeCode(), command.getTaskCode(), command.getProduceTaskCode(), command.getPassNum(),
                command.getWorksiteCode(), command.getDeviceCode(), command.getWrapRuleCode(), command.getWrapCode(),
                command.getCapacity(), command.getManualOverrideReason())));
    }

    private MesOfflineReportReplayDTO.Result result(MesOfflineReportReplayEntity replay, String message) {
        return new MesOfflineReportReplayDTO.Result().setRequestId(replay.getRequestId())
                .setPermitNo(replay.getPermitNo()).setLocalSequence(replay.getLocalSequence())
                .setStatus("ACCEPTED").setMessage(message).setReportId(replay.getReportId())
                .setAcceptedAt(replay.getAcceptedAt());
    }

    private MomUser requireUser() {
        MomUser user = SecurityUtils.getUser();
        if (user == null || user.getId() == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("弱网报工必须由实名登录人员执行");
        }
        return user;
    }

    private Long requireTenant() {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || TenantContextHolder.getTenantSkip()) {
            throw new CheckedException("弱网报工必须在实际租户上下文执行");
        }
        return tenantId;
    }

    private List<String> split(String csv) {
        if (StrUtil.isBlank(csv)) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private String required(String value, String message) {
        if (StrUtil.isBlank(value)) {
            throw new CheckedException(message);
        }
        return value.trim();
    }

    private String requestId(MesOfflineReportReplayDTO.Command envelope) {
        return envelope == null || envelope.getReport() == null ? null : envelope.getReport().getRequestId();
    }

    private String classifyFailure(String message) {
        if (message.contains("授权") || message.contains("令牌")) return "PERMIT_INVALID";
        if (message.contains("载荷") || message.contains("请求号")) return "IDEMPOTENCY_CONFLICT";
        if (message.contains("工厂") || message.contains("租户") || message.contains("登录")) return "ACCESS_DENIED";
        return "BUSINESS_REJECTED";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("弱网报工摘要算法不可用", exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
