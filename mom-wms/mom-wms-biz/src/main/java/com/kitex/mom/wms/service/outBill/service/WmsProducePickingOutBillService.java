package com.kitex.mom.wms.service.outBill.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsPickingAreaEntity;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.service.WmsPickingAreaService;
import com.kitex.mom.wms.service.impl.WmsLineDeliveryService;
import com.kitex.mom.wms.service.impl.WmsMesIssueOutboxService;
import com.kitex.mom.wms.service.outBill.AbstractWmsOutBillHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生产发料使用实际扣减库存冻结可普通签收的质量快照，并同事务建立配送及MES待投递事实。
 */
@Component
@RequiredArgsConstructor
public class WmsProducePickingOutBillService extends AbstractWmsOutBillHandler {
	private static final Set<String> RECEIVABLE_QUALITY_RESULTS = Set.of("QUALIFIED", "EXEMPTION", "RELEASE",
			"RECEIVE");
	private final WmsMesIssueOutboxService issueOutboxService;
	private final WmsLineDeliveryService lineDeliveryService;
	private final WmsPickingAreaService pickingAreaService;

	@Override
	public Boolean support(String billType) {
		return OutBillTypeEnum.PRODUCE_OUT.getCode().equals(billType);
	}

	/**
	 * 原库存已在同事务完成锁内扣减和标准数量守恒；只冻结当前明细和本次消耗事实，不改原库存或既存流水。
	 * 未明确放行或同一明细混合不同结论时拒绝，整笔原事务回滚，不能借草稿结论生成MES事件。
	 *
	 * @param outBill    当前生产发料原主单
	 * @param detail     本次已持久化的出库明细
	 * @param stockOuted 真实扣减快照；各行matNum为本次扣减的库存标准单位数量
	 */
	@Override
	protected void freezeActualOutboundQuality(WmsOutBillEntity outBill, WmsOutBillDetailEntity detail,
	                                           List<WmsLiveStockEntity> stockOuted) {
		if (outBill.getId() == null || detail.getId() == null || !Objects.equals(outBill.getId(), detail.getBillId())) {
			throw new CheckedException("生产发料质量冻结缺少当前出库明细来源");
		}
		List<String> qualities = stockOuted.stream().map(this::receivableQuality).distinct().toList();
		if (qualities.size() != 1) {
			throw new CheckedException("同一出库明细的源质量结论不一致，请按质量结论拆分发料");
		}
		String quality = qualities.get(0);
		requireCompatiblePickingQuality(outBill, detail, quality);
		int updated = wmsOutBillDetailMapper.update(null, new LambdaUpdateWrapper<WmsOutBillDetailEntity>()
				.eq(WmsOutBillDetailEntity::getId, detail.getId())
				.eq(WmsOutBillDetailEntity::getBillId, outBill.getId())
				.eq(WmsOutBillDetailEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsOutBillDetailEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
				.set(WmsOutBillDetailEntity::getQualityResult, quality));
		if (updated != 1) {
			throw new CheckedException("生产发料源质量快照保存未确认，出库整体回滚");
		}
		detail.setQualityResult(quality);
		// MyBatis不自动脏写返回实体；仅规范本次扣减事实供新流水/新拣配落证，禁止update原库存或补旧记录。
		// 显式免检的0标记不会复制进PickingArea，须在本次事实中冻结NOT_REQUIRED以免后续丢失依据。
		stockOuted.forEach(stock -> stock.setQualityResult(quality));
	}

	/**
	 * 扣库后才锁同原单拣配，核当前明细与此前实际质量同质；其它既存明细必须已有相同的明确冻结快照。
	 * 旧partial缺来源/质量或混合结论时拒绝，不能凭客户端、旧明细字段或回填旧拣配冒充来源证明。
	 */
	private void requireCompatiblePickingQuality(WmsOutBillEntity outBill, WmsOutBillDetailEntity detail,
	                                             String quality) {
		List<WmsPickingAreaEntity> picked = pickingAreaService.list(new LambdaQueryWrapper<WmsPickingAreaEntity>()
				.eq(WmsPickingAreaEntity::getBillNo, outBill.getBillNo())
				.eq(WmsPickingAreaEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsPickingAreaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
				.orderByAsc(WmsPickingAreaEntity::getId).last("FOR UPDATE"));
		Map<Long, String> previousQualities = new HashMap<>();
		for (WmsPickingAreaEntity row : picked) {
			String previousQuality = row.getQualityResult();
			if (!Objects.equals(row.getBillId(), outBill.getId()) || row.getBillDetailId() == null
					|| row.getSourceStockId() == null || row.getSourceStockId() <= 0
					|| !Objects.equals(row.getWarehouseCode(), outBill.getWarehouseCode())
					|| row.getMatNum() == null || !Double.isFinite(row.getMatNum()) || row.getMatNum() <= 0
					|| previousQuality == null || (!RECEIVABLE_QUALITY_RESULTS.contains(previousQuality)
					&& !"NOT_REQUIRED".equals(previousQuality))) {
				throw new CheckedException("原单既有拣配缺少真实来源或明确质量，须先核对原拣配事实");
			}
			String previous = previousQualities.putIfAbsent(row.getBillDetailId(), previousQuality);
			if ((previous != null && !previous.equals(previousQuality))
					|| (Objects.equals(row.getBillDetailId(), detail.getId()) && !quality.equals(previousQuality))) {
				throw new CheckedException("同一出库明细的既有拣配与本次源质量不一致，禁止混合发料");
			}
		}
		if (InOutBillStateEnum.APP_RUNNING.getCode().equals(outBill.getState())) {
			requireCompletePickingQuantities(outBill, detail, picked, previousQualities);
			return;
		}
		List<Long> otherDetails = previousQualities.keySet().stream().filter(id -> !Objects.equals(id, detail.getId()))
				.toList();
		if (!otherDetails.isEmpty()) {
			List<WmsOutBillDetailEntity> frozen = wmsOutBillDetailMapper.selectList(
					new LambdaQueryWrapper<WmsOutBillDetailEntity>()
							.eq(WmsOutBillDetailEntity::getBillId, outBill.getId())
							.eq(WmsOutBillDetailEntity::getTenantId, TenantContextHolder.getTenantId())
							.eq(WmsOutBillDetailEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
							.in(WmsOutBillDetailEntity::getId, otherDetails));
			// 既有明细只用于回核与真实拣配相同的冻结镜像，不作为质量权威，也不在此回填历史。
			if (frozen.size() != otherDetails.size() || frozen.stream().anyMatch(row ->
					!Objects.equals(row.getQualityResult(), previousQualities.get(row.getId())))) {
				throw new CheckedException("原单既有明细质量与实际拣配未形成明确冻结，禁止继续发料");
			}
		}
	}

	/**
	 * PDA内存量是本次增量，持久明细为累计量；逐明细核实际既存拣配加本次量完整覆盖累计量。
	 * 各份额均按该明细的标准单位核算，不跨物料/单位相加；缺失、超额或未知旧份额只拒绝，不补账改量。
	 */
	private void requireCompletePickingQuantities(WmsOutBillEntity outBill, WmsOutBillDetailEntity detail,
	                                              List<WmsPickingAreaEntity> picked,
	                                              Map<Long, String> previousQualities) {
		List<WmsOutBillDetailEntity> persisted = wmsOutBillDetailMapper.selectList(
				new LambdaQueryWrapper<WmsOutBillDetailEntity>()
						.eq(WmsOutBillDetailEntity::getBillId, outBill.getId())
						.eq(WmsOutBillDetailEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsOutBillDetailEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
						.orderByAsc(WmsOutBillDetailEntity::getId).last("FOR SHARE"));
		Map<Long, WmsOutBillDetailEntity> byId = new HashMap<>();
		for (WmsOutBillDetailEntity row : persisted) {
			if (row.getId() == null || row.getMatNum() == null || !Double.isFinite(row.getMatNum())
					|| row.getMatNum() <= 0 || StrUtil.isBlank(row.getMatUnit()) ||
					byId.put(row.getId(), row) != null) {
				throw new CheckedException("原单持久明细累计数量或标准单位无效，禁止继续拣货");
			}
		}
		if (!byId.containsKey(detail.getId()) || detail.getMatNum() == null || !Double.isFinite(detail.getMatNum())
				|| detail.getMatNum() <= 0) {
			throw new CheckedException("当前拣货缺少持久明细或本次真实数量，禁止冻结质量");
		}
		Map<Long, BigDecimal> pickedQuantities = new HashMap<>();
		for (WmsPickingAreaEntity row : picked) {
			WmsOutBillDetailEntity original = byId.get(row.getBillDetailId());
			if (original == null || !Objects.equals(original.getMatCode(), row.getMatCode())
					|| !Objects.equals(original.getMatUnit(), row.getMatUnit())) {
				throw new CheckedException("既存拣配缺少同源持久明细或标准单位不一致，禁止继续发料");
			}
			pickedQuantities.merge(row.getBillDetailId(), BigDecimal.valueOf(row.getMatNum()), BigDecimal::add);
		}
		for (WmsOutBillDetailEntity row : persisted) {
			boolean current = Objects.equals(row.getId(), detail.getId());
			BigDecimal represented = pickedQuantities.getOrDefault(row.getId(), BigDecimal.ZERO);
			if (current) {
				if (!Objects.equals(row.getMatUnit(), detail.getMatUnit())) {
					throw new CheckedException("本次拣货与持久累计明细标准单位不一致");
				}
				represented = represented.add(BigDecimal.valueOf(detail.getMatNum()));
			}
			if (represented.compareTo(BigDecimal.valueOf(row.getMatNum())) != 0) {
				throw new CheckedException("原单既存拣配及本次实物份额与持久累计数量不守恒，禁止覆盖旧份额质量");
			}
			if (!current && !Objects.equals(row.getQualityResult(), previousQualities.get(row.getId()))) {
				throw new CheckedException("原单既存明细质量与实际拣配未形成明确冻结，禁止继续发料");
			}
		}
	}

	/**
	 * 仅接受WMS出库与MES普通签收共同允许的真实结论；显式免检且无结论才冻结NOT_REQUIRED。
	 * 未知、待检、让步及其它处置状态不能转成普通可用，原库存结论始终保持原值。
	 */
	private String receivableQuality(WmsLiveStockEntity stock) {
		if (stock == null || (StrUtil.isNotBlank(stock.getQualityStatus())
				&& !Set.of("QUALIFIED", "RELEASED").contains(stock.getQualityStatus()))) {
			throw new CheckedException("生产发料源库存质量状态未明确放行，不能普通签收");
		}
		String result = stock.getQualityResult();
		if (result != null && RECEIVABLE_QUALITY_RESULTS.contains(result)) {
			return result;
		}
		if ("0".equals(stock.getIsQualityTest()) && StrUtil.isBlank(result)) {
			return "NOT_REQUIRED";
		}
		throw new CheckedException("生产发料源库存缺少明确放行或免检依据，不能普通签收");
	}

	/**
	 * 发料与可靠投递事实同事务落库；远端不可用不再导致发料/收料通知跨域半提交。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void handleDocumentBizWhenAdd(WmsOutBillEntity bill) {
		lineDeliveryService.ensureTask(bill);
		issueOutboxService.enqueue(bill);
	}
}
