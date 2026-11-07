package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.QmsInspectionPieceDTO.*;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.QmsInspectionPieceMapper;
import com.kitex.mom.qms.mapper.QmsInspectionPieceMapper.Row;
import com.kitex.mom.qms.service.QmsInspectionProcedureTaskDetailService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.*;

/** 单件完整采集的原子凭据；只登记人员声明的实物标识，不认证 MES SN 或首次资格。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionPieceService {
    private final QmsInspectionPieceMapper mapper;
    private final QmsInspectionHistorySupport access;
    private final QmsInspectionBasisService basis;
    private final QmsInspectionProcedureTaskDetailService details;

    public record Context(Row request, Row piece, Map<Long, Result> results, Receipt replay) {}

    private record Original(String tenantId, String factoryCode, String actorId, Command command) {}

    /** 兼容尚未迁移的旧库；部分迁移或准备函数失效必须停止，不能退回匿名采集。 */
    boolean installed() {
        int count = mapper.installedTables();
        if (count == 0) return false;
        require(count == 3 && Objects.equals(mapper.schemaVersion(), 1), "逐件检验结构未完整就绪，请完成数据库升级");
        return true;
    }

    private void ready() {
        require(installed(), "逐件检验结构尚未安装，请完成数据库升级");
    }

    void anonymous(QmsInspectionProcedureTaskEntity task) {
        if (installed()) require(count(task) == 0, "此任务已采用逐件全检，请从逐件检验入口继续");
    }

    private long count(QmsInspectionProcedureTaskEntity task) {
        return mapper.count(task.getId(), task.getTenantId(), task.getFactoryCode());
    }

    private Row scoped(QmsInspectionProcedureTaskEntity task) {
        var row = new Row();
        row.setId(IdWorker.getId());
        row.setTaskId(task.getId());
        row.setTenantId(task.getTenantId());
        row.setFactoryCode(task.getFactoryCode());
        row.setActorId(SecurityUtils.getUser().getId());
        row.setActorName(
                cn.hutool.core.util.StrUtil.blankToDefault(
                        SecurityUtils.getUser().getName(), SecurityUtils.getUser().getUsername()));
        row.setOccurredAt(System.currentTimeMillis());
        return row;
    }

    private Original original(Command command) {
        require(command != null, "请提供逐件检验原请求");
        id(command.taskId());
        require(
                command.requestId() != null && command.requestId().matches("[A-Za-z0-9_-]{16,80}"),
                "逐件检验请求号无效");
        require(
                command.identifier() != null
                        && !command.identifier().isBlank()
                        && command.identifier().equals(command.identifier().strip())
                        && command.identifier().length() <= 200
                        && command.identifier()
                                .codePoints()
                                .noneMatch(
                                        c ->
                                                Character.isISOControl(c)
                                                        || Character.getType(c)
                                                                == Character.FORMAT),
                "实物标识不得为空、超长或含边界空白和控制字符");
        require(
                command.results() != null
                        && !command.results().isEmpty()
                        && command.results().size() <= 500,
                "单件检验须填写1至500个完整项目");
        var unique = new HashSet<String>();
        for (var result : command.results()) {
            require(result != null, "逐件检验项目不能为空");
            id(result.detailId());
            require(
                    unique.add(result.detailId())
                            && (result.valueText() == null || result.valueText().length() <= 100),
                    "逐件检验项目重复或数值过长");
        }
        return new Original(
                String.valueOf(TenantContextHolder.getTenantId()),
                FactoryContextHolder.getFactoryCode(),
                String.valueOf(SecurityUtils.getUser().getId()),
                command);
    }

    static Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "逐件检验编号必须是正整数字符串");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("逐件检验编号超出范围", e);
        }
    }

    private Row existing(Original original) {
        var command = original.command();
        var row =
                mapper.request(
                        id(command.taskId()),
                        command.requestId(),
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        if (row != null) {
            verify(row);
            require(
                    row.getPayload().equals(QmsInspectionBasisJson.write(original)),
                    "该原请求号属于其他人员或原始内容不同，请核对原记录");
        }
        return row;
    }

    private void verify(Row row) {
        require(
                row.getPayload() != null
                        && Objects.equals(
                                QmsInspectionBasisJson.sha(row.getPayload()), row.getFingerprint()),
                "逐件检验原请求指纹不一致");
        var o = QmsInspectionBasisJson.read(row.getPayload(), Original.class);
        require(
                row.getPayload().equals(QmsInspectionBasisJson.write(o))
                        && Objects.equals(
                                o.tenantId(), String.valueOf(TenantContextHolder.getTenantId()))
                        && Objects.equals(o.factoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(
                                o.actorId(), String.valueOf(SecurityUtils.getUser().getId()))
                        && Objects.equals(o.command().taskId(), String.valueOf(row.getTaskId()))
                        && Objects.equals(o.command().requestId(), row.getRequestId())
                        && Objects.equals(row.getActorId(), SecurityUtils.getUser().getId())
                        && ("COMPLETED".equals(row.getState())
                                || "CANCELED".equals(row.getState())),
                "逐件检验原记录的组织、人员或状态不一致");
    }

    private Receipt receipt(Row row) {
        verify(row);
        return new Receipt(
                row.getState(),
                String.valueOf(row.getTenantId()),
                row.getFactoryCode(),
                String.valueOf(row.getActorId()),
                row.getFingerprint(),
                QmsInspectionBasisJson.read(row.getPayload(), Original.class).command(),
                row.getPieceId() == null ? null : String.valueOf(row.getPieceId()),
                String.valueOf(row.getOccurredAt()));
    }

    Context begin(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> actual,
            Command command) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "逐件采集必须与原检验事务一起提交");
        ready();
        var original = original(command);
        require(Objects.equals(task.getId(), id(command.taskId())), "逐件检验父任务不一致");
        var previous = existing(original);
        if (previous != null) return new Context(previous, null, Map.of(), receipt(previous));
        require(
                ("WAIT".equals(task.getStatus()) || "RUNNING".equals(task.getStatus()))
                        && (task.getResult() == null || task.getResult().isBlank()),
                "检验已结束，不能继续采集");
        var plan = basis.validateCurrent(task, actual);
        require(eligible(task, actual, plan), "逐件全检要求已冻结的完整全检方案（最多500项），请核对检验依据");
        require(
                mapper.anonymousCount(task.getId(), task.getTenantId(), task.getFactoryCode()) == 0,
                "此任务已有匿名样本，不能追补实物身份");
        require(count(task) < task.getNum(), "逐件采集已达到应检数量");
        require(
                mapper.identifierCount(
                                task.getId(),
                                task.getTenantId(),
                                task.getFactoryCode(),
                                command.identifier())
                        == 0,
                "此实物标识已采集，请查看原记录");
        var inputs = new HashMap<Long, Result>();
        command.results().forEach(v -> inputs.put(id(v.detailId()), v));
        require(inputs.size() == actual.size(), "单件必须完整填写全部冻结检验项目");
        for (var detail : actual) {
            require(inputs.containsKey(detail.getId()), "逐件检验项目不属于当前任务");
            QmsProcedureInspectionChecks.judge(detail, input(detail, inputs.get(detail.getId())));
        }
        var piece = scoped(task);
        piece.setIdentifier(command.identifier());
        piece.setBasisFingerprint(basis.currentFingerprint("PROCEDURE", task.getId()));
        var request = scoped(task);
        request.setRequestId(command.requestId());
        request.setPayload(QmsInspectionBasisJson.write(original));
        request.setFingerprint(QmsInspectionBasisJson.sha(request.getPayload()));
        request.setState("COMPLETED");
        request.setPieceId(piece.getId());
        require(mapper.insertPiece(piece) == 1, "逐件检验实物标识保存失败");
        require(mapper.insertSubmission(request) == 1, "逐件检验原请求保存失败");
        return new Context(request, piece, Map.copyOf(inputs), null);
    }

    static InspectionItemResultRecord input(
            QmsInspectionProcedureTaskDetailEntity detail, Result value) {
        var record = new InspectionItemResultRecord();
        if ("BOOL".equals(detail.getCheckType())) {
            require(value.valueText() == null && value.qualified() != null, "请选择明确的合格或不合格结果");
            return record.setQualified(value.qualified());
        }
        require(
                value.qualified() == null
                        && value.valueText() != null
                        && value.valueText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"),
                "请输入普通十进制检验值");
        var decimal = new BigDecimal(value.valueText());
        double number = decimal.doubleValue();
        require(
                Double.isFinite(number) && BigDecimal.valueOf(number).compareTo(decimal) == 0,
                "检验值超出当前数值精度，请核对原值");
        return record.setDoubleValue(number);
    }

    void attach(
            Context context,
            QmsInspectionProcedureTaskEntity task,
            QmsInspectionProcedureItemResultRecordEntity sample) {
        require(
                sample.getId() != null
                        && sample.getId() > 0
                        && Objects.equals(sample.getTaskId(), task.getId()),
                "实际检验样本编号缺失或任务不一致");
        var result = context.results().get(sample.getDetailId());
        require(result != null, "样本项目未绑定原请求");
        var row = scoped(task);
        row.setPieceId(context.piece().getId());
        row.setSubmissionId(context.request().getId());
        row.setDetailId(sample.getDetailId());
        row.setRecordId(sample.getId());
        row.setValueText(result.valueText());
        row.setQualified(sample.getQualified());
        require(mapper.insertSample(row) == 1, "实物与实际检验样本关联保存失败");
    }

    Receipt completed(Context context) {
        return context.replay() != null ? context.replay() : receipt(context.request());
    }

    @Transactional(readOnly = true)
    public Receipt request(String taskId, String requestId) {
        access.requireRead();
        ready();
        var task = parent(id(taskId), false);
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,80}"), "原请求号无效");
        var row =
                mapper.request(task.getId(), requestId, task.getTenantId(), task.getFactoryCode());
        return row == null
                ? new Receipt(
                        "NOT_FOUND",
                        String.valueOf(task.getTenantId()),
                        task.getFactoryCode(),
                        String.valueOf(SecurityUtils.getUser().getId()),
                        null,
                        null,
                        null,
                        null)
                : receipt(row);
    }

    /** 原号取消可处理未知请求及软删除任务，写入墓碑后晚到采集不能产生效果。 */
    @Transactional(rollbackFor = Exception.class)
    public Receipt cancel(Command command) {
        access.requireWrite();
        ready();
        var original = original(command);
        var task = parent(id(command.taskId()), true);
        var row = existing(original);
        if (row != null) return receipt(row);
        row = scoped(task);
        row.setRequestId(command.requestId());
        row.setPayload(QmsInspectionBasisJson.write(original));
        row.setFingerprint(QmsInspectionBasisJson.sha(row.getPayload()));
        row.setState("CANCELED");
        require(mapper.insertSubmission(row) == 1, "逐件检验原号取消保存失败");
        return receipt(row);
    }

    private QmsInspectionProcedureTaskEntity parent(Long id, boolean lock) {
        var task =
                lock
                        ? mapper.lockParent(
                                id,
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode())
                        : mapper.parent(
                                id,
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode());
        require(
                task != null
                        && Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                task.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "当前实际组织不存在此检验任务");
        return task;
    }

    private boolean eligible(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> actual,
            QmsFrozenInspectionPlan plan) {
        return plan != null
                && "FULL".equals(plan.method())
                && !actual.isEmpty()
                && actual.size() <= 500
                && task.getNum() != null
                && task.getNum() > 0
                && Objects.equals(task.getNum(), task.getInspectionNum())
                && actual.stream().allMatch(d -> Objects.equals(d.getTotalNum(), task.getNum()));
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View view(String taskId, int page) {
        access.requireRead();
        require(page >= 1 && page <= 100000, "逐件检验页码超出范围");
        var task = parent(id(taskId), false);
        boolean ready = installed();
        var actual =
                details.list(
                        new LambdaQueryWrapper<QmsInspectionProcedureTaskDetailEntity>()
                                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, task.getId())
                                .eq(
                                        QmsInspectionProcedureTaskDetailEntity::getTenantId,
                                        task.getTenantId())
                                .eq(
                                        QmsInspectionProcedureTaskDetailEntity::getFactoryCode,
                                        task.getFactoryCode())
                                .last("LIMIT 501"));
        var plan = actual.size() <= 500 ? basis.validateCurrent(task, actual) : null;
        String availability =
                !ready
                        ? "SCHEMA_UNAVAILABLE"
                        : !eligible(task, actual, plan)
                                ? "NOT_FULL"
                                : mapper.anonymousCount(
                                                        task.getId(),
                                                        task.getTenantId(),
                                                        task.getFactoryCode())
                                                > 0
                                        ? "ANONYMOUS_STARTED"
                                        : "AVAILABLE";
        var pieceRows =
                ready
                        ? mapper.pieces(
                                task.getId(),
                                task.getTenantId(),
                                task.getFactoryCode(),
                                (page - 1) * 20)
                        : List.<Row>of();
        var pieces = new ArrayList<Piece>();
        String currentFingerprint =
                pieceRows.isEmpty() ? null : basis.currentFingerprint("PROCEDURE", task.getId());
        for (var row : pieceRows) {
            require("AVAILABLE".equals(availability), "逐件记录与当前冻结全检依据不一致");
            pieces.add(verifiedPiece(task, row, actual, currentFingerprint));
        }
        return new View(
                availability,
                taskId,
                String.valueOf(task.getTenantId()),
                task.getFactoryCode(),
                task.getStatus(),
                task.getResult(),
                String.valueOf(task.getNum()),
                ready ? String.valueOf(count(task)) : "0",
                page,
                "AVAILABLE".equals(availability)
                        ? actual.stream()
                                .map(
                                        d ->
                                                new Detail(
                                                        String.valueOf(d.getId()),
                                                        d.getName(),
                                                        d.getDemand(),
                                                        d.getMethod(),
                                                        d.getPosition(),
                                                        d.getCheckType(),
                                                        d.getLowerLimit() == null
                                                                ? null
                                                                : d.getLowerLimit().toString(),
                                                        d.getUpperLimit() == null
                                                                ? null
                                                                : d.getUpperLimit().toString()))
                                .toList()
                        : List.of(),
                pieces,
                "OPERATOR_RECORDED",
                false);
    }

    record Checked(QmsInspectionProcedureTaskEntity task, Row row, Piece piece) {}

    /** 仅供已完成实际组织授权的QMS领域协作调用，不重建旧身份。 */
    Checked checked(Long taskId, Long pieceId) {
        access.requireScope();
        ready();
        var task = parent(taskId, false);
        require("0".equals(task.getDelFlag()), "逐件关联的检验任务已删除");
        var actual =
                details.list(
                        new LambdaQueryWrapper<QmsInspectionProcedureTaskDetailEntity>()
                                .eq(QmsInspectionProcedureTaskDetailEntity::getTaskId, taskId)
                                .eq(
                                        QmsInspectionProcedureTaskDetailEntity::getTenantId,
                                        task.getTenantId())
                                .eq(
                                        QmsInspectionProcedureTaskDetailEntity::getFactoryCode,
                                        task.getFactoryCode())
                                .last("LIMIT 501"));
        var plan = actual.size() <= 500 ? basis.validateCurrent(task, actual) : null;
        require(
                eligible(task, actual, plan)
                        && mapper.anonymousCount(taskId, task.getTenantId(), task.getFactoryCode())
                                == 0,
                "关联要求完整冻结的逐件全检，不能追补匿名身份");
        var row = mapper.piece(taskId, pieceId, task.getTenantId(), task.getFactoryCode());
        require(row != null, "指定实物不属于当前实际检验任务");
        return new Checked(
                task,
                row,
                verifiedPiece(task, row, actual, basis.currentFingerprint("PROCEDURE", taskId)));
    }

    private Piece verifiedPiece(
            QmsInspectionProcedureTaskEntity task,
            Row row,
            List<QmsInspectionProcedureTaskDetailEntity> actual,
            String currentFingerprint) {
        require(
                row.getId() != null
                        && row.getId() > 0
                        && Objects.equals(row.getTaskId(), task.getId())
                        && Objects.equals(row.getTenantId(), task.getTenantId())
                        && Objects.equals(row.getFactoryCode(), task.getFactoryCode())
                        && Objects.equals(row.getBasisFingerprint(), currentFingerprint),
                "逐件记录与当前冻结全检依据不一致");
        var detailIds =
                actual.stream()
                        .map(QmsInspectionProcedureTaskDetailEntity::getId)
                        .collect(java.util.stream.Collectors.toSet());
        var samples =
                mapper.samples(
                        task.getId(), row.getId(), task.getTenantId(), task.getFactoryCode());
        require(samples.size() == actual.size() && samples.size() <= 500, "逐件实物缺少完整实际检验样本，禁止推算判定");
        var linked = new HashSet<Long>();
        for (var sample : samples) {
            require(
                    detailIds.contains(sample.getDetailId())
                            && linked.add(sample.getDetailId())
                            && Objects.equals(sample.getActualTask(), task.getId())
                            && Objects.equals(sample.getActualDetail(), sample.getDetailId())
                            && Objects.equals(sample.getActualQualified(), sample.getQualified())
                            && sample.getQualified() != null
                            && (sample.getValueText() == null
                                    ? sample.getActualValue() == null
                                    : sample.getActualValue() != null
                                            && Double.compare(
                                                            sample.getActualValue(),
                                                            new BigDecimal(sample.getValueText())
                                                                    .doubleValue())
                                                    == 0),
                    "实物关联与原始实际样本不一致，请核对原记录");
        }
        return new Piece(
                String.valueOf(row.getId()),
                row.getIdentifier(),
                String.valueOf(row.getActorId()),
                row.getActorName(),
                String.valueOf(row.getOccurredAt()),
                samples.stream()
                        .map(
                                s ->
                                        new Sample(
                                                String.valueOf(s.getDetailId()),
                                                String.valueOf(s.getRecordId()),
                                                s.getValueText(),
                                                s.getQualified()))
                        .toList());
    }
}
