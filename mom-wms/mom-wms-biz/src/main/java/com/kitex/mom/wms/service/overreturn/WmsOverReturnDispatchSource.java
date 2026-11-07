package com.kitex.mom.wms.service.overreturn;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.DispatchCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.overreturn.WmsOverReturnRules.*;

/**
 * 在原差异处置事务内核完整补扣链并冻结逐份额原件，不恢复源仓库存。
 */
@Component
@RequiredArgsConstructor
public class WmsOverReturnDispatchSource {
	private final WmsOverReturnAccess access;
	private final WmsOverReturnStore store;
	private final WmsOverReturnCodec codec;
	private final WmsInoutFlowMapper flows;
	private final WmsWarehouseBusinessGuard business;

	/**
	 * 必须在原case、bill锁之后调用，全部失败随原处置事务回滚。
	 */
	public void freeze(WmsTransferVarianceCaseEntity c, WmsAllocationBillEntity b,
	                   List<WmsTransferVarianceLineEntity> lines, DispatchCommand command, LocalDateTime now) {
		var s = access.scope();
		access.target(c.getTargetWarehouseCode());
		store.ready();
		business.check(c.getTargetWarehouseCode(), "allocation", true);
		require(Objects.equals(s.tenant(), c.getTenantId()) && Objects.equals(s.factory(), c.getFactoryCode()) &&
				Objects.equals(s.tenant(), b.getTenantId()) && Objects.equals(s.factory(), b.getFactoryCode()) &&
				Objects.equals(c.getSourceWarehouseCode(), b.getOldWarehouseCode()) &&
				Objects.equals(c.getTargetWarehouseCode(), b.getNewWarehouseCode()) &&
				"DISCREPANCY_PENDING".equals(b.getAllocationState()), "差异单与原调拨范围或状态不符");
		require("APPROVE".equals(c.getWarehouseDecision()) &&
						(!Boolean.TRUE.equals(c.getPlanReviewRequired()) || "APPROVE".equals(c.getPlanDecision())) &&
						(!Boolean.TRUE.equals(c.getQualityReviewRequired()) || "APPROVE".equals(c.getQualityDecision())),
				"原差异必经审核未完成");
		var adjustments = flows.selectList(
				new LambdaQueryWrapper<WmsInoutFlowEntity>().eq(WmsInoutFlowEntity::getTenantId, s.tenant())
						.eq(WmsInoutFlowEntity::getFactoryCode, s.factory())
						.eq(WmsInoutFlowEntity::getVarianceCaseId, c.getId())
						.eq(WmsInoutFlowEntity::getTransferAction, "OVER_ISSUE_ADJUST")
						.orderByAsc(WmsInoutFlowEntity::getId).last("FOR UPDATE"));
		require(!adjustments.isEmpty() && store.manifests(s.tenant(), s.factory(), c.getId()).isEmpty(),
				"缺少补扣原件或已经发出");
		Map<Long, WmsTransferVarianceLineEntity> lineMap = new HashMap<>();
		for (var l : lines) {
			require(Objects.equals(s.tenant(), l.getTenantId()) && Objects.equals(s.factory(), l.getFactoryCode()) &&
					Objects.equals(c.getId(), l.getCaseId()), "差异行组织不一致");
			lineMap.put(l.getId(), l);
		}
		Map<Long, BigDecimal> totals = new HashMap<>();
		for (var a : adjustments) {
			var l = lineMap.get(a.getVarianceLineId());
			require(l != null && a.getSourceStockId() != null && a.getSourceStockId() > 0 &&
					Objects.equals(a.getBillId(), b.getId()) &&
					Objects.equals(a.getWarehouseCode(), c.getSourceWarehouseCode()) &&
					Objects.equals(a.getSourceFlowId(), l.getSourceFlowId()) &&
					InOutEnum.OUT.getCode().equals(a.getOperateType()), "补扣链不是原单源仓真实出库");
			var issue = flows.selectById(l.getSourceFlowId());
			require(issue != null && Objects.equals(issue.getTenantId(), s.tenant()) &&
					Objects.equals(issue.getFactoryCode(), s.factory()) &&
					Objects.equals(issue.getBillId(), b.getId()) && "ISSUE".equals(issue.getTransferAction()) &&
					Objects.equals(issue.getWarehouseCode(), c.getSourceWarehouseCode()) &&
					Objects.equals(issue.getMatCode(), a.getMatCode()) &&
					Objects.equals(issue.getMatBatch(), a.getMatBatch()) &&
					Objects.equals(issue.getMatUnit(), a.getMatUnit()), "补扣与原ISSUE物料、批次、单位或范围不一致");
			require(Objects.equals(l.getMaterialCode(), a.getMatCode()) &&
					Objects.equals(l.getMaterialBatch(), a.getMatBatch()) &&
					Objects.equals(l.getMaterialUnit(), a.getMatUnit()), "补扣与原差异行物料、批次或单位不符");
			text(a.getMatUnit(), 100, "原单位");
			require("OWNED".equals(a.getStockOwnerType()) && a.getSerialManaged() != null,
					"普通调拨须有明确自有货权及序列属性");
			require(a.getStockInTime() != null && a.getMatNum() != null && Double.isFinite(a.getMatNum()),
					"缺少原件库龄或数量");
			var q = quantity(decimal(BigDecimal.valueOf(a.getMatNum())));
			require(store.actualQuantity(s.tenant(), a.getId()).compareTo(q) == 0,
					"原Double数量不能无损表示数据库实物数量");
			if (Boolean.TRUE.equals(a.getSerialManaged())) {
				require(a.getMatBarcode() != null && !a.getMatBarcode().isBlank() && q.compareTo(BigDecimal.ONE) == 0,
						"单件补扣原件不完整");
			}
			require((a.getSourceInBillId() == null) == (a.getSourceInBillDetailId() == null) &&
					(a.getSourceReceiptId() == null) == (a.getSourceReceiptDetailId() == null), "原件成对来源证据缺失");
			require(flows.selectCount(
							new LambdaQueryWrapper<WmsInoutFlowEntity>().eq(WmsInoutFlowEntity::getTenantId, s.tenant())
									.eq(WmsInoutFlowEntity::getSourceFlowId, a.getId())
									.in(WmsInoutFlowEntity::getTransferAction, List.of("OVER_RETURN", "OVER_ACCEPT"))) == 0,
					"补扣原件已入库或冲突");
			totals.merge(l.getId(), q, BigDecimal::add);
			store.manifest(IdUtil.getSnowflakeNextId(), s, c.getId(), b.getId(), l.getId(), a.getId(),
					c.getSourceWarehouseCode(), c.getTargetWarehouseCode(), command.getRequestId(), q,
					codec.snapshot(a), command.getDispatchEvidence(), now);
		}
		for (var l : lines) {
			BigDecimal over = l.getOverQuantity() == null ? BigDecimal.ZERO : l.getOverQuantity();
			require(totals.getOrDefault(l.getId(), BigDecimal.ZERO).compareTo(over) == 0 &&
							(l.getHeldOverQuantity() == null ? BigDecimal.ZERO : l.getHeldOverQuantity()).compareTo(over) == 0,
					"逐行补扣、溢收和隔离实物数量不守恒");
		}
	}
}
