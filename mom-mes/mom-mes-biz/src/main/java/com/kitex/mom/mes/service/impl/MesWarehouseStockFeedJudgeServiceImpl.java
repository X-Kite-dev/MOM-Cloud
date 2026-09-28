package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesMaterialReturnWithdrawDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.InOutEnum;
import com.kitex.mom.mes.enums.WarehouseOutBizTypeEnum;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.MesWarehouseStockFeedJudgeService;
import com.kitex.mom.wms.api.feign.RemoteInNoticeService;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 线边余料受控退库。
 *
 * <p>服务端从当前租户、实际工厂和精确库存组重新取证，不信任页面提交的数量及物料属性。
 * 申请冻结事实并锁定来源库存，异岗批准后以永久请求号通知WMS，再原子扣减线边库存。</p>
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseStockFeedJudgeServiceImpl
        extends ServiceImpl<MesWarehouseStockFeedJudgeMapper, MesWarehouseStockFeedJudgeEntity>
        implements MesWarehouseStockFeedJudgeService {

    private static final int MAX_SOURCE_ROWS = 5000;

    private final RemoteSeqConfigService remoteSeqConfigService;
    private final MesWarehouseLiveStockService liveStockService;
    private final MesWarehouseLiveStockMapper liveStockMapper;
    private final RemoteInNoticeService inNoticeService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesWarehousePickingBillMapper pickingBillMapper;
    private final MesMaterialReturnOperationMapper operationMapper;
    private final MesWarehouseInoutMapper inoutMapper;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<MesWarehouseStockFeedJudgeEntity> pageRequests(Page<MesWarehouseStockFeedJudgeEntity> page,
                                                                MesWarehouseStockFeedJudgeEntity query) {
        String factory = factoryAccess.require("read");
        LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity> wrapper = new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
                .eq(MesWarehouseStockFeedJudgeEntity::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(query.getStatus()), MesWarehouseStockFeedJudgeEntity::getStatus, query.getStatus())
                .like(StrUtil.isNotBlank(query.getCode()), MesWarehouseStockFeedJudgeEntity::getCode, query.getCode())
                .like(StrUtil.isNotBlank(query.getTaskCode()), MesWarehouseStockFeedJudgeEntity::getTaskCode, query.getTaskCode())
                .like(StrUtil.isNotBlank(query.getBillNo()), MesWarehouseStockFeedJudgeEntity::getBillNo, query.getBillNo())
                .like(StrUtil.isNotBlank(query.getMatCode()), MesWarehouseStockFeedJudgeEntity::getMatCode, query.getMatCode())
                .like(StrUtil.isNotBlank(query.getApplicantName()), MesWarehouseStockFeedJudgeEntity::getApplicantName,
                        query.getApplicantName())
                .orderByDesc(MesWarehouseStockFeedJudgeEntity::getSubmittedTime)
                .orderByDesc(MesWarehouseStockFeedJudgeEntity::getId);
        return page(page, wrapper);
    }

    @Override
    @Transactional(readOnly = true)
    public MesWarehouseFeedDTO.Evidence preview(MesWarehouseFeedDTO source) {
        String factory = factoryAccess.require("read");
        validateSourceKey(source);
        EvidenceSnapshot snapshot = evidence(source, factory, null, false);
        MesWarehouseStockFeedJudgeEntity active = activeRequest(snapshot.source(), factory, false);
        List<String> blockers = new ArrayList<>(snapshot.blockers());
        if (active != null) {
            blockers.add("该实物库存组已有待审核退料申请[" + active.getCode() + "]");
        }
        return toEvidence(snapshot, active, blockers);
    }

    @Override
    @Transactional(readOnly = true)
    public MesWarehouseFeedDTO.Detail detail(Long id) {
        String factory = factoryAccess.require("read");
        MesWarehouseStockFeedJudgeEntity request = request(id, factory, false);
        MesWarehouseFeedDTO.Evidence current = null;
        if ("V2".equals(request.getWorkflowVersion())) {
            EvidenceSnapshot snapshot = evidence(sourceOf(request), factory,
                    "SUBMITTED".equals(request.getStatus()) ? request.getCode() : null, false);
            current = toEvidence(snapshot, activeRequest(snapshot.source(), factory, false), snapshot.blockers());
        }
        List<MesMaterialReturnOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<MesMaterialReturnOperationEntity>()
                        .eq(MesMaterialReturnOperationEntity::getReturnRequestId, request.getId())
                        .eq(MesMaterialReturnOperationEntity::getFactoryCode, factory)
                        .orderByAsc(MesMaterialReturnOperationEntity::getOperationTime)
                        .orderByAsc(MesMaterialReturnOperationEntity::getId));
        return new MesWarehouseFeedDTO.Detail().setRequest(request).setCurrentEvidence(current)
                .setOperations(operations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesWarehouseStockFeedJudgeEntity submitReturnMaterial(MesWarehouseFeedDTO command) {
        String factory = factoryAccess.require("write");
        validateSubmit(command);
        MomUser user = actor();
        String requestId = command.getRequestId().trim();
        String commandFingerprint = hash(Arrays.asList(sourceKey(command), decimal(command.getBackNum()),
                command.getExpectedEvidenceFingerprint(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + requestId);
        MesWarehouseStockFeedJudgeEntity replay = getOne(new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
                .eq(MesWarehouseStockFeedJudgeEntity::getRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory), "退料提交请求号已在其他工厂使用");
            require(Objects.equals(replay.getRequestFingerprint(), commandFingerprint),
                    "退料提交请求号已被不同命令使用");
            return replay;
        }

        baseMapper.lockKey("GROUP:" + scopedSourceKey(factory, command));
        EvidenceSnapshot snapshot = evidence(command, factory, null, true);
        require(snapshot.blockers().isEmpty(), String.join("；", snapshot.blockers()));
        require(command.getBackNum().compareTo(snapshot.available()) <= 0,
                "退料数量超过当前可退数量" + decimal(snapshot.available()));
        require(Objects.equals(command.getExpectedEvidenceFingerprint().trim(), snapshot.fingerprint()),
                "线边库存事实已变化，请重新预览后提交");
        require(activeRequest(snapshot.source(), factory, true) == null, "该库存组已有待审核退料申请");

        String code = Optional.ofNullable(remoteSeqConfigService.create("mes_stock_feed_back_judge"))
                .map(R::getData).map(String::valueOf).filter(StrUtil::isNotBlank)
                .orElseThrow(() -> new CheckedException("退料申请编号生成失败"));
        LocalDateTime now = LocalDateTime.now();
        MesWarehouseStockFeedJudgeEntity request = new MesWarehouseStockFeedJudgeEntity()
                .setWorkflowVersion("V2").setCode(code).setRequestId(requestId)
                .setRequestFingerprint(commandFingerprint).setEvidenceFingerprint(snapshot.fingerprint())
                .setEvidenceSnapshot(snapshot.json()).setStatus("SUBMITTED")
                .setNum(command.getBackNum()).setReason(command.getReason().trim())
                .setApplicantId(user.getId()).setApplicantCode(user.getUsername()).setApplicantName(user.getName())
                .setSubmittedTime(now).setFactoryCode(factory);
        copySource(snapshot.source(), request);
        require(save(request), "退料申请保存失败");
        int locked = lockStocks(snapshot.source(), code);
        require(locked == snapshot.rows().size(), "来源库存锁定数量不一致，请刷新后重试");
        saveOperation(request, requestId, commandFingerprint, "SUBMIT", null, "SUBMITTED",
                request.getReason(), user, now);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesWarehouseStockFeedJudgeEntity judge(MesWarehouseFeedJudgeDTO command) {
        String factory = factoryAccess.require("write");
        validateReview(command);
        MomUser user = actor();
        String operationRequestId = command.getRequestId().trim();
        String operationFingerprint = hash(Arrays.asList(command.getId(), command.getPass(),
                command.getExpectedEvidenceFingerprint(), command.getOpinion().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + operationRequestId);
        MesMaterialReturnOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesMaterialReturnOperationEntity>()
                        .eq(MesMaterialReturnOperationEntity::getRequestId, operationRequestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory), "退料审批请求号已在其他工厂使用");
            require(Objects.equals(replay.getRequestFingerprint(), operationFingerprint),
                    "退料审批请求号已被不同命令使用");
            return request(replay.getReturnRequestId(), factory, false);
        }

        MesWarehouseStockFeedJudgeEntity request = request(command.getId(), factory, true);
        require("V2".equals(request.getWorkflowVersion()), "历史退料单缺少完整冻结证据，不能按新流程审批");
        require("SUBMITTED".equals(request.getStatus()), "只有待审核退料申请可以审批");
        require(!Objects.equals(request.getApplicantId(), user.getId())
                        && !Objects.equals(request.getApplicantCode(), user.getUsername()),
                "退料申请人与审批人必须分离");
        require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "审批页面证据与原申请不一致，请刷新");

        baseMapper.lockKey("GROUP:" + scopedSourceKey(factory, sourceOf(request)));
        EvidenceSnapshot current = evidence(sourceOf(request), factory, request.getCode(), true);
        LocalDateTime now = LocalDateTime.now();
        if (!current.blockers().isEmpty() || !Objects.equals(request.getEvidenceFingerprint(), current.fingerprint())
                || request.getNum().compareTo(current.available()) > 0) {
            String message = current.blockers().isEmpty()
                    ? "来源库存数量或属性已变化，原退料申请失效" : String.join("；", current.blockers());
            finishReview(request, operationRequestId, operationFingerprint, false, command.getOpinion(), user,
                    now, "STALE", message);
            unlockStocks(sourceOf(request), request.getCode());
            saveOperation(request, operationRequestId, operationFingerprint, "MARK_STALE", "SUBMITTED", "STALE",
                    message, user, now);
            return request;
        }

        if (!Boolean.TRUE.equals(command.getPass())) {
            finishReview(request, operationRequestId, operationFingerprint, false, command.getOpinion(), user,
                    now, "REJECTED", "退料申请已驳回");
            unlockStocks(sourceOf(request), request.getCode());
            saveOperation(request, operationRequestId, operationFingerprint, "REJECT", "SUBMITTED", "REJECTED",
                    command.getOpinion().trim(), user, now);
            return request;
        }

        String wmsRequestId = "MES-MATERIAL-RETURN:" + request.getId();
        R<Boolean> result = inNoticeService.produceReturn(buildInNotice(request, wmsRequestId));
        require(result != null && result.isOk() && Boolean.TRUE.equals(result.getData()),
                "WMS未确认生产退料通知；结果不明时请使用新的审批操作号重新批准，WMS来源号保持幂等");
        request.setWmsRequestId(wmsRequestId).setWmsStatus("ACCEPTED")
                .setWmsReceiptStatus("WAITING").setWmsActualReceivedNum(BigDecimal.ZERO)
                .setWmsShortageNum(request.getNum()).setWmsOverageNum(BigDecimal.ZERO)
                .setReadinessFeedbackStatus("WAITING");
        deductLockedStocks(current.rows(), request);
        finishReview(request, operationRequestId, operationFingerprint, true, command.getOpinion(), user,
                now, "APPROVED", "WMS已接受退料通知，线边库存已转为待退实物");
        saveOperation(request, operationRequestId, operationFingerprint, "APPROVE", "SUBMITTED", "APPROVED",
                request.getResultMessage(), user, now);
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesWarehouseStockFeedJudgeEntity withdraw(MesMaterialReturnWithdrawDTO command) {
        String factory = factoryAccess.require("write");
        require(command != null && command.getId() != null && validRequestId(command.getRequestId())
                && validReason(command.getReason()), "撤回必须提供申请、永久请求号和1至500字原因");
        MomUser user = actor();
        String fingerprint = hash(Arrays.asList(command.getId(), command.getReason().trim(), user.getUsername()));
        baseMapper.lockKey("COMMAND:" + command.getRequestId().trim());
        MesMaterialReturnOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<MesMaterialReturnOperationEntity>()
                        .eq(MesMaterialReturnOperationEntity::getRequestId, command.getRequestId().trim()), false);
        if (replay != null) {
            require(Objects.equals(replay.getFactoryCode(), factory), "退料撤回请求号已在其他工厂使用");
            require(Objects.equals(replay.getRequestFingerprint(), fingerprint), "撤回请求号已被不同命令使用");
            return request(replay.getReturnRequestId(), factory, false);
        }
        MesWarehouseStockFeedJudgeEntity request = request(command.getId(), factory, true);
        require("V2".equals(request.getWorkflowVersion()) && "SUBMITTED".equals(request.getStatus()),
                "只有新流程待审核退料申请可以撤回");
        require(Objects.equals(request.getApplicantId(), user.getId())
                        && Objects.equals(request.getApplicantCode(), user.getUsername()),
                "只有原申请人可以撤回退料申请");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus("WITHDRAWN").setHandledTime(now).setResultMessage("申请人已撤回：" + command.getReason().trim());
        require(updateById(request), "退料申请撤回失败");
        unlockStocks(sourceOf(request), request.getCode());
        saveOperation(request, command.getRequestId().trim(), fingerprint, "WITHDRAW", "SUBMITTED", "WITHDRAWN",
                command.getReason().trim(), user, now);
        return request;
    }

    private EvidenceSnapshot evidence(MesWarehouseFeedDTO key, String factory, String ownLockCode, boolean lock) {
        Long tenantId = TenantContextHolder.getTenantId();
        LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper = stockGroup(key)
                .eq(MesWarehouseLiveStockEntity::getTenantId, tenantId)
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
                .eq(MesWarehouseLiveStockEntity::getMatDetailType, "RAW")
                .orderByAsc(MesWarehouseLiveStockEntity::getInTime)
                .orderByAsc(MesWarehouseLiveStockEntity::getId)
                .last(lock ? "LIMIT 5001 FOR UPDATE" : "LIMIT 5001");
        List<MesWarehouseLiveStockEntity> rows = liveStockService.list(wrapper);
        List<String> blockers = new ArrayList<>();
        if (rows.isEmpty()) {
            blockers.add("未找到当前租户和工厂下的精确线边实物库存");
        }
        if (rows.size() > MAX_SOURCE_ROWS) {
            blockers.add("来源库存超过5000行核验容量，须先合并或分批退料");
        }
        BigDecimal available = BigDecimal.ZERO;
        for (MesWarehouseLiveStockEntity row : rows) {
            if (row.getNum() == null || row.getNum().signum() <= 0) {
                blockers.add("库存行[" + row.getId() + "]数量无效");
            } else {
                available = available.add(row.getNum());
            }
            if (row.getQualityCaseId() != null) {
                blockers.add("库存行[" + row.getId() + "]处于独立质量隔离，须先完成质量处置");
            }
            if (StrUtil.isNotBlank(row.getStatus()) && !"0".equals(row.getStatus())) {
                blockers.add("库存行[" + row.getId() + "]状态不可退料：" + row.getStatus());
            }
            if (!returnableInspection(row.getInspectionStatus())) {
                blockers.add("库存行[" + row.getId() + "]质检状态不可退料：" + row.getInspectionStatus());
            }
            if (StrUtil.isNotBlank(row.getJudgeStatus()) && !Objects.equals(row.getJudgeStatus(), ownLockCode)) {
                blockers.add("库存行[" + row.getId() + "]已被其他退料申请锁定");
            }
        }
        MesWarehouseFeedDTO canonical = canonicalSource(key, rows, available);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", "MES_MATERIAL_RETURN_V2");
        facts.put("tenantId", tenantId);
        facts.put("factoryCode", factory);
        facts.put("group", sourceKey(canonical));
        facts.put("available", decimal(available));
        facts.put("rows", rows.stream().map(row -> Arrays.asList(row.getId(), decimal(row.getNum()),
                row.getStatus(), row.getQualityCaseId(), row.getQualityOriginCaseId(), row.getInspectionStatus(),
                value(row.getInTime()))).toList());
        String json = JSONUtil.toJsonStr(facts);
        return new EvidenceSnapshot(canonical, rows, available, blockers, hash(json), json);
    }

    private MesWarehouseFeedDTO canonicalSource(MesWarehouseFeedDTO requested,
                                                List<MesWarehouseLiveStockEntity> rows,
                                                BigDecimal available) {
        MesWarehouseFeedDTO result = new MesWarehouseFeedDTO();
        if (rows.isEmpty()) {
            result.setBillNo(requested.getBillNo());
            result.setMatCode(requested.getMatCode());
            result.setReservationNo(requested.getReservationNo());
            result.setRequirementMatCode(requested.getRequirementMatCode());
            result.setWarehouseCode(requested.getWarehouseCode());
            result.setSiteCode(requested.getSiteCode());
            result.setMatBatch(requested.getMatBatch());
            result.setNum(available);
            return result;
        }
        MesWarehouseLiveStockEntity row = rows.get(0);
        result.setBillNo(row.getBillNo());
        result.setMatCode(row.getMatCode());
        result.setReservationNo(row.getReservationNo());
        result.setRequirementMatCode(row.getRequirementMatCode());
        result.setRequirementMatName(row.getRequirementMatName());
        result.setSubstituteFlag(row.getSubstituteFlag());
        result.setSubstituteRatio(row.getSubstituteRatio());
        result.setMatName(row.getMatName());
        result.setMatSpec(row.getMatSpec());
        result.setMatBatch(row.getMatBatch());
        result.setMatUnit(row.getMatUnit());
        result.setSupplierCode(row.getSupplierCode());
        result.setSupplierName(row.getSupplierName());
        result.setWarehouseCode(row.getWarehouseCode());
        result.setWarehouseName(row.getWarehouseName());
        result.setSiteCode(row.getSiteCode());
        result.setSiteName(row.getSiteName());
        result.setNum(available);
        MesWarehousePickingBillEntity pickingBill = pickingBillMapper.selectOne(
                new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
                        .eq(MesWarehousePickingBillEntity::getBillNo, row.getBillNo())
                        .eq(MesWarehousePickingBillEntity::getFactoryCode, row.getFactoryCode())
                        .last("LIMIT 1"));
        result.setTaskCode(pickingBill == null ? null : pickingBill.getTaskCode());
        result.setFactoryCode(row.getFactoryCode());
        return result;
    }

    private LambdaQueryWrapper<MesWarehouseLiveStockEntity> stockGroup(MesWarehouseFeedDTO key) {
        LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper = new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getBillNo, key.getBillNo().trim())
                .eq(MesWarehouseLiveStockEntity::getMatCode, key.getMatCode().trim())
                .eq(MesWarehouseLiveStockEntity::getWarehouseCode, key.getWarehouseCode().trim())
                .eq(MesWarehouseLiveStockEntity::getSiteCode, key.getSiteCode().trim())
                .eq(MesWarehouseLiveStockEntity::getMatBatch, key.getMatBatch().trim());
        nullableText(wrapper, MesWarehouseLiveStockEntity::getReservationNo, key.getReservationNo());
        nullableText(wrapper, MesWarehouseLiveStockEntity::getRequirementMatCode, key.getRequirementMatCode());
        return wrapper;
    }

    private void nullableText(LambdaQueryWrapper<MesWarehouseLiveStockEntity> wrapper,
                              SFunction<MesWarehouseLiveStockEntity, ?> column, String text) {
        if (StrUtil.isBlank(text)) {
            wrapper.and(q -> q.isNull(column).or().eq(column, ""));
        } else {
            wrapper.eq(column, text.trim());
        }
    }

    private int lockStocks(MesWarehouseFeedDTO source, String code) {
        return liveStockMapper.update(null, new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, source.getFactoryCode())
                .eq(MesWarehouseLiveStockEntity::getMatDetailType, "RAW")
                .and(q -> q.isNull(MesWarehouseLiveStockEntity::getJudgeStatus)
                        .or().eq(MesWarehouseLiveStockEntity::getJudgeStatus, ""))
                .apply(groupSql(source)).set(MesWarehouseLiveStockEntity::getJudgeStatus, code));
    }

    private void unlockStocks(MesWarehouseFeedDTO source, String code) {
        liveStockService.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, source.getFactoryCode())
                .eq(MesWarehouseLiveStockEntity::getJudgeStatus, code)
                .apply(groupSql(source)).set(MesWarehouseLiveStockEntity::getJudgeStatus, null));
    }

    /**
     * 仅由服务端规范化后的值拼装等值条件；所有文本先转义单引号。
     */
    private String groupSql(MesWarehouseFeedDTO source) {
        return "bill_no='" + sql(source.getBillNo()) + "' AND mat_code='" + sql(source.getMatCode())
                + "' AND warehouse_code='" + sql(source.getWarehouseCode()) + "' AND site_code='" + sql(source.getSiteCode())
                + "' AND mat_batch='" + sql(source.getMatBatch()) + "' AND "
                + nullableSql("reservation_no", source.getReservationNo()) + " AND "
                + nullableSql("requirement_mat_code", source.getRequirementMatCode());
    }

    private String nullableSql(String column, String text) {
        return StrUtil.isBlank(text) ? "(" + column + " IS NULL OR " + column + "='')"
                : column + "='" + sql(text) + "'";
    }

    private String sql(String value) {
        return Objects.toString(value, "").trim().replace("'", "''");
    }

    private void deductLockedStocks(List<MesWarehouseLiveStockEntity> rows,
                                    MesWarehouseStockFeedJudgeEntity request) {
        BigDecimal remaining = request.getNum();
        for (MesWarehouseLiveStockEntity row : rows) {
            if (remaining.signum() <= 0) {
                break;
            }
            require(Objects.equals(row.getJudgeStatus(), request.getCode()), "来源库存锁不属于当前申请，禁止扣减");
            BigDecimal take = row.getNum().min(remaining);
            BigDecimal balance = row.getNum().subtract(take);
            boolean changed = liveStockService.update(new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                    .eq(MesWarehouseLiveStockEntity::getId, row.getId())
                    .eq(MesWarehouseLiveStockEntity::getNum, row.getNum())
                    .eq(MesWarehouseLiveStockEntity::getJudgeStatus, request.getCode())
                    .set(MesWarehouseLiveStockEntity::getNum, balance)
                    .set(MesWarehouseLiveStockEntity::getJudgeStatus, null)
                    .set(balance.signum() == 0, MesWarehouseLiveStockEntity::getDelFlag, "1"));
            require(changed, "线边库存已被并发修改，请核对WMS幂等通知后重试");
            MesWarehouseInoutEntity inout = new MesWarehouseInoutEntity();
            BeanUtil.copyProperties(row, inout, "id", "createBy", "createTime", "updateBy", "updateTime", "delFlag");
            inout.setBillId(request.getId());
            inout.setBillNo(request.getCode());
            inout.setResourceNo(row.getBillNo());
            inout.setOperateNum(take);
            inout.setOperateType(InOutEnum.OUT.getCode());
            inout.setBizType(WarehouseOutBizTypeEnum.PRODUCE_RETURN.getCode());
            inout.setSourceStockId(row.getId());
            inout.setSourceReportId(row.getSourceReportId());
            inout.setStockBeforeNum(row.getNum());
            inout.setStockAfterNum(balance);
            inout.setStockProofVersion("STOCK_LINE_V1");
            inout.setAllocationPolicy(JSONUtil.toJsonStr(Map.of(
                    "returnRequestId", request.getId(), "returnRequestNo", request.getCode(),
                    "wmsRequestId", Objects.toString(request.getWmsRequestId(), ""),
                    "evidenceFingerprint", request.getEvidenceFingerprint(), "allocation", "FIFO_IN_TIME_ID")));
            inout.setTenantId(row.getTenantId());
            inout.setFactoryCode(row.getFactoryCode());
            require(inoutMapper.insert(inout) == 1, "生产退料线边出库流水保存失败");
            remaining = remaining.subtract(take);
        }
        require(remaining.signum() == 0, "来源库存不足，禁止形成数量不守恒的退料通知");
        unlockStocks(sourceOf(request), request.getCode());
    }

    private WmsInNoticeEntity buildInNotice(MesWarehouseStockFeedJudgeEntity request, String wmsRequestId) {
        double noticeQuantity = request.getNum().doubleValue();
        require(BigDecimal.valueOf(noticeQuantity).compareTo(request.getNum()) == 0,
                "退料数量无法无损转换为WMS当前数量精度");
        WmsInNoticeDetailEntity detail = new WmsInNoticeDetailEntity();
        detail.setMatCode(request.getMatCode());
        detail.setReservationNo(request.getReservationNo());
        detail.setRequirementMatCode(request.getRequirementMatCode());
        detail.setRequirementMatName(request.getRequirementMatName());
        detail.setSubstituteFlag(request.getSubstituteFlag());
        detail.setSubstituteRatio(request.getSubstituteRatio());
        detail.setMatName(request.getMatName());
        detail.setMatBatch(request.getMatBatch());
        detail.setMatUnit(request.getMatUnit());
        detail.setWorkshop(request.getSiteCode());
        detail.setNoticeInNum(noticeQuantity);
        detail.setWarehouseCode(request.getWarehouseCode());
        detail.setWarehouseName(request.getWarehouseName());
        detail.setSupplierCode(request.getSupplierCode());
        detail.setSupplierName(request.getSupplierName());

        WmsInNoticeEntity notice = new WmsInNoticeEntity();
        notice.setSourceRequestId(wmsRequestId);
        notice.setDocumentId(request.getId());
        notice.setDocumentTable("mom_mes.mes_warehouse_stock_feed_judge");
        notice.setSupplierCode(request.getSupplierCode());
        notice.setWarehouseCode(request.getWarehouseCode());
        notice.setWarehouseName(request.getWarehouseName());
        notice.setWorkshop(request.getSiteCode());
        notice.setFactoryCode(request.getFactoryCode());
        notice.setRemark("生产余料退库申请" + request.getCode());
        notice.setDetailList(List.of(detail));
        return notice;
    }

    private void finishReview(MesWarehouseStockFeedJudgeEntity request, String operationRequestId,
                              String operationFingerprint, boolean approved, String opinion, MomUser user,
                              LocalDateTime now, String status, String message) {
        request.setReviewRequestId(operationRequestId).setReviewFingerprint(operationFingerprint)
                .setApproved(approved).setStatus(status).setOpinion(opinion.trim())
                .setReviewerId(user.getId()).setJudgePersonCode(user.getUsername()).setJudgePersonName(user.getName())
                .setJudgeTime(now).setHandledTime(now).setResultMessage(message);
        require(updateById(request), "退料审批结果保存失败");
    }

    private void copySource(MesWarehouseFeedDTO source, MesWarehouseStockFeedJudgeEntity target) {
        target.setBillNo(source.getBillNo()).setTaskCode(source.getTaskCode()).setMatCode(source.getMatCode())
                .setReservationNo(source.getReservationNo()).setRequirementMatCode(source.getRequirementMatCode())
                .setRequirementMatName(source.getRequirementMatName()).setSubstituteFlag(source.getSubstituteFlag())
                .setSubstituteRatio(source.getSubstituteRatio()).setMatName(source.getMatName()).setMatSpec(source.getMatSpec())
                .setMatUnit(source.getMatUnit()).setWarehouseCode(source.getWarehouseCode()).setWarehouseName(source.getWarehouseName())
                .setSiteCode(source.getSiteCode()).setSiteName(source.getSiteName()).setSupplierCode(source.getSupplierCode())
                .setSupplierName(source.getSupplierName()).setMatBatch(source.getMatBatch());
    }

    private MesWarehouseFeedDTO sourceOf(MesWarehouseStockFeedJudgeEntity request) {
        MesWarehouseFeedDTO source = new MesWarehouseFeedDTO();
        source.setBillNo(request.getBillNo());
        source.setMatCode(request.getMatCode());
        source.setReservationNo(request.getReservationNo());
        source.setRequirementMatCode(request.getRequirementMatCode());
        source.setWarehouseCode(request.getWarehouseCode());
        source.setSiteCode(request.getSiteCode());
        source.setMatBatch(request.getMatBatch());
        source.setFactoryCode(request.getFactoryCode());
        return source;
    }

    private MesWarehouseStockFeedJudgeEntity request(Long id, String factory, boolean lock) {
        require(id != null, "请选择退料申请");
        MesWarehouseStockFeedJudgeEntity request = getOne(new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
                .eq(MesWarehouseStockFeedJudgeEntity::getId, id)
                .eq(MesWarehouseStockFeedJudgeEntity::getFactoryCode, factory)
                .last(lock ? "FOR UPDATE" : ""), false);
        require(request != null, "当前租户和工厂下不存在该退料申请");
        return request;
    }

    private MesWarehouseStockFeedJudgeEntity activeRequest(MesWarehouseFeedDTO source, String factory, boolean lock) {
        LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity> wrapper = new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
                .eq(MesWarehouseStockFeedJudgeEntity::getFactoryCode, factory)
                .eq(MesWarehouseStockFeedJudgeEntity::getWorkflowVersion, "V2")
                .eq(MesWarehouseStockFeedJudgeEntity::getStatus, "SUBMITTED")
                .eq(MesWarehouseStockFeedJudgeEntity::getBillNo, source.getBillNo())
                .eq(MesWarehouseStockFeedJudgeEntity::getMatCode, source.getMatCode())
                .eq(MesWarehouseStockFeedJudgeEntity::getWarehouseCode, source.getWarehouseCode())
                .eq(MesWarehouseStockFeedJudgeEntity::getSiteCode, source.getSiteCode())
                .eq(MesWarehouseStockFeedJudgeEntity::getMatBatch, source.getMatBatch());
        nullableRequestText(wrapper, MesWarehouseStockFeedJudgeEntity::getReservationNo, source.getReservationNo());
        nullableRequestText(wrapper, MesWarehouseStockFeedJudgeEntity::getRequirementMatCode, source.getRequirementMatCode());
        return getOne(wrapper.orderByDesc(MesWarehouseStockFeedJudgeEntity::getId)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"), false);
    }

    private void nullableRequestText(LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity> wrapper,
                                     SFunction<MesWarehouseStockFeedJudgeEntity, ?> column, String text) {
        if (StrUtil.isBlank(text)) {
            wrapper.and(q -> q.isNull(column).or().eq(column, ""));
        } else {
            wrapper.eq(column, text.trim());
        }
    }

    private MesWarehouseFeedDTO.Evidence toEvidence(EvidenceSnapshot snapshot,
                                                    MesWarehouseStockFeedJudgeEntity active,
                                                    List<String> blockers) {
        return new MesWarehouseFeedDTO.Evidence().setSource(snapshot.source())
                .setEvidenceFingerprint(snapshot.fingerprint()).setEvidenceSnapshot(snapshot.json())
                .setAvailableQuantity(snapshot.available()).setEligible(blockers.isEmpty())
                .setBlockers(blockers).setActiveRequest(active);
    }

    private void saveOperation(MesWarehouseStockFeedJudgeEntity request, String requestId,
                               String fingerprint, String type, String before, String after,
                               String content, MomUser user, LocalDateTime time) {
        MesMaterialReturnOperationEntity operation = new MesMaterialReturnOperationEntity()
                .setReturnRequestId(request.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorId(user.getId()).setOperatorCode(user.getUsername()).setOperatorName(user.getName())
                .setOperationTime(time).setFactoryCode(request.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "退料操作履历保存失败");
    }

    private void validateSourceKey(MesWarehouseFeedDTO source) {
        require(source != null && StrUtil.isNotBlank(source.getBillNo()) && StrUtil.isNotBlank(source.getMatCode())
                        && StrUtil.isNotBlank(source.getWarehouseCode()) && StrUtil.isNotBlank(source.getSiteCode())
                        && StrUtil.isNotBlank(source.getMatBatch()),
                "退料必须选择精确派工、物料、仓库、线边库位和批次");
    }

    private void validateSubmit(MesWarehouseFeedDTO command) {
        validateSourceKey(command);
        require(command.getBackNum() != null && command.getBackNum().signum() > 0
                        && validRequestId(command.getRequestId()) && validFingerprint(command.getExpectedEvidenceFingerprint())
                        && validReason(command.getReason()),
                "退料提交必须提供正数量、永久请求号、当前事实指纹和1至500字原因");
    }

    private void validateReview(MesWarehouseFeedJudgeDTO command) {
        require(command != null && command.getId() != null && command.getPass() != null
                        && validRequestId(command.getRequestId()) && validFingerprint(command.getExpectedEvidenceFingerprint())
                        && validReason(command.getOpinion()),
                "退料审批必须提供申请、永久请求号、原事实指纹、结论和1至500字意见");
    }

    private String sourceKey(MesWarehouseFeedDTO source) {
        return String.join("|", norm(source.getBillNo()), norm(source.getMatCode()), norm(source.getReservationNo()),
                norm(source.getRequirementMatCode()), norm(source.getWarehouseCode()), norm(source.getSiteCode()),
                norm(source.getMatBatch()));
    }

    private String scopedSourceKey(String factory, MesWarehouseFeedDTO source) {
        return TenantContextHolder.getTenantId() + "|" + norm(factory) + "|" + sourceKey(source);
    }

    private boolean returnableInspection(String status) {
        return StrUtil.isBlank(status) || switch (status.trim().toUpperCase()) {
            case "NOT_REQUIRED", "RELEASED", "QUALIFIED", "RELEASE", "RECEIVE", "EXEMPTION" -> true;
            default -> false;
        };
    }

    private String norm(String value) {
        return Objects.toString(value, "").trim();
    }

    private boolean validRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9:_-]{16,100}");
    }

    private boolean validFingerprint(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private boolean validReason(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 500;
    }

    private String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String value(Object value) {
        return Objects.toString(value, "");
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername())
                && StrUtil.isNotBlank(user.getName()), "退料操作必须使用已维护姓名的实名账号");
        return user;
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSONUtil.toJsonStr(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("退料证据指纹生成失败", exception);
        }
    }

    private record EvidenceSnapshot(MesWarehouseFeedDTO source, List<MesWarehouseLiveStockEntity> rows,
                                    BigDecimal available, List<String> blockers,
                                    String fingerprint, String json) {
    }
}
