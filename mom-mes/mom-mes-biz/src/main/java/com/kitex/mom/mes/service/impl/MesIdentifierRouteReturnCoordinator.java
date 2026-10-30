package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesIdentifierRouteReturnCommand;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.util.MesRouteReturnNodeEvidence;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 逐标识退回上序协调服务。
 *
 * <p>本服务解决“在制品已经通过某工序，但因现场复核需要从较早工序重新执行”的场景。
 * 批准后生成标识级路线版本，后续只能按冻结片段逐站执行。该循环只记录追加工序事实，
 * 不重复增加生产计划完成量、不重复出入库。需要补料、处置不合格品或末工序成品回退时，
 * 必须使用现有QMS返工工单闭环。</p>
 */
@Service
@RequiredArgsConstructor
public class MesIdentifierRouteReturnCoordinator {

    public static final String NORMAL = "NORMAL";
    public static final String ROUTE_RETURN = "ROUTE_RETURN";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesIdentifierRouteReturnMapper returnMapper;
    private final MesIdentifierRouteOperationMapper operationMapper;
    private final MesTaskMapper taskMapper;
    private final MesProduceTaskMapper produceTaskMapper;
    private final MesTaskReportMapper reportMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final com.kitex.mom.mes.service.MesIdentifierBindingGroupService bindingGroups;
    private final MesExecutionTaskLock taskLock;

    /**
     * 查询指定标识当前路线事实及可退回目标，供管理端申请前核对。
     */
    public Map<String, Object> preview(String taskCode, String identifier) {
        String factory = factoryAccess.require("read");
        Basis basis = basis(factory, taskCode, identifier);
        MesIdentifierRouteReturnEntity active = active(factory, taskCode, basis.identifier(), false);
        List<Map<String, Object>> targets = basis.nodeEvidence() == null ? basis.passedRoutes().stream()
                .filter(route -> route.getRouteOrder() < basis.source().getRouteOrder()).map(this::routeMap).toList()
                : MesRouteReturnNodeEvidence.targets(basis.nodeEvidence()).stream()
                .map(MesRouteReturnNodeEvidence::routeMap).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", basis.task());
        result.put("identifier", basis.identifier());
        result.put("returnSchemaVersion", basis.nodeEvidence() == null ? null : MesRouteReturnNodeEvidence.VERSION);
        result.put("sourceRoute", displayRoute(basis, basis.source()));
        result.put("targetRoutes", targets);
        result.put("activeReturn", active);
        result.put("boundary", "仅在制品无补料工序复核；末工序产出回退、质量返工或补料必须走QMS返工工单");
        return result;
    }

    /**
     * 申请、批准、驳回和撤回，均保存永久幂等回执。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesIdentifierRouteReturnCommand command) {
        String factory = factoryAccess.require("write");
        MomUser actor = SecurityUtils.getUser();
        require(actor != null, "无法识别当前操作人");
        validateCommand(command);
        String operation = command.getOperation().toUpperCase();
        command.setOperation(operation);
        String fingerprint = commandFingerprint(command, actor.getId());
        returnMapper.lockBusinessKey("MES-IDENTIFIER-ROUTE:" + TenantContextHolder.getTenantId()
                + ":" + factory + ":" + command.getRequestId());
        MesIdentifierRouteOperationEntity old = operationMapper.selectOne(
                new LambdaQueryWrapper<MesIdentifierRouteOperationEntity>()
                        .eq(MesIdentifierRouteOperationEntity::getFactoryCode, factory)
                        .eq(MesIdentifierRouteOperationEntity::getRequestId, command.getRequestId()));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint), "请求号已被其他内容或人员使用");
            return result(returnMapper.selectById(old.getRouteReturnId()), old, true);
        }

        MesIdentifierRouteReturnEntity routeReturn;
        String before;
        String evidence;
        if ("APPLY".equals(operation)) {
            List<MesTaskEntity> owners = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesTaskEntity::getFactoryCode, factory)
                    .eq(MesTaskEntity::getCode, command.getTaskCode().trim()).last("LIMIT 2"));
            require(owners.size() == 1, "当前租户工厂未找到唯一生产工单");
            MesTaskEntity owner = owners.get(0);
            bindingGroups.lockDispatchTask(owner.getCode());
            Basis basis = basis(factory, owner.getCode(), command.getIdentifier());
            require(Objects.equals(owner.getId(), basis.task().getId()), "生产工单身份已变化，请原号重核");
            returnMapper.lockBusinessKey("MES-IDENTIFIER-ROUTE:" + TenantContextHolder.getTenantId()
                    + ":" + factory + ":" + basis.task().getCode() + ":" + basis.identifier());
            require(active(factory, basis.task().getCode(), basis.identifier(), false) == null,
                    "该标识已有待审或执行中的退回上序申请");
            MesProduceTaskEntity target;
            List<MesProduceTaskEntity> segment;
            if (basis.nodeEvidence() != null) {
                require(StrUtil.isNotBlank(command.getTargetProduceTaskCode())
                                && StrUtil.isNotBlank(command.getTargetRouteNodeCode()),
                        "节点退回须从新版履历选择实际目标任务和节点，不能按同名工序申请");
                target = MesRouteReturnNodeEvidence.byActualTask(basis.nodeEvidence(), command.getTargetProduceTaskCode());
                require(Objects.equals(target.getSourceRouteNodeCode(), command.getTargetRouteNodeCode())
                                && Objects.equals(target.getRouteCode(), command.getTargetRouteCode()),
                        "目标实际任务、稳定节点与主工序不一致");
                segment = MesRouteReturnNodeEvidence.segment(basis.nodeEvidence(), target);
            } else {
                require(command.getTargetProduceTaskCode() == null && command.getTargetRouteNodeCode() == null,
                        "历史执行合同不能夹带新节点申请字段");
                List<MesProduceTaskEntity> matches = basis.passedRoutes().stream()
                        .filter(route -> Objects.equals(route.getRouteCode(), command.getTargetRouteCode())).toList();
                require(matches.size() == 1, "目标工序不是唯一已通过的有效上序");
                target = matches.get(0);
                require(target.getRouteOrder() != null && target.getRouteOrder() < basis.source().getRouteOrder(),
                        "目标工序必须早于当前最近已过工序");
                segment = routeSegment(basis.routes(), target, basis.source());
            }
            int nextVersion = Objects.requireNonNullElse(returnMapper.selectList(
                            new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                                    .eq(MesIdentifierRouteReturnEntity::getFactoryCode, factory)
                                    .eq(MesIdentifierRouteReturnEntity::getTaskCode, basis.task().getCode())
                                    .eq(MesIdentifierRouteReturnEntity::getIdentifier, basis.identifier())
                                    .orderByDesc(MesIdentifierRouteReturnEntity::getVersionNo).last("LIMIT 1"))
                    .stream().findFirst().map(MesIdentifierRouteReturnEntity::getVersionNo).orElse(1), 1) + 1;
            routeReturn = new MesIdentifierRouteReturnEntity()
                    .setReturnNo("IRR-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                            + "-" + command.getRequestId().substring(0, Math.min(8, command.getRequestId().length())))
                    .setTaskCode(basis.task().getCode()).setIdentifier(basis.identifier()).setVersionNo(nextVersion)
                    .setSourceRouteCode(basis.source().getRouteCode()).setSourceRouteName(basis.source().getRouteName())
                    .setSourceRouteOrder(basis.source().getRouteOrder())
                    .setTargetRouteCode(target.getRouteCode()).setTargetRouteName(target.getRouteName())
                    .setTargetRouteOrder(target.getRouteOrder())
                    .setResumeRouteCode(basis.source().getRouteCode()).setResumeRouteName(basis.source().getRouteName())
                    .setResumeRouteOrder(basis.source().getRouteOrder())
                    .setRouteSnapshot(basis.nodeEvidence() == null
                            ? JSONUtil.toJsonStr(segment.stream().map(this::routeMap).toList())
                            : MesRouteReturnNodeEvidence.json(segment.stream().map(MesRouteReturnNodeEvidence::routeMap).toList()))
                    .setSourceFingerprint(basis.fingerprint()).setStatus("PENDING").setReason(command.getReason())
                    .setApplicantId(actor.getId()).setApplicantCode(actor.getUsername()).setApplicantName(actor.getName())
                    .setAppliedTime(LocalDateTime.now()).setFactoryCode(factory);
            if (basis.nodeEvidence() != null) {
                routeReturn.setReturnSchemaVersion(MesRouteReturnNodeEvidence.VERSION)
                        .setSourceSnapshot(basis.nodeEvidence().sourceSnapshot())
                        .setSourceProduceTaskCode(basis.source().getCode()).setSourceRouteNodeCode(basis.source().getSourceRouteNodeCode())
                        .setTargetProduceTaskCode(target.getCode()).setTargetRouteNodeCode(target.getSourceRouteNodeCode())
                        .setResumeProduceTaskCode(basis.source().getCode()).setResumeRouteNodeCode(basis.source().getSourceRouteNodeCode());
            }
            require(returnMapper.insert(routeReturn) == 1, "退回上序申请保存失败");
            before = "NONE";
            evidence = JSONUtil.toJsonStr(Map.of("source", displayRoute(basis, basis.source()), "target", displayRoute(basis, target),
                    "routeSegment", segment.stream().map(route -> displayRoute(basis, route)).toList(), "sourceFingerprint", basis.fingerprint()));
        } else {
            MesIdentifierRouteReturnEntity owner = returnMapper.selectOne(new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                    .eq(MesIdentifierRouteReturnEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesIdentifierRouteReturnEntity::getFactoryCode, factory)
                    .eq(MesIdentifierRouteReturnEntity::getId, command.getRouteReturnId()));
            require(owner != null, "当前租户工厂未找到退回申请");
            bindingGroups.lockDispatchTask(owner.getTaskCode());
            routeReturn = returnMapper.selectOne(new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                    .eq(MesIdentifierRouteReturnEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesIdentifierRouteReturnEntity::getFactoryCode, factory)
                    .eq(MesIdentifierRouteReturnEntity::getId, command.getRouteReturnId()).last("FOR UPDATE"));
            require(routeReturn != null && "PENDING".equals(routeReturn.getStatus()), "申请不存在或已处理，请刷新后核对");
            taskLock.assertTaskCode(owner.getTaskCode(), routeReturn.getTaskCode());
            before = routeReturn.getStatus();
            if ("WITHDRAW".equals(operation)) {
                require(Objects.equals(routeReturn.getApplicantId(), actor.getId()), "仅原申请人可撤回待审申请");
                routeReturn.setStatus("WITHDRAWN");
            } else {
                require(!Objects.equals(routeReturn.getApplicantId(), actor.getId()), "退回上序必须由另一名授权人员复核");
                if ("APPROVE".equals(operation)) {
                    require(returnMapper.selectCount(new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                                    .eq(MesIdentifierRouteReturnEntity::getFactoryCode, factory)
                                    .eq(MesIdentifierRouteReturnEntity::getTaskCode, routeReturn.getTaskCode())
                                    .eq(MesIdentifierRouteReturnEntity::getIdentifier, routeReturn.getIdentifier())
                                    .in(MesIdentifierRouteReturnEntity::getStatus, "PENDING", "ACTIVE")
                                    .ne(MesIdentifierRouteReturnEntity::getId, routeReturn.getId())) == 0,
                            "该标识已有其他待审或执行中的申请");
                    require(routeReturn.getReturnSchemaVersion() == null
                                    || MesRouteReturnNodeEvidence.VERSION.equals(routeReturn.getReturnSchemaVersion()),
                            "未知退回批准协议，不能按旧合同执行");
                    Basis current = basis(factory, routeReturn.getTaskCode(), routeReturn.getIdentifier(),
                            routeReturn.getReturnSchemaVersion() == null);
                    require(Objects.equals(current.fingerprint(), routeReturn.getSourceFingerprint()),
                            "申请后的工序事实或路线已变化，旧申请失效，请驳回后重新申请");
                    if (routeReturn.getReturnSchemaVersion() != null) {
                        require(current.nodeEvidence() != null, "原节点申请的实际执行协议已变化");
                        MesRouteReturnNodeEvidence.assertFrozen(routeReturn, current.nodeEvidence());
                    }
                    routeReturn.setStatus("ACTIVE").setActivatedTime(LocalDateTime.now());
                } else {
                    routeReturn.setStatus("REJECTED");
                }
                routeReturn.setReviewerId(actor.getId()).setReviewerCode(actor.getUsername())
                        .setReviewerName(actor.getName()).setReviewReason(command.getReason())
                        .setReviewedTime(LocalDateTime.now());
            }
            require(returnMapper.updateById(routeReturn) == 1, "退回上序申请状态更新失败");
            evidence = JSONUtil.toJsonStr(Map.of("returnNo", routeReturn.getReturnNo(),
                    "sourceFingerprint", routeReturn.getSourceFingerprint(), "routeSnapshot", routeReturn.getRouteSnapshot()));
        }

        MesIdentifierRouteOperationEntity receipt = new MesIdentifierRouteOperationEntity()
                .setRouteReturnId(routeReturn.getId()).setRequestId(command.getRequestId())
                .setRequestFingerprint(fingerprint).setOperationType(operation).setBeforeStatus(before)
                .setAfterStatus(routeReturn.getStatus()).setOperatorId(actor.getId())
                .setOperatorCode(actor.getUsername()).setOperatorName(actor.getName()).setReason(command.getReason())
                .setEvidenceSnapshot(evidence).setOperatedTime(LocalDateTime.now()).setFactoryCode(factory);
        require(operationMapper.insert(receipt) == 1, "退回上序操作回执保存失败");
        return result(routeReturn, receipt, false);
    }

    /**
     * 在统一报工事务中解析当前标识是否进入退回路线，并锁定本次必须执行的工序。
     */
    public void prepareExecution(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                                 ReportModeEnum mode) {
        prepareExecution(command, arrange, task, mode, true);
    }

    /**
     * 预览只读路线、不取行锁；实际提交仍在报工事务中重新核对。
     */
    public void previewExecution(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                                 ReportModeEnum mode) {
        prepareExecution(command, arrange, task, mode, false);
    }

    private void prepareExecution(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskEntity task,
                                  ReportModeEnum mode, boolean lock) {
        command.setExecutionKind(NORMAL);
        command.setIdentifierRouteVersion(1);
        command.setRouteReturnId(null);
        if (StrUtil.isBlank(command.getTransferCode())) {
            return;
        }
        MesIdentifierRouteReturnEntity active = active(arrange.getFactoryCode(), task.getCode(),
                command.getTransferCode(), lock);
        require(active == null || !"PENDING".equals(active.getStatus())
                        || active.getReturnSchemaVersion() == null,
                "该标识有待审节点退回申请，请先批准、驳回或撤回原申请后报工");
        if (active == null || !"ACTIVE".equals(active.getStatus())) {
            return;
        }
        require(mode == ReportModeEnum.ONE_CODE || mode == ReportModeEnum.TRANSFER_CARD,
                "退回上序仅支持逐件条码和流转卡；数量/包装对象须拆分为可追溯标识或走返工工单");
        if (active.getReturnSchemaVersion() != null) {
            require(MesRouteReturnNodeEvidence.VERSION.equals(active.getReturnSchemaVersion()), "未知退回执行协议");
            Basis current = basis(arrange.getFactoryCode(), task.getCode(), command.getTransferCode());
            require(current.nodeEvidence() != null, "节点批准与实际工单执行协议不一致");
            current.nodeEvidence().graph().current(arrange);
            List<MesProduceTaskEntity> ready = MesRouteReturnNodeEvidence.ready(active, current.nodeEvidence(),
                    loopReports(active), arrangements(arrange.getFactoryCode(), task.getCode()));
            require(ready.stream().anyMatch(row -> Objects.equals(row.getCode(), arrange.getProduceTaskCode())),
                    "当前节点不在本轮允许重走的范围；允许节点：" + ready.stream()
                            .map(row -> row.getRouteName() + "[" + row.getSourceRouteNodeCode() + "/" + row.getCode() + "]")
                            .collect(Collectors.joining("、")));
            command.setExecutionKind(ROUTE_RETURN);
            command.setRouteReturnId(active.getId());
            command.setIdentifierRouteVersion(active.getVersionNo());
            return;
        }
        List<RouteNode> segment = nodes(active.getRouteSnapshot());
        List<MesTaskReportEntity> loopReports = reportMapper.selectList(
                effectiveReportScope(arrange.getFactoryCode(), task.getCode(), command.getTransferCode())
                        .eq(MesTaskReportEntity::getRouteReturnId, active.getId()));
        Set<String> passed = loopReports.stream().map(MesTaskReportEntity::getProduceTaskCode)
                .collect(Collectors.toSet());
        RouteNode expected = segment.stream().filter(node -> !passed.contains(node.produceTaskCode()))
                .findFirst().orElseThrow(() -> new CheckedException("退回路线已全部报工，等待系统闭环，请刷新后重试"));
        require(Objects.equals(expected.produceTaskCode(), arrange.getProduceTaskCode())
                        && Objects.equals(expected.routeCode(), arrange.getRouteCode()),
                "当前标识退回路线下一工序为【" + expected.routeName() + "】，禁止跳序或在原路线继续报工");
        command.setExecutionKind(ROUTE_RETURN);
        command.setRouteReturnId(active.getId());
        command.setIdentifierRouteVersion(active.getVersionNo());
    }

    /**
     * 恢复工序成功后，在同一事务内关闭本次标识路线版本。
     */
    public void completeIfResume(MesTaskReportVO command, MesTaskArrangeEntity arrange, MesTaskReportEntity report) {
        if (!ROUTE_RETURN.equals(command.getExecutionKind()) || command.getRouteReturnId() == null) {
            return;
        }
        MesIdentifierRouteReturnEntity routeReturn = returnMapper.selectOne(
                new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                        .eq(MesIdentifierRouteReturnEntity::getFactoryCode, arrange.getFactoryCode())
                        .eq(MesIdentifierRouteReturnEntity::getId, command.getRouteReturnId()).last("FOR UPDATE"));
        require(routeReturn != null && "ACTIVE".equals(routeReturn.getStatus()), "退回路线版本已失效，报工事务已回滚");
        if (routeReturn.getReturnSchemaVersion() != null) {
            require(MesRouteReturnNodeEvidence.VERSION.equals(routeReturn.getReturnSchemaVersion()), "未知退回完成协议");
            if (!Objects.equals(routeReturn.getResumeProduceTaskCode(), arrange.getProduceTaskCode())) return;
            require(Objects.equals(routeReturn.getResumeRouteNodeCode(), arrange.getSourceRouteNodeCode()),
                    "恢复实际任务与批准节点不一致，不能关闭退回版本");
            Basis current = basis(arrange.getFactoryCode(), routeReturn.getTaskCode(), routeReturn.getIdentifier());
            require(current.nodeEvidence() != null, "恢复节点缺少原完整执行图");
            current.nodeEvidence().graph().current(arrange);
            require(MesRouteReturnNodeEvidence.ready(routeReturn, current.nodeEvidence(), loopReports(routeReturn),
                            arrangements(arrange.getFactoryCode(), routeReturn.getTaskCode())).isEmpty(),
                    "批准片段尚未全部重走完成，不能提前恢复正常路线");
        } else if (!Objects.equals(routeReturn.getResumeRouteCode(), arrange.getRouteCode())) {
            return;
        }
        routeReturn.setStatus("COMPLETED").setCompletedTime(LocalDateTime.now()).setCompletedReportId(report.getId());
        require(returnMapper.updateById(routeReturn) == 1, "退回路线闭环失败，报工事务已回滚");
    }

    public MesIdentifierRouteReturnEntity active(String factory, String taskCode, String identifier, boolean lock) {
        LambdaQueryWrapper<MesIdentifierRouteReturnEntity> wrapper =
                new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                        .eq(MesIdentifierRouteReturnEntity::getFactoryCode, factory)
                        .eq(MesIdentifierRouteReturnEntity::getTaskCode, taskCode)
                        .eq(MesIdentifierRouteReturnEntity::getIdentifier, identifier)
                        .in(MesIdentifierRouteReturnEntity::getStatus, "PENDING", "ACTIVE")
                        .orderByDesc(MesIdentifierRouteReturnEntity::getVersionNo).last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1");
        return returnMapper.selectOne(wrapper);
    }

    public List<MesIdentifierRouteOperationEntity> operations(Long routeReturnId) {
        String factory = factoryAccess.require("read");
        return operationMapper.selectList(new LambdaQueryWrapper<MesIdentifierRouteOperationEntity>()
                .eq(MesIdentifierRouteOperationEntity::getFactoryCode, factory)
                .eq(MesIdentifierRouteOperationEntity::getRouteReturnId, routeReturnId)
                .orderByAsc(MesIdentifierRouteOperationEntity::getOperatedTime)
                .orderByAsc(MesIdentifierRouteOperationEntity::getId));
    }

    private Basis basis(String factory, String taskCode, String identifier) {
        return basis(factory, taskCode, identifier, false);
    }

    /**
     * 原待审申请只由其持久协议决定重采方式，部署不能强制改写旧SHA。
     */
    private Basis basis(String factory, String taskCode, String identifier, boolean legacyApproval) {
        require(StrUtil.isNotBlank(taskCode) && taskCode.trim().length() <= 100, "生产工单号不能为空或过长");
        require(StrUtil.isNotBlank(identifier) && identifier.trim().length() <= 200, "生产标识不能为空或过长");
        String normalized = identifier.trim();
        MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factory).eq(MesTaskEntity::getCode, taskCode.trim()));
        require(task != null, "当前工厂生产工单不存在");
        require(bindingGroups.taskGroups(task.getId()).isEmpty(),
                "工单已冻结动态换码区段，旧逐标识退回尚无实例生命周期补偿，不允许继承原占用重过站");
        List<MesProduceTaskEntity> routes = produceTaskMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getFactoryCode, factory).eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                .orderByAsc(MesProduceTaskEntity::getRouteOrder).orderByAsc(MesProduceTaskEntity::getId));
        require(routes.size() >= 2 && routes.size() <= 200, "生产工单须有2至200道可核验工序");
        require(routes.stream().allMatch(route -> route.getRouteOrder() != null && StrUtil.isNotBlank(route.getRouteCode())),
                "工序路线缺少编码或顺序，不能生成标识路线版本");
        Map<String, MesProduceTaskEntity> byTask = routes.stream().collect(Collectors.toMap(
                MesProduceTaskEntity::getCode, Function.identity(), (left, right) -> {
                    throw new CheckedException("工序任务编码重复，不能确定逐标识路线");
                }, LinkedHashMap::new));
        List<MesTaskReportEntity> reports = reportMapper.selectList(effectiveReportScope(factory, task.getCode(), normalized)
                .isNull(MesTaskReportEntity::getRouteReturnId).orderByAsc(MesTaskReportEntity::getReportTime));
        if (!legacyApproval && routes.stream().anyMatch(route -> route.getRouteExecutionSchemaVersion() != null)) {
            var evidence = MesRouteReturnNodeEvidence.capture(task, normalized, routes, reports,
                    arrangements(factory, task.getCode()));
            return new Basis(task, normalized, routes, evidence.passedRoutes(), evidence.source(),
                    evidence.fingerprint(), evidence);
        }
        List<MesProduceTaskEntity> passed = reports.stream().map(report -> byTask.get(report.getProduceTaskCode()))
                .filter(Objects::nonNull).distinct().sorted(Comparator.comparing(MesProduceTaskEntity::getRouteOrder)).toList();
        require(passed.size() >= 2, "该标识至少通过两道正常工序后才能退回上序");
        MesProduceTaskEntity source = passed.get(passed.size() - 1);
        require(!Boolean.TRUE.equals(source.getLastRoute()), "末工序产出已形成库存或质量事实，必须走报工冲销及正式返工工单");
        String fingerprint = hash(JSONUtil.toJsonStr(java.util.Arrays.asList(
                task.getId(), task.getCode(), task.getProcessRouteCode(), task.getProcessRouteSnapshot(),
                routes.stream().map(this::routeMap).toList(),
                reports.stream().map(report -> java.util.Arrays.asList(report.getId(), report.getProduceTaskCode(),
                        report.getReportNum(), report.getStatus())).toList())));
        return new Basis(task, normalized, routes, passed, source, fingerprint, null);
    }

    private List<MesTaskArrangeEntity> arrangements(String factory, String taskCode) {
        return arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getTaskCode, taskCode));
    }

    /**
     * 不隐藏本轮失效/越界报告；原批准不允许把异常履历忽略后重做。
     */
    private List<MesTaskReportEntity> loopReports(MesIdentifierRouteReturnEntity routeReturn) {
        return reportMapper.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, routeReturn.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, routeReturn.getFactoryCode())
                .eq(MesTaskReportEntity::getRouteReturnId, routeReturn.getId()));
    }

    private Map<String, Object> displayRoute(Basis basis, MesProduceTaskEntity route) {
        return basis.nodeEvidence() == null ? routeMap(route) : MesRouteReturnNodeEvidence.routeMap(route);
    }

    private List<MesProduceTaskEntity> routeSegment(List<MesProduceTaskEntity> routes,
                                                    MesProduceTaskEntity target, MesProduceTaskEntity source) {
        List<MesProduceTaskEntity> segment = routes.stream()
                .filter(route -> route.getRouteOrder() >= target.getRouteOrder()
                        && route.getRouteOrder() <= source.getRouteOrder())
                .sorted(Comparator.comparing(MesProduceTaskEntity::getRouteOrder)).toList();
        require(segment.size() >= 2, "退回路线片段至少包含目标和恢复工序");
        require(segment.stream().map(MesProduceTaskEntity::getRouteOrder).distinct().count() == segment.size(),
                "当前路线片段含并行或分支工序，不能猜测重过站顺序，请走专用返工路线");
        for (int index = 1; index < segment.size(); index++) {
            List<String> predecessors = split(segment.get(index).getPredecessorCodes());
            require(predecessors.contains(segment.get(index - 1).getRouteCode()),
                    "路线片段不是连续前后序关系，请配置专用返工路线");
        }
        return segment;
    }

    private LambdaQueryWrapper<MesTaskReportEntity> effectiveReportScope(String factory, String taskCode,
                                                                         String identifier) {
        return new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getFactoryCode, factory).eq(MesTaskReportEntity::getTaskCode, taskCode)
                .eq(MesTaskReportEntity::getTransferCode, identifier)
                .and(item -> item.isNull(MesTaskReportEntity::getStatus)
                        .or().ne(MesTaskReportEntity::getStatus, ReportStatusEnum.REVERSED.name()));
    }

    private List<RouteNode> nodes(String snapshot) {
        JSONArray array = JSONUtil.parseArray(snapshot);
        require(!array.isEmpty() && array.size() <= 200, "退回路线快照为空或超过安全上限");
        List<RouteNode> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            var item = array.getJSONObject(i);
            result.add(new RouteNode(item.getStr("produceTaskCode"), item.getStr("routeCode"),
                    item.getStr("routeName"), item.getInt("routeOrder")));
        }
        return result;
    }

    private Map<String, Object> routeMap(MesProduceTaskEntity route) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("produceTaskCode", route.getCode());
        map.put("routeCode", route.getRouteCode());
        map.put("routeName", route.getRouteName());
        map.put("routeOrder", route.getRouteOrder());
        map.put("predecessorCodes", split(route.getPredecessorCodes()));
        map.put("lastRoute", Boolean.TRUE.equals(route.getLastRoute()));
        return map;
    }

    private void validateCommand(MesIdentifierRouteReturnCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getRequestId().matches("[A-Za-z0-9_-]{8,64}"),
                "须提供8至64位字母、数字、横线或下划线组成的请求号");
        require(Set.of("APPLY", "APPROVE", "REJECT", "WITHDRAW").contains(
                Objects.toString(command.getOperation(), "").toUpperCase()), "不支持的退回上序操作");
        require(StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
                "须填写500字以内实际原因");
        command.setReason(command.getReason().trim());
        if ("APPLY".equalsIgnoreCase(command.getOperation())) {
            require(command.getRouteReturnId() == null && StrUtil.isNotBlank(command.getTaskCode())
                            && StrUtil.isNotBlank(command.getIdentifier()) && StrUtil.isNotBlank(command.getTargetRouteCode()),
                    "申请须明确生产工单、生产标识和目标上序");
        } else {
            require(command.getRouteReturnId() != null, "复核、驳回或撤回须引用申请记录");
            require(command.getTargetProduceTaskCode() == null && command.getTargetRouteNodeCode() == null,
                    "复核只引用原申请，不能重新指定目标节点");
        }
    }

    /**
     * 老请求仍按原七字段Bean序列化，新增DTO字段不参与旧幂等正文。
     * 新申请两个节点字段必须同时出现，首次实际协议在读取原工单后再次严格核验。
     */
    private String commandFingerprint(MesIdentifierRouteReturnCommand command, Long actorId) {
        if (command.getTargetProduceTaskCode() == null && command.getTargetRouteNodeCode() == null) {
            LegacyCommand original = new LegacyCommand();
            original.setRequestId(command.getRequestId());
            original.setRouteReturnId(command.getRouteReturnId());
            original.setTaskCode(command.getTaskCode());
            original.setIdentifier(command.getIdentifier());
            original.setTargetRouteCode(command.getTargetRouteCode());
            original.setReason(command.getReason());
            original.setOperation(command.getOperation());
            return hash(JSONUtil.toJsonStr(List.of(original, actorId)));
        }
        return hash(JSONUtil.toJsonStr(List.of(command, actorId)));
    }

    private List<String> split(String value) {
        if (StrUtil.isBlank(value)) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim)
                .filter(StrUtil::isNotBlank).distinct().toList();
    }

    private Map<String, Object> result(MesIdentifierRouteReturnEntity routeReturn,
                                       MesIdentifierRouteOperationEntity operation, boolean replayed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routeReturn", routeReturn);
        result.put("operation", operation);
        result.put("replayed", replayed);
        return result;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256算法", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Basis(MesTaskEntity task, String identifier, List<MesProduceTaskEntity> routes,
                         List<MesProduceTaskEntity> passedRoutes, MesProduceTaskEntity source,
                         String fingerprint, MesRouteReturnNodeEvidence.Evidence nodeEvidence) {
    }

    @Data
    private static class LegacyCommand {
        private String requestId;
        private Long routeReturnId;
        private String taskCode;
        private String identifier;
        private String targetRouteCode;
        private String reason;
        private String operation;
    }

    private record RouteNode(String produceTaskCode, String routeCode, String routeName, Integer routeOrder) {
    }
}
