package com.kitex.mom.qms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsBadJudgeReq;
import com.kitex.mom.qms.dto.req.QmsBadVerifyReq;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsBadOperationEntity;
import com.kitex.mom.qms.mapper.QmsBadInfoMapper;
import com.kitex.mom.qms.service.QmsBadInfoDetailService;
import com.kitex.mom.qms.service.QmsBadInfoService;
import com.kitex.mom.qms.service.QmsBadOperationService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import com.kitex.mom.wms.dto.WmsSpecialReturnQualityDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NCR不合格品闭环：发现、库存隔离、MRB分类和处置、WMS执行、独立验证、关闭。
 */
@Service
@RequiredArgsConstructor
public class QmsBadInfoServiceImpl extends ServiceImpl<QmsBadInfoMapper, QmsBadInfoEntity>
        implements QmsBadInfoService {

    private static final String WAIT_JUDGE = "WAIT_JUDGE";
    private static final String WAIT_MRB = "WAIT_MRB";
    private static final String WAIT_WMS = "WAIT_WMS";
    private static final String WAIT_EXECUTION = "WAIT_EXECUTION";
    private static final String WAIT_VERIFY = "WAIT_VERIFY";
    private static final String CLOSED = "CLOSED";
    private static final Set<String> DISPOSITIONS = Set.of("SMASH", "RETURN", "RELEASE", "CONCESSION", "REWORK");
    private static final Set<String> TERMINAL_ACTIONS = Set.of("SMASH", "RETURN");

    private final ObjectMapper specialReturnJson;
    private final JdbcTemplate specialReturnJdbc;
    private final RemoteMaterialService remoteMaterialService;
    private final RemoteLiveStockService remoteLiveStockService;
    private final com.kitex.mom.mes.api.feign.RemoteMesInventoryQualityCaseService remoteMesQualityCases;
    private final com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService actualFactories;
    private final QmsBadInfoDetailService detailService;
    private final QmsBadOperationService operationService;
    private final QmsMesReceiptReconcileQueue mesReceiptQueue;
    private final QmsMrbService mrbService;
	private final QmsNcrReworkRepository ncrReworkLedger;
    @org.springframework.beans.factory.annotation.Value("${mom.quality-receipt-reconcile.read-secret:${MOM_MES_RECEIPT_READ_SECRET:}}")
    private String receiptReadSecret;

    @Override
	public Page<QmsBadInfoEntity> queryPage(Page<QmsBadInfoEntity> page, QmsBadInfoEntity query) {
		QueryWrapper<QmsBadInfoEntity> wrapper = QueryGenerator.initQueryWrapper(query);
		if (query != null && query.getResourceTaskId() != null
				&& com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(query.getResourceType())) {
			String factory = requireMesFactory();
			wrapper.lambda().eq(QmsBadInfoEntity::getFactoryCode, factory)
					.eq(QmsBadInfoEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
					.eq(QmsBadInfoEntity::getResourceType, query.getResourceType())
					.eq(QmsBadInfoEntity::getResourceTaskId, query.getResourceTaskId());
			page.setSize(Math.max(1, Math.min(page.getSize(), 50)));
		}
		// 混合来源列表也显式隔离特殊退回的实际组织；普通来源（含历史空来源）保持原查询条件。
		wrapper.lambda().and(scope -> scope.isNull(QmsBadInfoEntity::getResourceType)
				.or().ne(QmsBadInfoEntity::getResourceType, "WMS_SPECIAL_RETURN")
				.or(special -> special.eq(QmsBadInfoEntity::getResourceType, "WMS_SPECIAL_RETURN")
						.eq(QmsBadInfoEntity::getTenantId,
								com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
						.eq(QmsBadInfoEntity::getFactoryCode,
								com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode())));
		wrapper.lambda().orderByDesc(QmsBadInfoEntity::getCreateTime);
		return this.page(page, wrapper);
	}

    @Override
    public Page<QmsBadInfoDetailEntity> queryDetailPage(Page<QmsBadInfoDetailEntity> page,
                                                        QmsBadInfoDetailEntity query) {
        QueryWrapper<QmsBadInfoDetailEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().orderByDesc(QmsBadInfoDetailEntity::getCreateTime);
        return detailService.page(page, wrapper);
    }

    @Override
    public Map<String, Long> detailCount(QmsBadInfoDetailEntity query) {
        return detailService.list(new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                        .like(StrUtil.isNotBlank(query.getMatCode()), QmsBadInfoDetailEntity::getMatCode, query.getMatCode())
                        .like(StrUtil.isNotBlank(query.getMatName()), QmsBadInfoDetailEntity::getMatName, query.getMatName())
                        .eq(StrUtil.isNotBlank(query.getBadType()), QmsBadInfoDetailEntity::getBadType, query.getBadType())
                        .eq(StrUtil.isNotBlank(query.getResourceType()), QmsBadInfoDetailEntity::getResourceType, query.getResourceType()))
                .stream().collect(Collectors.groupingBy(QmsBadInfoDetailEntity::getStatus, Collectors.counting()));
    }

    @Override
    public QmsBadInfoDetailEntity queryDetailById(Long id) {
        return detailService.getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(QmsBadInfoEntity info) {
        require(info != null, "不合格品信息不能为空");
        require(!isSpecialReturn(info) && info.getWmsSpecialReturnTargetJson() == null,
                "特殊退回必须从专用原件选择入口登记，普通填报不能写入冻结来源");
        if (isMesCase(info)) {
            var source = mesSource(info.getResourceTaskId());
            // 外部传入的来源、数量、位置和进度均不能覆盖MES原始事实。
            var clean = new QmsBadInfoEntity().setResourceType(com.kitex.mom.mes.dto.MesInventoryQualityOrigin.resourceType(source)).setResourceTaskId(source.getId())
                    .setMatCode(source.getMatCode()).setMatName(source.getMatName()).setBatchNo(source.getMatBatch())
                    .setNum(source.getQuantity()).setQuantityUnit(source.getMatUnit()).setLocationCode(source.getLocationCode())
                    .setLocationName(source.getLocationName()).setFactoryCode(source.getFactoryCode()).setRequestId(com.kitex.mom.mes.dto.MesInventoryQualityOrigin.requestId(source));
            clean.setTenantId(source.getTenantId());
            info = clean;
            baseMapper.lockSource("QMS-MES-CASE:" + source.getTenantId() + ":" + source.getFactoryCode() + ":" + source.getId());
        }
        require(StrUtil.isNotBlank(info.getMatCode()), "物料编码不能为空");
        requireQuantity(info.getNum(), "不合格数量");
        info.setNum(info.getNum().stripTrailingZeros());
        info.setRequestId(StrUtil.blankToDefault(info.getRequestId(), sourceRequestId(info)));
        QmsBadInfoEntity existing = getOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
                .eq(QmsBadInfoEntity::getRequestId, info.getRequestId()), false);
        if (existing != null) {
            require(existing.getNum() != null && existing.getNum().compareTo(info.getNum()) == 0
                    && Objects.equals(existing.getMatCode(), info.getMatCode()) && Objects.equals(existing.getBatchNo(), info.getBatchNo())
                    && Objects.equals(existing.getResourceTaskId(), info.getResourceTaskId()) && Objects.equals(existing.getResourceType(), info.getResourceType())
                    && Objects.equals(existing.getSupplierCode(), info.getSupplierCode()) && Objects.equals(existing.getLocationCode(), info.getLocationCode())
                    && Objects.equals(existing.getContainerCode(), info.getContainerCode()), "NCR请求号已用于不同来源、范围或数量，不能当作重复成功");
            return;
        }

        if (StrUtil.isBlank(info.getMatModel())) {
            R<MdmMaterialEntity> material = remoteMaterialService.getDetails(info.getMatCode());
            if (material != null && material.getCode() == 0 && material.getData() != null) {
                info.setMatModel(material.getData().getSpecificationModel());
            }
        }
        MomUser user = currentUser();
        info.setNcrNo("NCR-" + IdUtil.getSnowflakeNextIdStr())
                .setUnJudgeNum(info.getNum()).setJudgedNum(BigDecimal.ZERO).setDisposedNum(BigDecimal.ZERO).setClosedNum(BigDecimal.ZERO)
                .setStatus(WAIT_JUDGE).setDetectedBy(userCode(user)).setDetectedByName(userName(user))
                .setDetectedTime(LocalDateTime.now());
        require(save(info), "NCR保存失败");
        var stored = getById(info.getId());
        require(stored != null && stored.getNum() != null && stored.getNum().compareTo(info.getNum()) == 0
                        && stored.getUnJudgeNum() != null && stored.getUnJudgeNum().compareTo(info.getNum()) == 0,
                "NCR数据库数量精度不匹配，请先执行累计升级，已回滚且未调用WMS");
        saveOperation(info, null, info.getRequestId(), "CREATE", null, WAIT_JUDGE,
                "发现不合格，数量=" + info.getNum());
        syncInitialHold(info, "WMS-HOLD:" + info.getNcrNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void judge(QmsBadJudgeReq req) {
        require(req != null && req.getId() != null, "NCR不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "判定请求号不能为空");
        requireQuantity(req.getNum(), "判定数量");
        req.setNum(req.getNum().stripTrailingZeros());
        require(StrUtil.isNotBlank(req.getBadType()) && StrUtil.isNotBlank(req.getBadReason()),
                "不合格分类和原因不能为空");
        if (hasOperation(req.getRequestId())) {
            assertSameClassification(req);
            return;
        }

        QmsBadInfoEntity info = lockInfo(req.getId());
        require(info != null, "NCR不存在");
        if (hasOperation(req.getRequestId())) {
            assertSameClassification(req);
            return;
        }
        if (isMesCase(info)) {
            var source = assertMesSource(info);
            require(!Boolean.TRUE.equals(source.getIndivisible()) || req.getNum().compareTo(info.getNum()) == 0, "标识型隔离案件须整件/整卡/整包分类处置");
        }
        require("SUCCESS".equals(info.getHoldSyncStatus()), "库存尚未成功隔离，禁止MRB判定");
        require(!CLOSED.equals(info.getStatus()), "NCR已关闭");
        require(req.getNum().compareTo(value(info.getUnJudgeNum())) <= 0, "此次判定数量大于未判定数量");

        QmsBadInfoDetailEntity detail = new QmsBadInfoDetailEntity();
        BeanUtil.copyProperties(info, detail);
        detail.setId(null);
        detail.setDetailNo("NCRD-" + IdUtil.getSnowflakeNextIdStr()).setRequestId(req.getRequestId())
                .setBadId(info.getId()).setJudgeNum(req.getNum()).setBadType(req.getBadType())
                .setBadTypeName(req.getBadTypeName()).setBadReason(req.getBadReason()).setStatus(WAIT_MRB);
        require(detailService.save(detail), "NCR分类保存失败");
        var storedDetail = detailService.getById(detail.getId());
        require(storedDetail != null && storedDetail.getJudgeNum() != null && storedDetail.getJudgeNum().compareTo(req.getNum()) == 0,
                "NCR分类数量在数据库发生舍入，已回滚，请核对升级脚本");
        String afterStatus = req.getNum().compareTo(value(info.getUnJudgeNum())) == 0 ? WAIT_MRB : WAIT_JUDGE;
        info.setUnJudgeNum(value(info.getUnJudgeNum()).subtract(req.getNum()))
                .setJudgedNum(value(info.getJudgedNum()).add(req.getNum())).setStatus(afterStatus);
        updateById(info);
        saveOperation(info, detail, req.getRequestId(), "CLASSIFY", WAIT_JUDGE, afterStatus,
                "MRB分类=" + req.getBadType() + "，数量=" + req.getNum() + "，原因=" + req.getBadReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
	public void handle(QmsBadHandleReq req) {
		validateHandle(req);
		// 与返工准备/落账共用detail锁；重放MRB也不得越过未知验证意图。
		QmsBadInfoDetailEntity detail = lockDetail(req.getDetailId());
		require(detail != null, "不合格明细不存在");
		assertNoPendingRework(detail);
		if (mrbService.replay(req)) {
			return;
		}
		if (hasOperation(req.getRequestId())) {
			require(req.getMrbProposalId() == null, "发布请求号已被其他业务使用，不能假定该会签已发布");
			var previous = operationService.getOne(
					new LambdaQueryWrapper<QmsBadOperationEntity>().eq(QmsBadOperationEntity::getRequestId,
									req.getRequestId())
							.eq(QmsBadOperationEntity::getDetailId, req.getDetailId())
							.eq(QmsBadOperationEntity::getOperationType, "MRB_DECIDE")
							.eq(QmsBadOperationEntity::getFactoryCode,
									com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()), false);
			require(previous != null, "原请求不是当前工厂、当前分类的MRB发布，不能当作重复成功");
			var owner = detailService.getOne(
					new LambdaQueryWrapper<QmsBadInfoDetailEntity>().eq(QmsBadInfoDetailEntity::getMesDecisionKey,
							req.getRequestId()), false);
			if (owner != null) {
				require(Objects.equals(owner.getId(), req.getDetailId()) &&
								Objects.equals(owner.getHandleResult(), req.getHandleResult())
								&& Objects.equals(owner.getHandleRemark(), req.getHandleRemark()) &&
								Objects.equals(owner.getHandleFile(), req.getHandleFile())
								&& Objects.equals(owner.getExpectedCompletionTime(), req.getExpectedCompletionTime())
								&& Boolean.TRUE.equals(owner.getMesConcessionUnrestricted()) ==
								Boolean.TRUE.equals(req.getMesConcessionUnrestricted())
								&& Objects.equals(owner.getHandlePersonId(), userCode(currentUser())),
						"MES决策请求号已用于不同内容或人员，请核对原决策");
			} else {
				var target = detailService.getById(req.getDetailId());
				require(target != null &&
								!com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(target.getResourceType())
								&& Objects.equals(target.getHandleResult(), req.getHandleResult()) &&
								Objects.equals(target.getHandleRemark(), req.getHandleRemark())
								&& Objects.equals(StrUtil.blankToDefault(target.getHandleFile(), null),
								StrUtil.blankToDefault(req.getHandleFile(), null))
								&& Objects.equals(target.getExpectedCompletionTime(), req.getExpectedCompletionTime())
								&& Objects.equals(target.getHandlePersonId(), userCode(currentUser())),
						"历史决策内容/人员已不匹配，请查询原操作证据，不当作重复发布成功");
			}
			return;
		}
		require(req.getExpectedCompletionTime().isAfter(LocalDateTime.now()), "预计完成时间必须晚于当前时间");
		if (mrbService.replay(req)) {
			return;
		}
		require(WAIT_MRB.equals(detail.getStatus()), "仅待MRB决策的明细允许处置");
		QmsBadInfoEntity info = lockInfo(detail.getBadId());
		require(info != null, "NCR不存在");
		require("SUCCESS".equals(info.getHoldSyncStatus()), "库存隔离未成功，禁止处置");
		mrbService.claim(req, info, detail);
		if (isMesCase(info)) {
			var source = assertMesSource(info);
			require(!Boolean.TRUE.equals(source.getIndivisible()) || detail.getJudgeNum().compareTo(info.getNum()) == 0,
					"历史标识型部分分类不允许执行，须先治理分类来源，不能伪造子标识");
			require(Set.of("RELEASE", "CONCESSION", "SMASH").contains(req.getHandleResult()),
					"MES案件当前支持放行、让步接收、报废；返工/退货需专用业务链，不借用WMS");
			require(req.getRequestId().matches("[A-Za-z0-9_-]{16,64}"), "MES决策请求号须为16至64位安全字符");
			require(!"CONCESSION".equals(req.getHandleResult()) || StrUtil.isNotBlank(req.getHandleFile()),
					"让步接收须填写可追溯授权依据/文件引用，不能仅口头放行");
			require(!"CONCESSION".equals(req.getHandleResult()) ||
							Boolean.TRUE.equals(req.getMesConcessionUnrestricted()),
					"当前仅支持无客户/订单/用途限制的内部让步授权；有范围限制的案件不能在此放行");
			var user = currentUser();
			detail.setMesDecisionKey(req.getRequestId()).setMesReceiptId(null).setMesExecutorCode(null)
					.setMesExecutionEvidence(null)
					.setMesDeciderUserId(user.getId())
					.setMesConcessionUnrestricted(Boolean.TRUE.equals(req.getMesConcessionUnrestricted()))
					.setHandleResult(req.getHandleResult()).setHandleRemark(req.getHandleRemark())
					.setHandleFile(req.getHandleFile())
					.setExpectedCompletionTime(req.getExpectedCompletionTime()).setHandlePersonId(userCode(user))
					.setHandlePersonName(userName(user)).setHandleTime(new Date())
					.setVerificationResult(null).setVerificationRemark(null).setVerifierCode(null).setVerifierName(null)
					.setVerificationTime(null)
					.setStatus(WAIT_EXECUTION);
			require(saveMrbDecision(detail), "MES案件MRB决策保存失败");
			require(detailService.update(
					new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QmsBadInfoDetailEntity>()
							.eq(QmsBadInfoDetailEntity::getId, detail.getId())
							.set(QmsBadInfoDetailEntity::getVerificationResult, null)
							.set(QmsBadInfoDetailEntity::getVerificationRemark, null)
							.set(QmsBadInfoDetailEntity::getVerifierCode, null)
							.set(QmsBadInfoDetailEntity::getVerifierName, null)
							.set(QmsBadInfoDetailEntity::getVerificationTime, null)), "旧验证展示清理失败");
			refreshHeader(info);
			saveOperation(info, detail, req.getRequestId(), "MRB_DECIDE", WAIT_MRB, WAIT_EXECUTION,
					"MES决策=" + req.getHandleResult() + "，数量=" + detail.getJudgeNum() + "，待独立现场执行；" +
							req.getHandleRemark());
			// 以数据库实际提交精度冻结（例如时间精度），不保存仅存在于请求对象中的尾数。
			var published = detailService.getById(detail.getId());
			require(published != null, "已发布MES决策不可读取");
			mesReceiptQueue.enqueue(info, published);
			return;
		}
		require(!"RETURN".equals(req.getHandleResult()) || "PURCHASE".equals(info.getResourceType()),
				"退货仅适用于采购检验不合格");

		MomUser user = currentUser();
		detail.setHandleResult(req.getHandleResult()).setHandleRemark(req.getHandleRemark())
				.setHandleFile(req.getHandleFile()).setExpectedCompletionTime(req.getExpectedCompletionTime())
				.setHandlePersonId(userCode(user)).setHandlePersonName(userName(user)).setHandleTime(new Date())
				.setVerificationResult(null).setVerificationRemark(null).setVerifierCode(null)
				.setVerifierName(null).setVerificationTime(null).setExecutionRef(null);
		SyncOutcome outcome = callWms(dispositionCommand(info, detail,
				dispositionRequestId(detail)));
		applyDispositionOutcome(detail, outcome);
		require(saveMrbDecision(detail), "MRB处置保存失败");
		info.setDisposedNum(value(info.getDisposedNum()).add(detail.getJudgeNum()));
		refreshHeader(info);
		saveOperation(info, detail, req.getRequestId(), "MRB_DECIDE", WAIT_MRB, detail.getStatus(),
				"处置=" + req.getHandleResult() + "，数量=" + detail.getJudgeNum()
						+ "，WMS=" + outcome.status() + "，说明=" + req.getHandleRemark());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void verify(QmsBadVerifyReq req) {
		require(req != null && req.getDetailId() != null, "不合格明细不能为空");
		require(StrUtil.isNotBlank(req.getRequestId()), "验证请求号不能为空");
		require("PASS".equals(req.getResult()) || "REJECT".equals(req.getResult()),
				"验证结果必须为PASS或REJECT");
		require(StrUtil.isNotBlank(req.getRemark()), "验证说明不能为空");
		QmsBadInfoDetailEntity detail = lockDetail(req.getDetailId());
		require(detail != null, "不合格明细不存在");
		QmsBadInfoEntity info = lockInfo(detail.getBadId());
		require(info != null, "NCR不存在");
		assertNoPendingRework(detail);
		// 必须早于hasOperation快捷返回；旧文本编号不能替代实作、独立复核和WMS永久终态。
		require(isMesCase(info) || !"REWORK".equals(detail.getHandleResult()),
				"仓储返工须使用原位返工完成/独立复核入口，旧验证不能办理REWORK");
		if (!com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(detail.getResourceType()) &&
				hasOperation(req.getRequestId())) {
			return;
		}
		require(Set.of(WAIT_WMS, WAIT_EXECUTION, WAIT_VERIFY).contains(detail.getStatus())
				|| com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(detail.getResourceType()) &&
				Set.of(CLOSED, WAIT_MRB).contains(detail.getStatus()), "当前状态不可验证");
		String beforeStatus = detail.getStatus();
		MomUser user = currentUser();
		require(!Objects.equals(detail.getHandlePersonId(), userCode(user)), "处置人与验证人必须独立");
		if (isMesCase(info)) {
			assertMesSource(info);
			require(Set.of(WAIT_VERIFY, CLOSED, WAIT_MRB).contains(detail.getStatus()) &&
							Objects.equals(req.getMesDecisionKey(), detail.getMesDecisionKey()),
					"MES案件须完成执行且当前决策版本一致才能验证；已结案仅可重放原验证");
			require(Boolean.TRUE.equals(req.getMesConfirmed()), "须确认已核对MES现场执行和独立验证证据");
			var command = new com.kitex.mom.mes.dto.MesCaseDispositionCommand();
			command.setDetailId(detail.getId());
			command.setDecisionKey(detail.getMesDecisionKey());
			command.setRequestId(req.getRequestId());
			command.setStage("VERIFY_" + req.getResult());
			command.setEvidence(req.getRemark());
			command.setConfirmed(true);
			var response = remoteMesQualityCases.execute(command);
			require(response != null && response.isOk() && response.getData() != null,
					"MES验证结果未确认，请同步永久回执或重试原请求");
			applyMesReceipt(info, detail, response.getData());
			return;
		}
		SyncOutcome current = syncDisposition(info, detail);
		if ("PASS".equals(req.getResult())) {
			require(!"SUPERSEDED".equals(current.status()),
					"原处置已被驳回并恢复隔离，请继续驳回完成原案落账，不能验证通过");
			verifyPass(detail, req, current);
			info.setClosedNum(value(info.getClosedNum()).add(detail.getJudgeNum()));
		} else {
			verifyReject(info, detail, req, current);
			BigDecimal remainingDisposed = value(info.getDisposedNum()).subtract(detail.getJudgeNum());
			require(remainingDisposed.signum() >= 0, "NCR已处置数量小于本次退回判定量，须核对数量账");
			info.setDisposedNum(remainingDisposed);
		}
		detail.setVerificationResult(req.getResult()).setVerificationRemark(req.getRemark())
				.setVerifierCode(userCode(user)).setVerifierName(userName(user))
				.setVerificationTime(LocalDateTime.now());
		detailService.updateById(detail);
		refreshHeader(info);
		saveOperation(info, detail, req.getRequestId(), "VERIFY_" + req.getResult(),
				beforeStatus, detail.getStatus(), "验证=" + req.getResult() + "，说明=" + req.getRemark());
	}

    @Override
    @Transactional(rollbackFor = Exception.class)
	public void retryHold(Long id) {
		QmsBadInfoEntity info = lockInfo(id);
		require(info != null, "NCR不存在");
		require(!"LEGACY_CLOSED".equals(info.getStatus()), "历史已关闭NCR无需重新隔离");
		if (isSpecialReturn(info) && "SUCCESS".equals(info.getHoldSyncStatus())) {
			specialReturnTarget(info);
			return;
		}
		require(!"SUCCESS".equals(info.getHoldSyncStatus()), "库存已经隔离成功，无需重试");
		syncInitialHold(info, "WMS-HOLD:" + info.getNcrNo());
	}

    @Override
    @Transactional(rollbackFor = Exception.class)
	public void retryDisposition(Long detailId) {
		QmsBadInfoDetailEntity detail = lockDetail(detailId);
		require(detail != null, "不合格明细不存在");
		assertNoPendingRework(detail);
		require(Set.of(WAIT_WMS, WAIT_EXECUTION).contains(detail.getStatus()), "当前处置无需重试");
		QmsBadInfoEntity info = lockInfo(detail.getBadId());
		String before = detail.getStatus();
		SyncOutcome outcome = syncDisposition(info, detail);
		applyDispositionOutcome(detail, outcome);
		detailService.updateById(detail);
		refreshHeader(info);
		saveOperation(info, detail, UUID.randomUUID().toString(), "WMS_RETRY", before, detail.getStatus(),
				"WMS重试结果=" + outcome.status() + "，" + outcome.message());
	}

    @Override
    public List<QmsBadOperationEntity> operations(Long id) {
        return operationService.list(new LambdaQueryWrapper<QmsBadOperationEntity>()
                .eq(QmsBadOperationEntity::getBadId, id)
                .orderByAsc(QmsBadOperationEntity::getOperationTime)
                .orderByAsc(QmsBadOperationEntity::getId));
    }

	private void syncInitialHold(QmsBadInfoEntity info, String requestId) {
		if (isMesCase(info)) {
			var c = assertMesSource(info);
			info.setHoldOperationId(com.kitex.mom.mes.dto.MesInventoryQualityOrigin.initialProofId(c))
					.setHoldNo("MES-QC-" + c.getId()).setHoldSyncStatus("SUCCESS")
					.setHoldSyncMessage("已核对MES独立案件、剩余隔离库存和处置数量账；原始隔离事实不随处置改写");
			if ("WAIT_WMS_HOLD".equals(info.getStatus())) {
				info.setStatus(WAIT_JUDGE);
			}
			require(updateById(info), "MES隔离证明保存失败");
			saveOperation(info, null, UUID.randomUUID().toString(), "MES_HOLD", null, info.getStatus(),
					"MES隔离案件=" + c.getId() + "，初始隔离事实=" +
							com.kitex.mom.mes.dto.MesInventoryQualityOrigin.initialProofId(c) + "，数量=" +
							c.getQuantity() + " " + c.getMatUnit());
			return;
		}
		var command = baseCommand(info, info.getNum())
				.setRequestId(requestId).setAction("HOLD").setHoldNo(info.getHoldNo())
				.setReason("NCR " + info.getNcrNo() + " 不合格库存隔离");
		if (isSpecialReturn(info)) {
			// PENDING操作ID不是首次成功HOLD的root，所有初HOLD重放继续使用空root的原命令。
			command.setSpecialReturnBinding(
					new WmsSpecialReturnQualityDTO.Binding(specialReturnTarget(info), null, null));
		}
		SyncOutcome outcome = callWms(command);
		info.setHoldOperationId(outcome.operationId())
				.setHoldNo(StrUtil.blankToDefault(outcome.holdNo(), info.getHoldNo()))
				.setHoldSyncStatus(outcome.status()).setHoldSyncMessage(outcome.message());
		if (!"SUCCESS".equals(outcome.status())) {
			info.setStatus("WAIT_WMS_HOLD");
		} else if ("WAIT_WMS_HOLD".equals(info.getStatus())) {
			info.setStatus(WAIT_JUDGE);
		}
		updateById(info);
		saveOperation(info, null, UUID.randomUUID().toString(), "WMS_HOLD", null, info.getStatus(),
				"库存隔离=" + outcome.status() + "，" + outcome.message());
	}

    private SyncOutcome syncDisposition(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail) {
        require(!isMesCase(info), "MES案件须使用MES回执同步，不调用WMS处置");
        return callWms(dispositionCommand(info, detail,
                dispositionRequestId(detail)));
    }

    private String dispositionRequestId(QmsBadInfoDetailEntity detail) {
        return detail.getMrbProposalId() == null ? "WMS-DISP:" + detail.getDetailNo() + ":" + detail.getHandleResult() : "WMS-MRB:" + detail.getMrbProposalId();
    }

    private boolean saveMrbDecision(QmsBadInfoDetailEntity detail) {
        if (detail.getHandleFile() != null) return detailService.updateById(detail);
        return detailService.update(detail, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QmsBadInfoDetailEntity>()
                .eq(QmsBadInfoDetailEntity::getId, detail.getId()).set(QmsBadInfoDetailEntity::getHandleFile, null));
    }

    private WmsQualityStatusCommand dispositionCommand(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail,
                                                       String requestId) {
        String action = "SMASH".equals(detail.getHandleResult()) ? "SCRAP" : detail.getHandleResult();
        return baseCommand(info, detail.getJudgeNum()).setRequestId(requestId).setHoldNo(info.getHoldNo())
                .setAction(action).setReason("NCR明细 " + detail.getDetailNo() + " MRB处置：" + detail.getHandleRemark());
    }

	/** 组装正式QMS命令；特殊来源沿永久Target和首HOLD绑定，仓库/单位不读今日主数据。 */
	private WmsQualityStatusCommand baseCommand(QmsBadInfoEntity info, BigDecimal quantity) {
		require(!isMesCase(info), "MES案件不能发送至WMS库存处置接口");
		var command = new WmsQualityStatusCommand().setSourceType("QMS_NCR").setSourceId(String.valueOf(info.getId()))
				.setMatCode(info.getMatCode()).setMatBatch(info.getBatchNo()).setSupplierCode(info.getSupplierCode())
				.setSiteCode(info.getLocationCode()).setContainerCode(info.getContainerCode())
				.setQuantity(quantity).setFactoryCode(info.getFactoryCode());
		if (isSpecialReturn(info)) {
			var target = specialReturnTarget(info);
			command.setWarehouseCode(target.warehouseCode()).setMatUnit(target.unit())
					.setSpecialReturnBinding(new WmsSpecialReturnQualityDTO.Binding(target,
							"SUCCESS".equals(info.getHoldSyncStatus()) && info.getHoldOperationId() != null
									? info.getHoldOperationId().toString() : null, null));
		}
		return command;
	}

	/**
	 * 同时核业务码、永久来源、动作和精确数量；特殊来源传输异常或不完整回执记UNKNOWN。
	 */
	private SyncOutcome callWms(WmsQualityStatusCommand command) {
		try {
			// 专用路径让旧WMS明确404失败关闭，禁止忽略新Binding后落入普通批次/FIFO。
			boolean directed = command.getReholdOfPhysicalOperationId() != null;
			require(directed == (command.getReholdOfPhysicalRequestId() != null) &&
							(!directed || command.getSpecialReturnBinding() == null),
					"普通定向父字段必须成对且不能兼容特殊Binding");
			R<WmsQualityStatusResult> response;
			if (command.getSpecialReturnBinding() != null) {
				response = remoteLiveStockService.applySpecialReturnQualityStatus(command);
			} else if (directed) {
				response = remoteLiveStockService.applyPhysicalReholdStatus(command);
			} else {
				response = remoteLiveStockService.applyQualityStatus(command);
			}
			if (response == null || response.getCode() != 0 || response.getData() == null) {
				return new SyncOutcome(null, command.getHoldNo(),
						command.getSpecialReturnBinding() != null &&
								(response == null || response.getCode() == 0)
								? "UNKNOWN" : "FAILED",
						response == null ? (command.getSpecialReturnBinding() == null ? "WMS无响应"
								: "WMS结果未知，请按原NCR重试隔离核对永久回执") : StrUtil.blankToDefault(
								response.getMsg(), "WMS调用失败"));
			}
			WmsQualityStatusResult result = response.getData();
			if (directed) {
				require(result.getOperationId() != null && result.getOperationId() > 0 &&
						"HOLD".equals(result.getAction()) &&
						Objects.equals(command.getHoldNo(), result.getHoldNo()) &&
						Objects.equals(command.getReholdOfPhysicalOperationId(),
								result.getReholdOfPhysicalOperationId()) &&
						result.getSpecialReturnBinding() == null, "普通定向重隔离永久回执父ID、HOLD或QH不一致");
			}
			if (command.getSpecialReturnBinding() != null) {
				assertSpecialReturnReceipt(command, result);
			}
			require(result.getRequestedQuantity() != null &&
							result.getRequestedQuantity().compareTo(command.getQuantity()) == 0,
					"WMS返回的请求数量与NCR不一致，须核对远端回执，不得判为同步成功");
			if (Set.of("SUCCESS", "EXECUTED", "ACTION_PENDING")
					.contains(Objects.toString(result.getStatus(), ""))) {
				require(result.getAffectedQuantity() != null &&
								result.getAffectedQuantity().compareTo(command.getQuantity()) == 0,
						"WMS实际影响数量与NCR不一致，须人工对账，禁止验证放行");
			}
			if (StrUtil.isNotBlank(result.getSupersededByOperationId())) {
				// 原成功处置不被改写；永久REHOLD关系已使其失效，禁止再用历史SUCCESS验证结案。
				return new SyncOutcome(result.getOperationId(), result.getHoldNo(), "SUPERSEDED",
						"原处置已被验证驳回并恢复隔离，请继续原驳回落账；恢复操作=" +
								result.getSupersededByOperationId());
			}
			return new SyncOutcome(result.getOperationId(), result.getHoldNo(), result.getStatus(),
					result.getMessage());
		} catch (Exception ex) {
			return new SyncOutcome(null, command.getHoldNo(),
					command.getSpecialReturnBinding() == null ? "FAILED" : "UNKNOWN",
					StrUtil.maxLength(ex.getMessage(), 500));
		}
	}

	private boolean isSpecialReturn(QmsBadInfoEntity info) {
		return "WMS_SPECIAL_RETURN".equals(info.getResourceType());
	}

	/**
	 * 只读永久来源；新分支每次恢复先核实际组织和数据库保护，普通来源不新增依赖。
	 */
	private WmsSpecialReturnQualityDTO.Target specialReturnTarget(QmsBadInfoEntity info) {
		try {
			require(Boolean.TRUE.equals(specialReturnJdbc.queryForObject(
							"SELECT mom_qms.qms_special_return_quality_ready_v1()", Boolean.class)),
					"特殊退回数据库保护未就绪");
			var target = specialReturnJson.readValue(info.getWmsSpecialReturnTargetJson(),
					WmsSpecialReturnQualityDTO.Target.class);
			var user = SecurityUtils.getUser();
			var tenant = com.kitex.common.data.tenant.TenantContextHolder.getTenantId();
			var factory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
			require(user != null && user.getId() != null && tenant != null
							&& !com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip()
							&& !Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip())
							&& Objects.equals(tenant, info.getTenantId()) && Objects.equals(factory, info.getFactoryCode()),
					"特殊退回NCR不属于当前实名租户工厂");
			var access = actualFactories.check(new com.kitex.mom.mdm.dto.MdmFactoryAccessCheck()
					.setUserId(user.getId()).setFactoryCode(factory).setRequiredPermission("read"));
			require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
					"当前人员没有实际工厂访问权限");
			require(target != null && target.contractVersion() == 1 && target.identity() != null
							&& Objects.equals(target.tenantId(), tenant.toString()) &&
							Objects.equals(target.factoryCode(), factory)
							&& Objects.equals(target.identity().get("matCode"), info.getMatCode())
							&& Objects.equals(target.identity().get("matBatch"), info.getBatchNo())
							&& Objects.equals(target.identity().get("supplierCode"), info.getSupplierCode())
							&& Objects.equals(target.unit(), info.getQuantityUnit()) &&
							Objects.equals(target.siteCode(), info.getLocationCode())
							&& Objects.equals(target.containerCode(), info.getContainerCode())
							&& new BigDecimal(target.quantity()).compareTo(info.getNum()) == 0,
					"NCR与永久退回原件、数量、单位或位置不一致，禁止重新选件");
			return target;
		} catch (com.kitex.common.core.exception.CheckedException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new CheckedException("特殊退回永久来源或数据库保护无法核实，请核对本次升级和原案");
		}
	}

	/**
	 * WMS回执必须来自相同原件/root/被驳回处置及正式动作，不能只凭影响量标成功。
	 */
	private void assertSpecialReturnReceipt(WmsQualityStatusCommand command, WmsQualityStatusResult result) {
		var sent = command.getSpecialReturnBinding();
		var returned = result.getSpecialReturnBinding();
		boolean completed = Set.of("SUCCESS", "EXECUTED", "ACTION_PENDING")
				.contains(Objects.toString(result.getStatus(), ""));
		require(result.getOperationId() != null && returned != null && Objects.equals(sent.target(), returned.target())
						&& Objects.equals(sent.reholdOperationId(), returned.reholdOperationId())
						&& Objects.equals(command.getAction(), result.getAction())
						&& (StrUtil.isBlank(command.getHoldNo()) || Objects.equals(command.getHoldNo(), result.getHoldNo())),
				"WMS永久回执原件、动作或隔离号不一致，请核对原号");
		if (StrUtil.isBlank(sent.rootHoldOperationId())) {
			require(StrUtil.isBlank(returned.rootHoldOperationId())
							|| Objects.equals(result.getOperationId().toString(), returned.rootHoldOperationId()),
					"WMS初HOLD原root不一致");
			if (completed) {
				require(Objects.equals(result.getOperationId().toString(), returned.rootHoldOperationId())
								&& result.getAffectedStockIds() != null && result.getAffectedStockIds().size() == 1
								&& Objects.equals(sent.target().stockId(), result.getAffectedStockIds().get(0)),
						"WMS未证明选定原件的首次隔离");
			}
		} else {
			require(Objects.equals(sent.rootHoldOperationId(), returned.rootHoldOperationId()),
					"WMS回执不属于本NCR首次隔离");
		}
		if (completed) {
			require(StrUtil.isNotBlank(result.getHoldNo()) && result.getAffectedStockIds() != null
					&& !result.getAffectedStockIds().isEmpty(), "WMS回执缺实际原件清单，禁止判为同步成功");
		}
	}

    private boolean isMesCase(QmsBadInfoEntity info) {
        return com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(info.getResourceType());
    }

    @Override
    public com.kitex.mom.qms.dto.QmsMesCaseDecision mesDecision(Long detailId) {
        String factory = requireMesFactory();
        var detail = detailService.getOne(new LambdaQueryWrapper<QmsBadInfoDetailEntity>().eq(QmsBadInfoDetailEntity::getId, detailId)
                .eq(QmsBadInfoDetailEntity::getFactoryCode, factory).eq(QmsBadInfoDetailEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId()));
        require(detail != null && com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(detail.getResourceType()) && StrUtil.isNotBlank(detail.getMesDecisionKey()), "不存在本厂已提交MES处置决策");
        var info = getOne(new LambdaQueryWrapper<QmsBadInfoEntity>().eq(QmsBadInfoEntity::getId, detail.getBadId())
                .eq(QmsBadInfoEntity::getFactoryCode, factory).eq(QmsBadInfoEntity::getTenantId, com.kitex.common.data.tenant.TenantContextHolder.getTenantId()));
        require(info != null && isMesCase(info) && Objects.equals(detail.getResourceTaskId(), info.getResourceTaskId()), "MES决策主从来源不一致");
        var response = new com.kitex.mom.qms.dto.QmsMesCaseDecision();
        response.setNcr(info);
        response.setDetail(detail);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeMes(com.kitex.mom.qms.dto.req.QmsBadMesExecuteReq req) {
        require(req != null && req.getDetailId() != null, "须指定MES案件明细");
        requireMesFactory();
        var detail = lockDetail(req.getDetailId());
        require(detail != null, "NCR明细不存在");
        var info = lockInfo(detail.getBadId());
        require(info != null && isMesCase(info) && Set.of(WAIT_EXECUTION, WAIT_VERIFY, CLOSED).contains(detail.getStatus()), "仅有当前MES决策的案件可执行或重放原执行回执");
        assertMesSource(info);
        require(Objects.equals(req.getDecisionKey(), detail.getMesDecisionKey()), "MRB决策已变化，请刷新后执行");
        var command = new com.kitex.mom.mes.dto.MesCaseDispositionCommand();
        BeanUtil.copyProperties(req, command);
        command.setStage("EXECUTE");
        var response = remoteMesQualityCases.execute(command);
        require(response != null && response.isOk() && response.getData() != null, "MES执行结果未确认；勿重复实物操作，先同步原回执");
        applyMesReceipt(info, detail, response.getData());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncMes(Long detailId) {
        requireMesFactory();
        var detail = lockDetail(detailId);
        require(detail != null, "NCR明细不存在");
        var info = lockInfo(detail.getBadId());
        require(info != null && isMesCase(info) && StrUtil.isNotBlank(detail.getMesDecisionKey()), "当前不存在MES处置决策");
        var response = remoteMesQualityCases.chain(info.getResourceTaskId(), info.getId(), detailId, detail.getMesDecisionKey());
        require(response != null && response.isOk(), "MES回执查询失败，不能按未执行处理");
        require(response.getData() != null, "MES尚无该决策执行回执，库存仍隔离；请现场核对后执行");
        require(response.getData().getReceipts() != null && !response.getData().getReceipts().isEmpty(), "MES尚无该决策执行回执；请现场核对后执行");
        applyMesBundle(info, detail, response.getData());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void discoverMesReconcile(Long detailId) {
        requireBackgroundScope();
        var d = lockDetail(detailId);
        if (d == null || !com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(d.getResourceType()) || d.getMesDecisionKey() == null)
            return;
        var n = lockInfo(d.getBadId());
        require(n != null && isMesCase(n), "历史MES决策来源丢失");
        mesReceiptQueue.enqueue(n, d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncMesInBackground(com.kitex.mom.qms.entity.QmsMesReceiptReconcileEntity job) {
        requireBackgroundScope();
        require(job != null && Objects.equals(job.getTenantId(), com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                && Objects.equals(job.getFactoryCode(), com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()), "后台对账范围与待办不一致");
        var detail = lockDetail(job.getDetailId());
        require(detail != null, "对账NCR明细不存在，需人工核对");
        var info = lockInfo(detail.getBadId());
        require(info != null && isMesCase(info) && Objects.equals(info.getId(), job.getBadId())
                && Objects.equals(info.getResourceTaskId(), job.getCaseId()) && Objects.equals(info.getTenantId(), job.getTenantId()) && Objects.equals(info.getFactoryCode(), job.getFactoryCode()), "对账NCR来源或归属变化");
        if (!Objects.equals(detail.getMesDecisionKey(), job.getDecisionKey()))
            return Map.of("status", "SUPERSEDED", "applied", 0, "message", "已有其他决策版本，停止重放旧决策");
        require(job.getDecisionSnapshot().equals(com.kitex.mom.qms.dto.QmsMesCaseDecisionSnapshot.of(info, detail)), "本地决策证据变化，停止自动恢复并转人工核对");
        var query = new com.kitex.mom.mes.dto.MesCaseReceiptRead();
        query.setTenantId(job.getTenantId());
        query.setFactoryCode(job.getFactoryCode());
        query.setCaseId(job.getCaseId());
        query.setNcrId(job.getBadId());
        query.setDetailId(job.getDetailId());
        query.setDecisionKey(job.getDecisionKey());
        query.setIssuedAt(java.time.Instant.now().getEpochSecond());
        query.setNonce(UUID.randomUUID().toString());
        query.setSignature(query.sign(receiptReadSecret));
        var response = remoteMesQualityCases.readReceipts(query);
        if (response == null || !response.isOk())
            throw new IllegalStateException("MES只读服务未确认成功，保持状态未知并退避");
        require(response.getData() != null, "MES回执只读成功响应缺少证据，不能当作未执行");
        return applyMesBundle(info, detail, response.getData());
    }

    private void requireBackgroundScope() {
        require(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null && currentUser() == null
                        && com.kitex.common.data.tenant.TenantContextHolder.getTenantId() != null && !com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip()
                        && StrUtil.isNotBlank(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()) && !Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip()),
                "后台回执恢复必须运行在独立租户/工厂调度上下文，不能冒用现场人员");
    }

    private Map<String, Object> applyMesBundle(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail, com.kitex.mom.mes.dto.MesCaseReceiptBundle bundle) {
        assertMesSourceSnapshot(info, bundle.getSource());
        var chain = bundle.getReceipts();
        require(chain != null && chain.size() <= 2, "MES回执链不可核验");
        if (chain.isEmpty()) {
            require(WAIT_EXECUTION.equals(detail.getStatus()) && detail.getMesReceiptId() == null, "MES没有实物回执但QMS已有执行结果，须人工对账");
            return Map.of("status", "WAIT_EXECUTION", "applied", 0, "message", "MES已核实尚无执行回执，继续等待现场执行");
        }
        var execution = chain.get(0);
        require(execution != null && "EXECUTE".equals(execution.getStage()) && execution.getExecutionReceiptId() == null, "回执链缺少原执行事实");
        if (chain.size() == 2) {
            var v = chain.get(1);
            require(v != null && Set.of("VERIFY_PASS", "VERIFY_REJECT").contains(v.getStage())
                    && Objects.equals(v.getExecutionReceiptId(), execution.getId()) && !Objects.equals(v.getOperatorId(), execution.getOperatorId())
                    && v.getSequenceNo() != null && execution.getSequenceNo() != null && v.getSequenceNo() > execution.getSequenceNo(), "验证回执与原执行回执不连续或未独立分岗");
        }
        int applied = 0;
        for (var receipt : chain) {
            if (!hasOperation("MES-RECEIPT:" + receipt.getId())) applied++;
            applyMesReceipt(info, detail, receipt);
        }
        var last = chain.get(chain.size() - 1);
        String expected = "EXECUTE".equals(last.getStage()) ? WAIT_VERIFY : "VERIFY_PASS".equals(last.getStage()) ? CLOSED : WAIT_MRB;
        require(expected.equals(detail.getStatus()) && Objects.equals(detail.getMesReceiptId(), last.getId()), "已有QMS回执与当前状态不一致，禁止仅凭重复幂等号认定恢复完成");
        return Map.of("status", chain.size() == 2 ? "DONE" : "WAIT_VERIFY", "applied", applied, "receiptId", last.getId(), "receipts", chain, "source", bundle.getSource());
    }

    @Override
    public Map<String, Object> mesContext(Long detailId) {
        var decision = mesDecision(detailId);
        var source = assertMesSource(decision.getNcr());
        var response = remoteMesQualityCases.latest(detailId, decision.getDetail().getMesDecisionKey());
        require(response != null && response.isOk(), "MES回执状态未知，禁止按未执行展示");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("detail", decision.getDetail());
        result.put("source", source);
        result.put("receipt", response.getData());
        return result;
    }

    private void applyMesReceipt(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail, com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity receipt) {
        require(Objects.equals(receipt.getTenantId(), info.getTenantId()) && Objects.equals(receipt.getFactoryCode(), info.getFactoryCode())
                        && Objects.equals(receipt.getCaseId(), info.getResourceTaskId()) && Objects.equals(receipt.getNcrId(), info.getId())
                        && Objects.equals(receipt.getDetailId(), detail.getId()) && Objects.equals(receipt.getDecisionKey(), detail.getMesDecisionKey())
                        && Objects.equals(receipt.getAction(), detail.getHandleResult()) && receipt.getQuantity() != null && receipt.getQuantity().compareTo(detail.getJudgeNum()) == 0,
                "MES回执来源、决策或数量与NCR不一致，不能更新本地状态");
        require(receipt.getId() != null && receipt.getOperatorId() != null && receipt.getDeciderId() != null
                && Objects.equals(receipt.getDeciderId(), detail.getMesDeciderUserId()) && !Objects.equals(receipt.getOperatorId(), receipt.getDeciderId())
                && Objects.equals(receipt.getDeciderCode(), detail.getHandlePersonId())
                && Objects.equals(receipt.getDecisionSnapshot(), com.kitex.mom.qms.dto.QmsMesCaseDecisionSnapshot.of(info, detail)), "MES原始决策证据或独立人员身份不一致，不能补同步");
        String event = "MES-RECEIPT:" + receipt.getId();
        if (hasOperation(event)) return;
        String before = detail.getStatus();
        if ("EXECUTE".equals(receipt.getStage())) {
            require(WAIT_EXECUTION.equals(before), "不能用旧执行回执覆盖后续验证状态");
            detail.setStatus(WAIT_VERIFY).setMesExecutorCode(receipt.getOperatorCode()).setMesExecutionEvidence(receipt.getEvidence());
        } else {
            require(WAIT_VERIFY.equals(before), "必须先同步执行事实，再同步验证结案");
            require(Set.of("VERIFY_PASS", "VERIFY_REJECT").contains(receipt.getStage()), "未知MES回执阶段");
            detail.setStatus("VERIFY_PASS".equals(receipt.getStage()) ? CLOSED : WAIT_MRB)
                    .setVerificationResult("VERIFY_PASS".equals(receipt.getStage()) ? "PASS" : "REJECT").setVerificationRemark(receipt.getEvidence())
                    .setVerifierCode(receipt.getOperatorCode()).setVerifierName(receipt.getOperatorCode()).setVerificationTime(receipt.getCreateTime());
        }
        detail.setMesReceiptId(receipt.getId());
        require(detailService.updateById(detail), "QMS回执状态保存失败，可同步MES永久回执恢复");
        refreshHeader(info);
        saveOperation(info, detail, event, "MES_SYNC", before, detail.getStatus(), "MES回执=" + receipt.getId() + "，阶段=" + receipt.getStage() + "，原执行账号=" + receipt.getOperatorCode() + "，数量=" + receipt.getQuantity() + "，证据=" + receipt.getEvidence());
    }

    private String requireMesFactory() {
        var user = SecurityUtils.getUser();
        var factory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && com.kitex.common.data.tenant.TenantContextHolder.getTenantId() != null
                && !com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(factory), "MES处置须实名登录并选择单一实际工厂");
        // factory-code由原认证链路确定；岗位权限由入口校验，不增加第二套MDM工厂授权。
        return factory;
    }

    private com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity mesSource(Long id) {
        require(id != null && id > 0, "须提供MES原始质量案件主键");
        require(SecurityUtils.getUser() != null && com.kitex.common.data.tenant.TenantContextHolder.getTenantId() != null
                && !com.kitex.common.data.tenant.TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()), "须以实名用户选择单一实际工厂登记MES案件");
        var response = remoteMesQualityCases.source(id);
        require(response != null && response.isOk() && response.getData() != null, "MES隔离事实未核实，请核对案件和工厂权限后重试");
        var c = response.getData();
        require(Objects.equals(c.getId(), id) && Objects.equals(c.getTenantId(), com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                && Objects.equals(c.getFactoryCode(), com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode())
                && com.kitex.mom.mes.dto.MesInventoryQualityOrigin.initialProofId(c) != null && c.getStockId() != null && StrUtil.isNotBlank(c.getMatCode()), "MES隔离回执来源/归属不一致");
        requireQuantity(c.getQuantity(), "MES实收隔离数量");
        return c;
    }

    private com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity assertMesSource(QmsBadInfoEntity info) {
        var c = mesSource(info.getResourceTaskId());
        assertMesSourceSnapshot(info, c);
        return c;
    }

    private void assertMesSourceSnapshot(QmsBadInfoEntity info, com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity c) {
        require(c != null && Objects.equals(c.getId(), info.getResourceTaskId()) && c.getQuantity() != null && com.kitex.mom.mes.dto.MesInventoryQualityOrigin.initialProofId(c) != null && c.getStockId() != null
                && Objects.equals(com.kitex.mom.mes.dto.MesInventoryQualityOrigin.resourceType(c), info.getResourceType())
                && Objects.equals(c.getTenantId(), info.getTenantId()) && Objects.equals(c.getFactoryCode(), info.getFactoryCode())
                && Objects.equals(c.getMatCode(), info.getMatCode()) && Objects.equals(c.getMatBatch(), info.getBatchNo())
                && Objects.equals(c.getMatUnit(), info.getQuantityUnit()) && Objects.equals(c.getLocationCode(), info.getLocationCode())
                && info.getNum() != null && info.getNum().compareTo(c.getQuantity()) == 0, "NCR与MES不可变来源数量、单位或库位不一致");
    }

    private void applyDispositionOutcome(QmsBadInfoDetailEntity detail, SyncOutcome outcome) {
        detail.setWmsOperationId(outcome.operationId()).setWmsSyncStatus(outcome.status())
                .setWmsSyncMessage(outcome.message());
        if ("ACTION_PENDING".equals(outcome.status())) detail.setStatus(WAIT_EXECUTION);
        else if ("SUCCESS".equals(outcome.status()) || "EXECUTED".equals(outcome.status()))
            detail.setStatus(WAIT_VERIFY);
        else detail.setStatus(WAIT_WMS);
    }

    private void verifyPass(QmsBadInfoDetailEntity detail, QmsBadVerifyReq req, SyncOutcome outcome) {
        if (TERMINAL_ACTIONS.contains(detail.getHandleResult())) {
            require("EXECUTED".equals(outcome.status()), "报废/退货尚未完成仓库实物执行，不能验证通过");
        } else {
            require("SUCCESS".equals(outcome.status()) || "EXECUTED".equals(outcome.status()),
                    "WMS处置尚未成功，不能验证通过");
        }
        if ("REWORK".equals(detail.getHandleResult())) {
            require(StrUtil.isNotBlank(req.getExecutionRef()), "返工验证必须填写返工任务/工单号");
        }
        detail.setExecutionRef(req.getExecutionRef()).setStatus(CLOSED);
    }

	private void verifyReject(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail, QmsBadVerifyReq req,
	                          SyncOutcome current) {
		require(!TERMINAL_ACTIONS.contains(detail.getHandleResult()) || !"EXECUTED".equals(current.status()),
				"报废/退货实物已执行，不允许撤回为重新MRB");
		if (!"REWORK".equals(detail.getHandleResult()) || isSpecialReturn(info)) {
			var command = baseCommand(info, detail.getJudgeNum())
					.setRequestId("WMS-REHOLD:" + detail.getDetailNo() + ":" + req.getRequestId())
					.setAction("HOLD").setHoldNo(info.getHoldNo())
					.setReason("验证驳回，恢复质量隔离：" + req.getRemark());
			if (isSpecialReturn(info)) {
				// 只恢复本分类实际处置片段，不能从本NCR其他仍HOLD的分类中补量。
				require(detail.getWmsOperationId() != null &&
								Objects.equals(detail.getWmsOperationId(), current.operationId()),
						"缺本分类原处置回执，不能恢复隔离");
				var binding = command.getSpecialReturnBinding();
				// 以原处置永久ID恢复同一次REHOLD；关闭弹窗/重启或本地回滚后也能重建完全相同原命令。
				// 当前验证说明只写QMS验证与履历，不进入远端幂等原文。
				command.setRequestId("WMS-REHOLD:" + detail.getDetailNo() + ":" + detail.getWmsOperationId())
						.setReason("NCR明细 " + detail.getDetailNo() + " 原处置 " + detail.getWmsOperationId() +
								" 验证驳回恢复质量隔离")
						.setSpecialReturnBinding(new WmsSpecialReturnQualityDTO.Binding(binding.target(),
								binding.rootHoldOperationId(), detail.getWmsOperationId().toString()));
			}
			if (!isSpecialReturn(info) && TERMINAL_ACTIONS.contains(detail.getHandleResult())) {
				require(detail.getWmsOperationId() != null &&
								Objects.equals(detail.getWmsOperationId(), current.operationId()),
						"缺本明细原实物处置回执，不能定向恢复隔离");
				command.setReholdOfPhysicalOperationId(detail.getWmsOperationId().toString())
						.setReholdOfPhysicalRequestId(dispositionRequestId(detail))
						.setRequestId("WMS-PH-REHOLD:" + detail.getWmsOperationId())
						.setReason("NCR明细 " + detail.getDetailNo() + " 原处置 " + detail.getWmsOperationId() +
								" 验证驳回恢复质量隔离");
			}
			SyncOutcome rehold = callWms(command);
			require("SUCCESS".equals(rehold.status()), "验证驳回后的库存恢复隔离失败：" + rehold.message());
			detail.setWmsOperationId(rehold.operationId()).setWmsSyncStatus(rehold.status())
					.setWmsSyncMessage(rehold.message());
		}
		detail.setHandleResult(null).setHandleRemark(null).setHandleFile(null).setExpectedCompletionTime(null)
				.setHandlePersonId(null).setHandlePersonName(null).setHandleTime(null).setExecutionRef(null)
				.setStatus(WAIT_MRB);
	}

    private void refreshHeader(QmsBadInfoEntity info) {
        List<QmsBadInfoDetailEntity> details = detailService.list(new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                .eq(QmsBadInfoDetailEntity::getBadId, info.getId()));
        BigDecimal judged = details.stream().map(item -> value(item.getJudgeNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal disposed = details.stream().filter(item -> !WAIT_MRB.equals(item.getStatus()))
                .map(item -> value(item.getJudgeNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closed = details.stream().filter(item -> CLOSED.equals(item.getStatus()))
                .map(item -> value(item.getJudgeNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(judged.signum() >= 0 && judged.compareTo(value(info.getNum())) <= 0
                        && disposed.signum() >= 0 && disposed.compareTo(judged) <= 0 && closed.signum() >= 0 && closed.compareTo(disposed) <= 0,
                "NCR分类、处置和关闭数量不守恒，禁止用截断归零掩盖异常");
        info.setJudgedNum(judged).setUnJudgeNum(value(info.getNum()).subtract(judged))
                .setDisposedNum(disposed).setClosedNum(closed);
        if (!"SUCCESS".equals(info.getHoldSyncStatus())) info.setStatus("WAIT_WMS_HOLD");
        else if (info.getUnJudgeNum().signum() > 0) info.setStatus(WAIT_JUDGE);
        else if (details.stream().anyMatch(item -> WAIT_MRB.equals(item.getStatus()))) info.setStatus(WAIT_MRB);
        else if (details.stream().anyMatch(item -> WAIT_WMS.equals(item.getStatus()) || WAIT_EXECUTION.equals(item.getStatus())))
            info.setStatus(isMesCase(info) ? WAIT_EXECUTION : WAIT_WMS);
        else if (details.stream().anyMatch(item -> WAIT_VERIFY.equals(item.getStatus()))) info.setStatus(WAIT_VERIFY);
        else if (closed.compareTo(value(info.getNum())) == 0) info.setStatus(CLOSED);
        require(updateById(info), "NCR数量进度保存失败");
    }

    private void validateHandle(QmsBadHandleReq req) {
        require(req != null && req.getDetailId() != null, "不合格明细不能为空");
        require(StrUtil.isNotBlank(req.getRequestId()), "处置请求号不能为空");
        require(DISPOSITIONS.contains(req.getHandleResult()), "处置方式无效");
        require(StrUtil.isNotBlank(req.getHandleRemark()), "处置说明不能为空");
        require(req.getExpectedCompletionTime() != null, "预计完成时间不能为空");
    }

	private QmsBadInfoEntity lockInfo(Long id) {
		var info = getOne(new LambdaQueryWrapper<QmsBadInfoEntity>()
				.eq(QmsBadInfoEntity::getId, id).last("FOR UPDATE"), false);
		if (info != null) {
			require(info.getNum() != null && info.getNum().signum() > 0
							&& info.getUnJudgeNum() != null && info.getUnJudgeNum().signum() >= 0
							&& info.getJudgedNum() != null && info.getJudgedNum().signum() >= 0
							&& info.getDisposedNum() != null && info.getDisposedNum().signum() >= 0
							&& info.getClosedNum() != null && info.getClosedNum().signum() >= 0
							&& info.getUnJudgeNum().add(info.getJudgedNum()).compareTo(info.getNum()) == 0
							&& info.getDisposedNum().compareTo(info.getJudgedNum()) <= 0 &&
							info.getClosedNum().compareTo(info.getDisposedNum()) <= 0,
					"NCR原数量进度缺失或不守恒，请先核对真实数据；不能以0代替未知量后调用WMS");
		}
		if (info != null && isSpecialReturn(info)) {
			specialReturnTarget(info);
		}
		return info;
	}

    private QmsBadInfoDetailEntity lockDetail(Long id) {
        var detail = detailService.getOne(new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                .eq(QmsBadInfoDetailEntity::getId, id).last("FOR UPDATE"), false);
        if (detail != null)
            require(detail.getNum() != null && detail.getJudgeNum() != null && detail.getJudgeNum().signum() > 0
                    && detail.getJudgeNum().compareTo(detail.getNum()) <= 0, "NCR分类数量缺失或不守恒，禁止继续远端处置");
        return detail;
    }

    /** 新旧写入口共享detail锁后核未知意图，禁止在WMS已生效、本地未确认时改案。 */
	private void assertNoPendingRework(QmsBadInfoDetailEntity detail) {
		if (!com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(detail.getResourceType())) {
			ncrReworkLedger.noPending(detail.getTenantId(), detail.getFactoryCode(), detail.getId());
		}
	}

    private boolean hasOperation(String requestId) {
        return operationService.count(new LambdaQueryWrapper<QmsBadOperationEntity>()
                .eq(QmsBadOperationEntity::getRequestId, requestId)) > 0;
    }

    private void assertSameClassification(QmsBadJudgeReq req) {
        var previous = detailService.getOne(new LambdaQueryWrapper<QmsBadInfoDetailEntity>()
                .eq(QmsBadInfoDetailEntity::getRequestId, req.getRequestId()), false);
        require(previous != null && Objects.equals(previous.getBadId(), req.getId()) && previous.getJudgeNum() != null
                && previous.getJudgeNum().compareTo(req.getNum()) == 0 && Objects.equals(previous.getBadType(), req.getBadType())
                && Objects.equals(previous.getBadReason(), req.getBadReason()), "判定请求号已用于不同NCR、数量或分类内容");
    }

    private void saveOperation(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail, String requestId,
                               String type, String before, String after, String content) {
        MomUser user = currentUser();
        var operation = new QmsBadOperationEntity().setRequestId(requestId).setBadId(info.getId())
                .setDetailId(detail == null ? null : detail.getId()).setNcrNo(info.getNcrNo())
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(userCode(user)).setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(info.getFactoryCode());
        operation.setTenantId(info.getTenantId());
        require(operationService.save(operation), "NCR永久操作回执保存失败，业务状态已回滚");
    }

    private String sourceRequestId(QmsBadInfoEntity info) {
        if (info.getResourceTaskId() == null || StrUtil.isBlank(info.getResourceType()))
            return UUID.randomUUID().toString();
        return "INSPECTION:" + info.getResourceType() + ":" + info.getResourceTaskId() + ":"
                + StrUtil.nullToEmpty(info.getMatCode()) + ":" + StrUtil.nullToEmpty(info.getBatchNo());
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : user.getName();
    }

    private BigDecimal value(BigDecimal number) {
        return number == null ? BigDecimal.ZERO : number;
    }

    private void requireQuantity(BigDecimal quantity, String label) {
        require(quantity != null && quantity.signum() > 0 && (long) quantity.precision() + Math.max(-(long) quantity.scale(), 0) <= 24 && Math.max(quantity.scale(), 0) <= 8,
                label + "须为正数，最多24位且8位小数；禁止取整或舍入");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record SyncOutcome(Long operationId, String holdNo, String status, String message) {
    }
}
