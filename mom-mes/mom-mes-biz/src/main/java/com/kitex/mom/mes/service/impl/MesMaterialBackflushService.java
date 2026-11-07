package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.InOutEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.service.impl.material.MesBackflushAllocationPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原报告同事务的逐需求倒冲；真实OUT逐库存行回执供原报告冲销沿原份额恢复。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialBackflushService {
	private final MesActualFactoryAccessService access;
	private final MesExecutionTaskLock locks;
	private final MesTaskMapper tasks;
	private final MesTaskArrangeMapper arrangements;
	private final MesProductionFeedBatchMapper births;
	private final MesWarehouseInoutMapper flows;
	private final MesMaterialConsumptionModeResolver modes;
	private final MesWarehouseLiveStockService stocks;
	private final RemoteMaterialService materials;
	private final MesBackflushAllocationPlanner planner;
	private final MesPhysicalTransformCodec codec;

	/**
	 * 在真实GOOD产出及报工的同一事务内，按原开工逐需求政策实际倒冲一次。
	 * request须指向同组织已落账的原报告与出生凭据；调用方不得提前独立提交。
	 * 持原父单和派工锁，逐原库存校验单位、替代比例、前后量及完整OUT回执；任一不符整笔回滚。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void consumeGood(MesTaskReportVO request) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(request != null && request.getTask() != null && request.getTaskArrange() != null
						&& request.getReportId() != null && request.getReportId() > 0 && tenant != null && tenant > 0,
				"倒冲缺少真实原报告及执行归属");
		var owner = locks.arrange(request.getTaskArrange().getCode());
		var actualArranges = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, request.getTaskArrange().getCode())
				.last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(actualArranges != null && actualArranges.size() == 1, "倒冲派工未唯一归属当前组织");
		var arrange = actualArranges.get(0);
		locks.assertArrange(owner, arrange);
		var actualTasks = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(actualTasks != null && actualTasks.size() == 1, "倒冲父工单不唯一，不能猜快照");
		var task = actualTasks.get(0);
		require(Objects.equals(task.getTenantId(), tenant) && Objects.equals(task.getFactoryCode(), factory)
						&& Objects.equals(task.getCode(), arrange.getTaskCode())
						&& Objects.equals(arrange.getTenantId(), tenant) && Objects.equals(arrange.getFactoryCode(), factory)
						&& Objects.equals(request.getTask().getCode(), task.getCode())
						&& Objects.equals(request.getTask().getProcessRouteSnapshot(), task.getProcessRouteSnapshot())
						&& Objects.equals(request.getTask().getBomSnapshot(), task.getBomSnapshot()),
				"倒冲调用的原工单/BOM/工艺与实际冻结来源不一致");
		var birth = births.actualBirth(tenant, factory, "GOOD", request.getReportId());
		require(birth != null && birth.getId() != null && birth.getId() > 0
						&& Boolean.TRUE.equals(birth.getSameTransaction()) && "GOOD".equals(birth.getOutputKind())
						&& Objects.equals(birth.getTenantId(), tenant) && Objects.equals(birth.getFactoryCode(), factory)
						&& Objects.equals(birth.getSourceId(), request.getReportId()),
				"倒冲仅接受本事务首次真实GOOD报告，不能补旧报告或伪装NG");
		var facts = codec.objectDocument(birth.getSourceSnapshot(), 32 * 1024 * 1024);
		require(Objects.equals(facts.path("id").asText(), request.getReportId().toString())
						&& Objects.equals(facts.path("tenant_id").asText(), tenant.toString())
						&& Objects.equals(facts.path("factory_code").asText(), factory)
						&& Objects.equals(facts.path("task_code").asText(), task.getCode())
						&& Objects.equals(facts.path("arrange_code").asText(), arrange.getCode())
						&& Objects.equals(facts.path("produce_task_code").asText(), arrange.getProduceTaskCode())
						&& Objects.equals(facts.path("mat_code").asText(), arrange.getMatCode())
						&& Objects.equals(facts.path("request_id").asText(), request.getRequestId())
						&& "0".equals(facts.path("del_flag").asText()) && "EFFECTIVE".equals(facts.path("status").asText())
						&& !"ROUTE_RETURN".equals(facts.path("execution_kind").asText()),
				"倒冲原报告身份、原号、生产状态或原产出不一致");
		codec.code(facts.path("report_unit").asText(), "原报告生产单位", 100);
		BigDecimal quantity = codec.quantity(birth.getQuantity(), false, "原GOOD加工量");
		require(request.getPassNum() != null && quantity.compareTo(request.getPassNum()) == 0
						&& codec.quantity(birth.getEffectiveQuantity(), false, "原GOOD有效量").compareTo(quantity) == 0
						&& codec.quantity(birth.getCorrectedQuantity(), true, "原GOOD纠正量").signum() == 0,
				"倒冲加工量必须为本次未纠正的原GOOD量");
		Long prior = flows.selectCount(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
				.eq(MesWarehouseInoutEntity::getTenantId, tenant).eq(MesWarehouseInoutEntity::getFactoryCode, factory)
				.eq(MesWarehouseInoutEntity::getBillId, request.getReportId())
				.eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode())
				.eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode()));
		require(prior != null && prior == 0, "原GOOD已有倒冲流水，禁止重复扣库");
		var selection = modes.selection(arrange);
		var route = com.kitex.mom.mes.util.MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(),
				arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
		var requirements = MesMaterialConsumptionModeResolver.partition(selection.mode(),
				route.getMdmProcessProcedureMatList(),
				MesMaterialConsumptionModeResolver.Mode.BACKFLUSH);
		var allocations = planner.plan(arrange, quantity, requirements, task.getBomSnapshot(),
				r -> stocks.previewOutCandidates(
						stockRequest(arrange, r.requirementCode(), r.candidate().actualMatCode(),
								r.candidate().ratio(), r.candidate().substitute())), code -> {
					var response = materials.productionMeasure(code);
					require(response != null && response.isOk() && response.getData() != null,
							"倒冲实际物料计量读取失败");
					return response.getData();
				});
		for (var allocation : allocations) {
			var out = stockRequest(arrange, allocation.requirementCode(), allocation.materialCode(),
					allocation.ratio(), allocation.substitute());
			out.setBillId(request.getReportId());
			out.setResourceNo(request.getRequestId());
			out.setMatUnit(allocation.materialUnit());
			out.setNum(allocation.actualQuantity());
			var receipt = stocks.outStockWithReceipt(out, new MesWarehouseStockOutReceiptDTO.Lineage(
					allocation.reservationNo(), allocation.stockRequirementCode(), allocation.stockId()));
			verifyReceipt(receipt, out, allocation, tenant, factory);
		}
	}

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
		request.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
		return request;
	}

	private void verifyReceipt(MesWarehouseStockOutReceiptDTO receipt, MesWarehouseLiveStockInoutDTO request,
	                           MesBackflushAllocationPlanner.Allocation allocation, Long tenant, String factory) {
		require(receipt != null && "STOCK_LINE_V1".equals(receipt.version())
						&& Objects.equals(receipt.tenantId(), tenant) && Objects.equals(receipt.factoryCode(), factory)
						&& Objects.equals(receipt.billId(), request.getBillId()) &&
						Objects.equals(receipt.billNo(), request.getBillNo())
						&& Objects.equals(receipt.bizType(), request.getBizType()) &&
						Objects.equals(receipt.materialCode(), allocation.materialCode())
						&& Objects.equals(receipt.materialUnit(), allocation.materialUnit())
						&& equal(receipt.quantity(), allocation.actualQuantity()) && receipt.lines().size() == 1,
				"逐料倒冲真实出库回执不完整，整次原报工回滚");
		var line = receipt.lines().get(0);
		require(line.inoutId() != null && line.inoutId() > 0 && Objects.equals(line.stockId(), allocation.stockId())
						&& Objects.equals(line.tenantId(), tenant) && Objects.equals(line.factoryCode(), factory)
						&& Objects.equals(line.billId(), request.getBillId()) &&
						Objects.equals(line.billNo(), request.getBillNo())
						&& Objects.equals(line.resourceNo(), request.getResourceNo()) &&
						Objects.equals(line.bizType(), request.getBizType())
						&& Objects.equals(line.materialCode(), allocation.materialCode()) &&
						Objects.equals(line.materialUnit(), allocation.materialUnit())
						&& Objects.equals(line.requirementMaterialCode(), allocation.requirementCode())
						&& Objects.equals(line.substitute(), allocation.substitute()) &&
						equal(line.substituteRatio(), allocation.ratio())
						&& equal(line.quantity(), allocation.actualQuantity()) &&
						equal(line.beforeQuantity(), allocation.stockBefore())
						&& equal(line.afterQuantity(), allocation.stockAfter()) && "STOCK_LINE_V1".equals(line.proofVersion()),
				"实际OUT与本次原库存份额不一致，不能用其他行或舍入量代替");
	}

	private boolean equal(BigDecimal left, BigDecimal right) {
		return left != null && right != null && left.compareTo(right) == 0;
	}
}
