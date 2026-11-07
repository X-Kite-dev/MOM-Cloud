package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.mapper.MesNgMaterialPolicyMapper.Header;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.service.impl.material.MesBackflushAllocationPlanner;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 独立NG POLICY消耗；只接真实出生，不复用GOOD或旧FEED账。
 */
@Service
@RequiredArgsConstructor
public class MesNgMaterialPolicyService {
	private final MesActualFactoryAccessService access;
	private final MesExecutionTaskLock locks;
	private final MesTaskMapper tasks;
	private final MesTaskArrangeMapper arrangements;
	private final MesProductionFeedBatchMapper births;
	private final MesWarehouseFeedMapper feeds;
	private final MesWarehouseLiveStockMapper stockRows;
	private final MesMaterialSourceEligibility eligibility;
	private final MesMaterialConsumptionModeResolver modes;
	private final MesStartMaterialBindingReader bindings;
	private final MesWarehouseLiveStockService stocks;
	private final RemoteMaterialService materials;
	private final MesNgMaterialPolicyPlanner planner;
	private final MesNgMaterialPolicyMapper ledger;
	private final MesProductionNgSourceCodec sources;
	private final MesNgMaterialPolicyCodec evidence;
	private final MesPhysicalTransformCodec codec;

	/**
	 * 在真实NG首次出生的同一本地事务中按原政策耗料并保存完整原账；原出生、组织、来源池、数量或真实回执不一致即回滚。参数须来自已锁定原报告与工单，不接受伪GOOD。
	 */
	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
	public void consume(MesTaskReportUnqualifiedEntity source, MesTaskArrangeEntity callerArrange, MesTaskEntity callerTask) {
		String factory = access.require("write");
		Long tenant = com.kitex.common.data.tenant.TenantContextHolder.getTenantId();
		ready();
		require(source != null && callerArrange != null && callerTask != null && sources.verify(source)
						&& Objects.equals(source.getTenantId(), tenant) && Objects.equals(source.getFactoryCode(), factory)
						&& Objects.equals(source.getCapturedActorId(), com.kitex.common.security.util.SecurityUtils.getUser().getId()),
				"NG逐料耗用须为当前实际实名组织的原来源");
		var owner = locks.arrange(source.getArrangeCode());
		var rows = arrangements.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, source.getArrangeCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(rows != null && rows.size() == 1, "NG实际派工未唯一归属");
		var arrange = rows.get(0);
		locks.assertArrange(owner, arrange);
		var parents = tasks.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, source.getTaskCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(parents != null && parents.size() == 1, "NG真实父工单不唯一");
		var task = parents.get(0);
		require(Objects.equals(arrange.getCode(), callerArrange.getCode()) && Objects.equals(arrange.getTaskCode(), task.getCode())
				&& Objects.equals(source.getProduceTaskCode(), arrange.getProduceTaskCode())
				&& Objects.equals(task.getTenantId(), tenant) && Objects.equals(task.getFactoryCode(), factory)
				&& Objects.equals(callerTask.getCode(), task.getCode())
				&& Objects.equals(callerTask.getProcessRouteSnapshot(), task.getProcessRouteSnapshot())
				&& Objects.equals(callerTask.getBomSnapshot(), task.getBomSnapshot()), "NG原父工单、工艺或BOM不一致");
		var birth = births.actualBirth(tenant, factory, "NG", source.getId());
		require(birth != null && birth.getId() != null && birth.getId() > 0 && Boolean.TRUE.equals(birth.getSameTransaction())
						&& "NG".equals(birth.getOutputKind()) && Objects.equals(birth.getSourceId(), source.getId())
						&& Objects.equals(birth.getTenantId(), tenant) && Objects.equals(birth.getFactoryCode(), factory)
						&& codec.quantity(birth.getQuantity(), false, "真实NG加工量").compareTo(source.getUnqualifiedNum()) == 0,
				"NG耗用仅接本事务首次真实NG出生，不能补旧来源或伪GOOD");
		var facts = codec.objectDocument(birth.getSourceSnapshot(), 32 * 1024 * 1024);
		var expected = new LinkedHashMap<String, String>();
		expected.put("id", source.getId().toString());
		expected.put("tenant_id", tenant.toString());
		expected.put("factory_code", factory);
		expected.put("task_code", task.getCode());
		expected.put("arrange_code", arrange.getCode());
		expected.put("produce_task_code", arrange.getProduceTaskCode());
		expected.put("mat_code", source.getMatCode());
		expected.put("request_id", source.getRequestId());
		expected.put("report_unit", source.getReportUnit());
		expected.put("captured_actor_id", source.getCapturedActorId().toString());
		expected.put("source_fingerprint", source.getSourceFingerprint());
		expected.put("del_flag", "0");
		require(expected.entrySet().stream().allMatch(e -> Objects.equals(facts.path(e.getKey()).asText(), e.getValue())),
				"真实NG出生身份、本人、原单位或原文SHA不一致");
		require(original(source) == null, "NG原来源已登记POLICY，不能再次耗料");
		var selection = modes.selection(arrange);
		require("START_CHECK".equals(selection.source()) && arrange.getMaterialGateRecordId() != null
				&& Objects.equals(arrange.getMaterialGateRecordId().toString(), selection.sourceId()), "NG POLICY只能沿原开工政策");
		var binding = bindings.binding(arrange);
		var selected = bindings.sourceIds(arrange);
		require((binding == null) == (selected == null), "NG原来源池绑定与完整成员全集不一致");
		var policy = new LinkedHashMap<String, Object>();
		policy.put("selection", selection.manifest());
		policy.put("materialReservationSnapshot", task.getMaterialReservationSnapshot());
		policy.put("bindingId", binding == null ? null : binding.getId().toString());
		policy.put("bindingFingerprint", binding == null ? null : binding.getBindingFingerprint());
		policy.put("bindingSnapshot", binding == null ? null : binding.getBindingJson());
		policy.put("selectedFeedIds", selected == null ? null : selected.stream().sorted().map(Object::toString).toList());
		var route = com.kitex.mom.mes.util.MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(), arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
		var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode()).eq(MesWarehouseFeedEntity::getIsLock, false)
				.and(w -> w.isNull(MesWarehouseFeedEntity::getMatValidEndDate).or()
						.ge(MesWarehouseFeedEntity::getMatValidEndDate, java.time.LocalDate.now())).orderByAsc(MesWarehouseFeedEntity::getId);
		bindings.constrain(arrange, query);
		var requirements = Objects.requireNonNullElse(route.getMdmProcessProcedureMatList(), List.<com.kitex.mom.mes.dto.ProcessProcedureDTO.ProcessProcedureMatDTO>of());
		var feedRequirements = MesMaterialConsumptionModeResolver.partition(selection.mode(), requirements, MesMaterialConsumptionModeResolver.Mode.FEED);
		var feedRows = feedRequirements.isEmpty() ? List.<MesWarehouseFeedEntity>of() : feeds.selectList(query.last("LIMIT 5001 FOR UPDATE NOWAIT"));
		require(feedRows != null && feedRows.size() <= 5000, "NG投料来源全集未知或超限");
		var plan = planner.plan(arrange, source.getId().toString(), source.getUnqualifiedNum(), source.getReportUnit(),
				selection, requirements, task.getBomSnapshot(), feedRows,
				r -> stocks.previewOutCandidates(stockRequest(arrange, r.requirementCode(), r.candidate().actualMatCode(), r.candidate().ratio(), r.candidate().substitute())),
				code -> {
					var response = materials.productionMeasure(code);
					require(response != null && response.isOk() && response.getData() != null, "NG物料真实计量无法读取");
					return response.getData();
				});
		var h = new Header();
		h.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
		h.setTenantId(tenant);
		h.setFactoryCode(factory);
		h.setSourceUnqualifiedReportId(source.getId());
		h.setSourceBirthId(birth.getId());
		h.setActorId(source.getCapturedActorId());
		h.setRequestId(source.getRequestId());
		h.setTaskCode(task.getCode());
		h.setProduceTaskCode(arrange.getProduceTaskCode());
		h.setArrangeCode(arrange.getCode());
		h.setRouteNodeCode(arrange.getSourceRouteNodeCode());
		h.setGateId(arrange.getMaterialGateRecordId());
		h.setProcessedQuantity(source.getUnqualifiedNum());
		h.setProductionUnit(source.getReportUnit());
		h.setSourceFingerprint(source.getSourceFingerprint());
		h.setPolicySnapshot(codec.snapshot(policy));
		h.setRouteSnapshot(task.getProcessRouteSnapshot());
		h.setBomSnapshot(task.getBomSnapshot());
		var lines = new ArrayList<MesNgMaterialPolicyMapper.Line>();
		for (var f : plan.feeds()) {
			require(selected == null || selected.contains(f.feedId()), "NG实际投料不属于原绑定池完整成员");
			var birthProof = ledger.feedBirth(tenant, factory, f.feedId());
			require(birthProof != null && birthProof.getId() != null && birthProof.getId() > 0
							&& "MES_FEED_BIRTH_V1".equals(birthProof.getEvidenceVersion()) && "RECORDED".equals(birthProof.getStatus())
							&& Objects.equals(birthProof.getFeedId(), f.feedId()) && Objects.equals(birthProof.getTenantId(), tenant)
							&& Objects.equals(birthProof.getFactoryCode(), factory) && equal(birthProof.getQuantity(), f.feedQuantity())
							&& Objects.equals(birthProof.getUnit(), f.matUnit()) && birthProof.getSourceCount() != null
							&& birthProof.getSourceCount() >= 1 && birthProof.getSourceCount() <= 5000
							&& Objects.equals(birthProof.getEvidenceFingerprint(), codec.fingerprint("MES_FEED_BIRTH_V1", birthProof.getEvidenceSnapshot())),
					"POLICY不能用旧validator的NULL RETURN追认历史FEED，须真实出生主账和逐源全集");
			births.assertFeedBirth(f.feedId());
			var feed = feedRows.stream().filter(row -> Objects.equals(row.getId(), f.feedId())).findFirst().orElseThrow();
			require(Objects.equals(feed.getRequirementMatCode(), f.requirementMatCode())
							&& Objects.equals(feed.getSubstituteFlag(), f.substitute()) && equal(feed.getSubstituteRatio(), f.substituteRatio()),
					"POLICY投料原需求、比例或主替代标记未知/不同，不能补造新绑定");
			var line = line(h, "FEED", f.feedId(), f.requirementMatCode(), f.requirementUnit(), f.matCode(), f.matUnit(),
					f.consumeQuantity(), f.requirementQuantity(), f.substituteRatio(), f.substitute(), f.usedBefore(), f.usedAfter());
			var observation = new LinkedHashMap<String, Object>();
			observation.put("feed", feedFacts(feed));
			observation.put("birthId", birthProof.getId().toString());
			observation.put("birthVersion", birthProof.getEvidenceVersion());
			observation.put("birthSnapshot", birthProof.getEvidenceSnapshot());
			observation.put("birthFingerprint", birthProof.getEvidenceFingerprint());
			observation.put("feedQuantity", codec.amount(f.feedQuantity()));
			observation.put("materialId", f.materialId());
			observation.put("materialUpdatedAt", f.materialUpdatedAt());
			line.setSourceSnapshot(codec.snapshot(observation));
			var cas = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MesWarehouseFeedEntity>()
					.eq(MesWarehouseFeedEntity::getId, f.feedId()).eq(MesWarehouseFeedEntity::getTenantId, tenant)
					.eq(MesWarehouseFeedEntity::getFactoryCode, factory).eq(MesWarehouseFeedEntity::getFeedNum, f.feedQuantity())
					.and(w -> {
						w.eq(MesWarehouseFeedEntity::getUsedNum, f.usedBefore());
						if (f.usedBefore().signum() == 0) {
							w.or().isNull(MesWarehouseFeedEntity::getUsedNum);
						}
					});
			require(feeds.update(null, cas.set(MesWarehouseFeedEntity::getUsedNum, f.usedAfter())) == 1, "NG投料原份额CAS失败");
			var after = feeds.selectById(f.feedId());
			require(after != null && equal(after.getUsedNum(), f.usedAfter()) && feedFacts(after).equals(feedFacts(feed)), "NG投料实际回读精度或来源漂移");
			lines.add(line);
		}
		for (var allocation : plan.stocks()) {
			var stock = stockRows.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
					.eq(MesWarehouseLiveStockEntity::getTenantId, tenant).eq(MesWarehouseLiveStockEntity::getFactoryCode, factory)
					.eq(MesWarehouseLiveStockEntity::getId, allocation.stockId()).last("FOR UPDATE NOWAIT"));
			require(stock != null && Objects.equals(stock.getMatCode(), allocation.materialCode())
					&& Objects.equals(stock.getMatUnit(), allocation.materialUnit()) && equal(stock.getNum(), allocation.stockBefore()), "NG原库存份额余额已变化");
			eligibility.assertEligible(task, stock, allocation.requirementCode(), allocation.ratio());
			var out = stockRequest(arrange, allocation.requirementCode(), allocation.materialCode(), allocation.ratio(), allocation.substitute());
			out.setBillId(source.getId());
			out.setResourceNo(source.getRequestId());
			out.setMatUnit(allocation.materialUnit());
			out.setNum(allocation.actualQuantity());
			var receipt = stocks.outStockWithReceipt(out, new com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO.Lineage(
					allocation.reservationNo(), allocation.stockRequirementCode(), allocation.stockId()));
			verifyReceipt(receipt, out, allocation, tenant, factory);
			var line = line(h, "STOCK", allocation.stockId(), allocation.requirementCode(), allocation.requirementUnit(),
					allocation.materialCode(), allocation.materialUnit(), allocation.actualQuantity(), allocation.requirementQuantity(),
					allocation.ratio(), allocation.substitute(), allocation.stockBefore(), allocation.stockAfter());
			line.setSourceFlowId(receipt.lines().get(0).inoutId());
			line.setSourceSnapshot(codec.snapshot(Map.of("stock", stockFacts(stock), "receipt", receipt)));
			lines.add(line);
		}
		evidence.freeze(h, lines);
		require(ledger.insertHeader(h) == 1, "NG逐料原账未写入，整笔回滚");
		for (var line : lines) {
			require(ledger.insertLine(line) == 1, "NG原份额必要写失败，整笔回滚");
		}
		require(Objects.equals(original(source).getEvidenceSnapshot(), h.getEvidenceSnapshot()), "NG原账回执回读不一致");
	}

	/**
	 * 核验实际组织、原NG身份、原文SHA及完整typed份额后读取原账；仅真实不存在返回null，保护未安装或证据漂移均拒绝。
	 */
	public Header original(MesTaskReportUnqualifiedEntity source) {
		String factory = access.require("read");
		ready();
		require(source != null && Objects.equals(source.getTenantId(), com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
				&& Objects.equals(source.getFactoryCode(), factory), "NG原账读取跨实际组织");
		var h = ledger.original(source.getTenantId(), factory, source.getId());
		if (h == null) {
			return null;
		}
		require(sources.verify(source) && Objects.equals(h.getSourceUnqualifiedReportId(), source.getId())
						&& Objects.equals(h.getRequestId(), source.getRequestId()) && Objects.equals(h.getTenantId(), source.getTenantId())
						&& Objects.equals(h.getFactoryCode(), factory) && Objects.equals(h.getActorId(), source.getCapturedActorId())
						&& Objects.equals(h.getSourceFingerprint(), source.getSourceFingerprint())
						&& equal(h.getProcessedQuantity(), source.getUnqualifiedNum()) && Objects.equals(h.getProductionUnit(), source.getReportUnit()),
				"NG原账与真实来源不一致");
		evidence.verify(h, ledger.lines(h.getTenantId(), factory, h.getId()));
		return h;
	}

	/**
	 * 授权原来源读模型；原正量与恢复引用并列，不把已恢复的POLICY改成NONE。
	 */
	public MesNgMaterialPolicyDTO.Operation view(MesTaskReportUnqualifiedEntity source) {
		var h = original(source);
		if (h == null) {
			return null;
		}
		var lines = ledger.lines(h.getTenantId(), h.getFactoryCode(), h.getId());
		evidence.verify(h, lines);
		var r = ledger.attached(h.getTenantId(), h.getFactoryCode(), h.getId());
		var restored = new HashMap<Long, Long>();
		MesNgMaterialPolicyDTO.Receipt receipt = null;
		if (r != null) {
			var rows = ledger.restores(h.getTenantId(), h.getFactoryCode(), r.getId());
			evidence.verifyRestore(r, rows, h, lines);
			rows.forEach(row -> restored.put(row.getAllocationId(), row.getId()));
			receipt = new MesNgMaterialPolicyDTO.Receipt(r.getRequestId(), "APPLIED", r.getId().toString(), r.getOperationId().toString(),
					r.getTenantId().toString(), r.getFactoryCode(), r.getActorId().toString(), r.getCommandVersion(), r.getCommandSnapshot(),
					r.getCommandFingerprint(), r.getEvidenceVersion(), r.getEvidenceSnapshot(), r.getEvidenceFingerprint());
		}
		var shares = lines.stream().map(l -> new MesNgMaterialPolicyDTO.Share(l.getId().toString(), l.getSourceKind(), l.getSourceId().toString(),
				l.getSourceFlowId() == null ? null : l.getSourceFlowId().toString(), l.getRequirementMatCode(), l.getRequirementUnit(),
				l.getMatCode(), l.getMatUnit(), codec.amount(l.getQuantity()), codec.amount(l.getRequirementQuantity()), codec.amount(l.getSubstituteRatio()),
				l.getSubstituteFlag(), codec.amount(l.getBeforeQuantity()), codec.amount(l.getAfterQuantity()), l.getSourceSnapshot(),
				restored.containsKey(l.getId()) ? restored.get(l.getId()).toString() : null)).toList();
		return new MesNgMaterialPolicyDTO.Operation(h.getId().toString(), h.getSourceUnqualifiedReportId().toString(), h.getRequestId(),
				h.getTenantId().toString(), h.getFactoryCode(), h.getActorId().toString(), h.getTaskCode(), h.getArrangeCode(),
				h.getEvidenceVersion(), h.getEvidenceSnapshot(), h.getEvidenceFingerprint(), shares, receipt);
	}

	/**
	 * 检查实际V4保护目录；任一保护缺失即拒绝新POLICY或其账务读取，不能以服务可调用证明数据库已部署。
	 */
	public void ready() {
		require(Boolean.TRUE.equals(ledger.ready()), "NG POLICY完整耗用及恢复保护未就绪，请先按V4统一部署");
	}

	/**
	 * 按真实原账及规划份额构造一条分配事实；后续完整证据校验和数据库守卫负责数量守恒。
	 */
	private MesNgMaterialPolicyMapper.Line line(Header h, String kind, Long source, String requirement, String requirementUnit,
	                                            String material, String unit, java.math.BigDecimal quantity, java.math.BigDecimal equivalent, java.math.BigDecimal ratio,
	                                            boolean substitute, java.math.BigDecimal before, java.math.BigDecimal after) {
		var line = new MesNgMaterialPolicyMapper.Line();
		line.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
		line.setOperationId(h.getId());
		line.setTenantId(h.getTenantId());
		line.setFactoryCode(h.getFactoryCode());
		line.setSourceKind(kind);
		line.setSourceId(source);
		line.setRequirementMatCode(requirement);
		line.setRequirementUnit(requirementUnit);
		line.setMatCode(material);
		line.setMatUnit(unit);
		line.setQuantity(quantity);
		line.setRequirementQuantity(equivalent);
		line.setSubstituteRatio(ratio);
		line.setSubstituteFlag(substitute);
		line.setBeforeQuantity(before);
		line.setAfterQuantity(after);
		return line;
	}

	/**
	 * 冻结来源元数据；数量/audit变化不改写原来源身份。
	 */
	public Map<String, Object> stockFacts(MesWarehouseLiveStockEntity stock) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", stock.getId().toString());
		facts.put("tenantId", stock.getTenantId().toString());
		facts.put("factoryCode", stock.getFactoryCode());
		facts.put("materialCode", stock.getMatCode());
		facts.put("unit", stock.getMatUnit());
		facts.put("reservationNo", stock.getReservationNo());
		facts.put("requirementMaterialCode", stock.getRequirementMatCode());
		facts.put("sourceReportId", stock.getSourceReportId() == null ? null : stock.getSourceReportId().toString());
		facts.put("materialReceiptId", stock.getMaterialReceiptId() == null ? null : stock.getMaterialReceiptId().toString());
		facts.put("sourceUnqualifiedReportId", stock.getSourceUnqualifiedReportId() == null ? null : stock.getSourceUnqualifiedReportId().toString());
		facts.put("productionNgCaptureId", stock.getProductionNgCaptureId() == null ? null : stock.getProductionNgCaptureId().toString());
		facts.put("physicalTransformOutputId", stock.getPhysicalTransformOutputId() == null ? null : stock.getPhysicalTransformOutputId().toString());
		facts.put("batch", stock.getMatBatch());
		facts.put("barcode", stock.getMatBarcode());
		facts.put("warehouse", stock.getWarehouseCode());
		facts.put("produceTaskCode", stock.getProduceTaskCode());
		facts.put("detailType", stock.getMatDetailType());
		facts.put("billNo", stock.getBillNo());
		return facts;
	}

	/**
	 * 冻结原投料身份及原投料数量，数量单位为原matUnit；调用方须先验证真实出生和组织，不补造旧投料证据。
	 */
	public Map<String, Object> feedFacts(MesWarehouseFeedEntity feed) {
		var facts = new LinkedHashMap<String, Object>();
		facts.put("id", feed.getId().toString());
		facts.put("tenantId", feed.getTenantId().toString());
		facts.put("factoryCode", feed.getFactoryCode());
		facts.put("taskCode", feed.getTaskCode());
		facts.put("arrangeCode", feed.getArrangeCode());
		facts.put("produceTaskCode", feed.getProduceTaskCode());
		facts.put("materialCode", feed.getMatCode());
		facts.put("unit", feed.getMatUnit());
		facts.put("quantity", feed.getFeedNum() == null ? null : codec.amount(feed.getFeedNum()));
		return facts;
	}

	private boolean equal(java.math.BigDecimal a, java.math.BigDecimal b) {
		return a != null && b != null && a.compareTo(b) == 0;
	}

	/**
	 * 按原派工、需求及原BOM替代比率构造NG原生OUT请求；不能改成GOOD来源或通用入库。
	 */
	private MesWarehouseLiveStockInoutDTO stockRequest(MesTaskArrangeEntity arrange, String requirement,
	                                                   String material, BigDecimal ratio, boolean substitute) {
		var request = new MesWarehouseLiveStockInoutDTO();
		request.setMatCode(material);
		request.setFactoryCode(arrange.getFactoryCode());
		request.setBillNo(arrange.getCode());
		request.setRequirementMatCode(requirement);
		request.setSubstituteRatio(ratio);
		request.setSubstituteFlag(substitute);
		request.setProduceTaskCode(arrange.getProduceTaskCode());
		request.setAllowUnassignedProduceTask(true);
		request.setNum(BigDecimal.ONE);
		request.setMatDetailType("RAW");
		request.setBizType(WarehouseInBizTypeEnum.PRODUCTION_NG_MATERIAL_OUT.getCode());
		return request;
	}

	/**
	 * 逐字段核对真实原生OUT、原stockId和before/after精确份额；任一漂移都使原NG报工回滚。
	 */
	private void verifyReceipt(MesWarehouseStockOutReceiptDTO receipt, MesWarehouseLiveStockInoutDTO request,
	                           MesBackflushAllocationPlanner.Allocation allocation, Long tenant, String factory) {
		require(receipt != null && "STOCK_LINE_V1".equals(receipt.version())
						&& Objects.equals(receipt.tenantId(), tenant) && Objects.equals(receipt.factoryCode(), factory)
						&& Objects.equals(receipt.billId(), request.getBillId()) && Objects.equals(receipt.billNo(), request.getBillNo())
						&& Objects.equals(receipt.bizType(), request.getBizType()) && Objects.equals(receipt.materialCode(), allocation.materialCode())
						&& Objects.equals(receipt.materialUnit(), allocation.materialUnit())
						&& equal(receipt.quantity(), allocation.actualQuantity()) && receipt.lines().size() == 1,
				"逐料倒冲真实出库回执不完整，整次原报工回滚");
		var line = receipt.lines().get(0);
		require(line.inoutId() != null && line.inoutId() > 0 && Objects.equals(line.stockId(), allocation.stockId())
						&& Objects.equals(line.tenantId(), tenant) && Objects.equals(line.factoryCode(), factory)
						&& Objects.equals(line.billId(), request.getBillId()) && Objects.equals(line.billNo(), request.getBillNo())
						&& Objects.equals(line.resourceNo(), request.getResourceNo()) && Objects.equals(line.bizType(), request.getBizType())
						&& Objects.equals(line.materialCode(), allocation.materialCode()) && Objects.equals(line.materialUnit(), allocation.materialUnit())
						&& Objects.equals(line.requirementMaterialCode(), allocation.requirementCode())
						&& Objects.equals(line.substitute(), allocation.substitute()) && equal(line.substituteRatio(), allocation.ratio())
						&& equal(line.quantity(), allocation.actualQuantity()) && equal(line.beforeQuantity(), allocation.stockBefore())
						&& equal(line.afterQuantity(), allocation.stockAfter()) && "STOCK_LINE_V1".equals(line.proofVersion()),
				"实际OUT与本次原库存份额不一致，不能用其他行或舍入量代替");
	}

}
