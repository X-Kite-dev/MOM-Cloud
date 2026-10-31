package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.dto.app.WmsLiveStockMoveDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsAllocationMobileCancelReceiptMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnReceiptMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnExceptionLineMapper;
import com.kitex.mom.wms.mapper.WmsContainerBarcodeMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsTransferVarianceLineMapper;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import com.kitex.mom.wms.vo.WmsAllocationBillExcelVO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 调拨单
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Service
@AllArgsConstructor
public class WmsAllocationBillServiceImpl extends ServiceImpl<WmsAllocationBillMapper, WmsAllocationBillEntity> implements WmsAllocationBillService {

	private static final String IN_TRANSIT = "IN_TRANSIT";
	private static final String PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";
	private static final String RETURN_PENDING = "RETURN_PENDING";
	private static final String RETURN_EXCEPTION_PENDING = "RETURN_EXCEPTION_PENDING";
	private static final String RETURN_EXCEPTION_CLOSED = "RETURN_EXCEPTION_CLOSED";
	private static final String RECEIVED = "RECEIVED";
	private static final String CANCELLED = "CANCELLED";
	private static final String PARTIALLY_CANCELLED = "PARTIALLY_CANCELLED";
	/**
	 * 仅供已发布v6工具按冻结契约重试；v7及以后必须使用路线策略。
	 */
	private static final int LEGACY_V6_TRANSFER_LEAD_HOURS = 24;
	private final WmsInoutFlowMapper wmsInoutFlowMapper;
	private final WmsAllocationMobileCancelReceiptMapper mobileCancelReceipts;
	private final WmsAllocationReturnReceiptMapper wmsAllocationReturnReceiptMapper;
	private final WmsAllocationReturnExceptionLineMapper wmsAllocationReturnExceptionLineMapper;
	private final WmsInoutFlowService wmsInoutFlowService;
	private final WmsLiveStockService wmsLiveStockService;
	private final RemoteWarehouseLocationService remoteWarehouseLocationService;
	private final RemoteWarehouseService remoteWarehouseService;
	private final RemoteMaterialService remoteMaterialService;
	private final PermissionService permissionService;
	private final WmsContainerBarcodeMapper wmsContainerBarcodeMapper;
	private final WmsMaterialReservationService wmsMaterialReservationService;
	private final WmsTransferRoutePolicyService wmsTransferRoutePolicyService;
	private final WmsTransferVarianceLineMapper wmsTransferVarianceLineMapper;
	private final WmsPlanShortageEventOutboxService wmsPlanShortageEventOutboxService;
	private final WmsWarehouseBusinessGuard warehouseBusinessGuard;

	/**
	 * 新增调拨单
	 *
	 * @param wmsAllocationBillAdd 调拨单接收DTO
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveAllocationBill(WmsAllocationBillAddDTO wmsAllocationBillAdd) {
		require(wmsAllocationBillAdd != null, "调拨请求不能为空");
		requireText(wmsAllocationBillAdd.getRequestId(), "调拨请求唯一号不能为空");
		requireCode(wmsAllocationBillAdd.getBillNo(), "调拨单号不能为空");
		wmsAllocationBillAdd.setRequestId(wmsAllocationBillAdd.getRequestId().trim());
		wmsAllocationBillAdd.setBillNo(wmsAllocationBillAdd.getBillNo().trim());
		wmsAllocationBillAdd.setOldWarehouseCode(StrUtil.trim(wmsAllocationBillAdd.getOldWarehouseCode()));
		wmsAllocationBillAdd.setNewWarehouseCode(StrUtil.trim(wmsAllocationBillAdd.getNewWarehouseCode()));
		require(StrUtil.isNotBlank(wmsAllocationBillAdd.getOldWarehouseCode())
				&& StrUtil.isNotBlank(wmsAllocationBillAdd.getNewWarehouseCode()), "源仓和目标仓不能为空");
		require(!Objects.equals(wmsAllocationBillAdd.getOldWarehouseCode(), wmsAllocationBillAdd.getNewWarehouseCode()),
				"源仓和目标仓不能相同");
		require(wmsAllocationBillAdd.getDetails() != null && !wmsAllocationBillAdd.getDetails().isEmpty(),
				"调拨明细不能为空");
		String factoryCode = StrUtil.blankToDefault(FactoryContextHolder.getFactoryCode(),
				wmsAllocationBillAdd.getFactoryCode());
		requireCode(factoryCode, "当前工厂不能为空");
		wmsAllocationBillAdd.setFactoryCode(factoryCode);
		MdmWarehouseEntity sourceWarehouse = requireWarehouse(wmsAllocationBillAdd.getOldWarehouseCode(), factoryCode);
		MdmWarehouseEntity targetWarehouse = requireWarehouse(wmsAllocationBillAdd.getNewWarehouseCode(), factoryCode);
		requireWarehousePermission("wms_wmsAllocationBill_add", sourceWarehouse.getWarehouseCode(), "无权从该仓库发起调拨");
		String requestSnapshot = issueRequestSnapshot(wmsAllocationBillAdd);
		WmsAllocationBillEntity existing = getOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getRequestId, wmsAllocationBillAdd.getRequestId()), false);
		if (existing != null) {
			require(Objects.equals(existing.getRequestSnapshot(), requestSnapshot), "调拨幂等号已被不同请求使用");
			return Boolean.TRUE;
		}
		requireTransferConfiguration(sourceWarehouse.getWarehouseCode(), targetWarehouse.getWarehouseCode());
		require(count(new LambdaQueryWrapper<WmsAllocationBillEntity>()
						.eq(WmsAllocationBillEntity::getBillNo, wmsAllocationBillAdd.getBillNo())) == 0,
				"调拨单号已存在");
		MomUser user = SecurityUtils.getUser();
		WmsAllocationBillEntity wmsAllocationBill = new WmsAllocationBillEntity();
		BeanUtils.copyProperties(wmsAllocationBillAdd, wmsAllocationBill);
		wmsAllocationBill.setOldWarehouseName(sourceWarehouse.getWarehouseName());
		wmsAllocationBill.setNewWarehouseName(targetWarehouse.getWarehouseName());
		wmsAllocationBill.setFactoryCode(factoryCode);
		wmsAllocationBill.setRequestSnapshot(requestSnapshot);
		wmsAllocationBill.setAllocationState(IN_TRANSIT);
		wmsAllocationBill.setTransferPurpose(StrUtil.blankToDefault(wmsAllocationBillAdd.getTransferPurpose(),
				"agent".equals(wmsAllocationBillAdd.getBillType()) ? "AGENT" : "NORMAL"));
		wmsAllocationBill.setSourceDeducted(Boolean.TRUE);
		wmsAllocationBill.setIssueTime(LocalDateTime.now());
		wmsAllocationBill.setIssueBy(user.getUsername());
		wmsAllocationBill.setIssueByName(operatorName(user));
		require(this.save(wmsAllocationBill), "调拨单保存失败");

		Set<Long> sourceIds = new HashSet<>();
		for (WmsDetailDTO detail : wmsAllocationBillAdd.getDetails()) {
			Long sourceStockId = detail.getSourceStockId() == null ? detail.getBillId() : detail.getSourceStockId();
			require(sourceStockId != null && sourceIds.add(sourceStockId), "调拨源库存不能为空或重复");
			require(detail.getOperateNum() != null && detail.getOperateNum() > 0, "调拨数量必须大于0");
			WmsLiveStockEntity stock = wmsLiveStockService.transferOut(sourceStockId, detail.getOperateNum(),
					sourceWarehouse.getWarehouseCode());
			requireContainer(stock.getContainerCode(), sourceWarehouse);
			WmsInoutFlowEntity flow = issueFlow(wmsAllocationBill, stock, sourceStockId);
			require(wmsInoutFlowMapper.insert(flow) == 1, "调拨出库流水保存失败");
		}
		return Boolean.TRUE;
	}

	/**
	 * 调拨接收
	 *
	 * @param wmsAllocationBillAdd 调拨单接收DTO
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateAllocationBill(WmsAllocationBillAddDTO wmsAllocationBillAdd) {
		require(wmsAllocationBillAdd != null && wmsAllocationBillAdd.getId() != null, "调拨单ID不能为空");
		requireText(wmsAllocationBillAdd.getRequestId(), "接收请求唯一号不能为空");
		wmsAllocationBillAdd.setRequestId(wmsAllocationBillAdd.getRequestId().trim());
		WmsAllocationBillEntity wmsAllocationBill = lockBill(wmsAllocationBillAdd.getId());
		requireFactory(wmsAllocationBill.getFactoryCode());
		requireWarehousePermission("wms_wmsAllocationReceive_rec", wmsAllocationBill.getNewWarehouseCode(),
				"无权接收至该仓库");
		require(wmsAllocationBillAdd.getDetails() != null && !wmsAllocationBillAdd.getDetails().isEmpty(),
				"调拨接收明细不能为空");
		String receiveSnapshot = receiveRequestSnapshot(wmsAllocationBillAdd);
		if (Objects.equals(wmsAllocationBill.getReceiveRequestId(), wmsAllocationBillAdd.getRequestId())
				&& RECEIVED.equals(wmsAllocationBill.getAllocationState())) {
			require(Objects.equals(wmsAllocationBill.getReceiveRequestSnapshot(), receiveSnapshot),
					"接收幂等号已被不同请求使用");
			return Boolean.TRUE;
		}
		require(IN_TRANSIT.equals(wmsAllocationBill.getAllocationState()), "仅在途调拨单可以接收");
		require(warehouseBusinessGuard.check(wmsAllocationBill.getNewWarehouseCode(), "allocation", true),
				"目标仓未配置调拨接收业务");
		if (!Boolean.TRUE.equals(wmsAllocationBill.getSourceDeducted())) {
			return legacyReceive(wmsAllocationBill, wmsAllocationBillAdd);
		}
		List<WmsInoutFlowEntity> issueFlows = issueFlows(wmsAllocationBill);
		Map<Long, WmsDetailDTO> receivedByFlow = new HashMap<>();
		for (WmsDetailDTO detail : wmsAllocationBillAdd.getDetails()) {
			require(detail.getFlowId() != null && receivedByFlow.put(detail.getFlowId(), detail) == null,
					"调拨接收流水不能为空或重复");
		}
		require(receivedByFlow.size() == issueFlows.size(), "必须完整接收调拨单全部在途明细");
		MdmWarehouseEntity target = requireWarehouse(wmsAllocationBill.getNewWarehouseCode(),
				wmsAllocationBill.getFactoryCode());
		MomUser user = SecurityUtils.getUser();
		for (WmsInoutFlowEntity issueFlow : issueFlows) {
			WmsDetailDTO detail = receivedByFlow.get(issueFlow.getId());
			require(detail != null, "调拨明细不属于当前单据");
			require(detail.getOperateNum() != null
							&& BigDecimal.valueOf(detail.getOperateNum()).compareTo(BigDecimal.valueOf(issueFlow.getMatNum())) == 0,
					"当前版本必须按发起数量完整接收；差异请取消原单后重新发起");
			MdmWarehouseLocationEntity site = requireLocation(detail.getNewSiteCode(), target.getWarehouseCode(),
					wmsAllocationBill.getFactoryCode());
			requireContainer(detail.getContainerCode(), target);
			wmsLiveStockService.transferIn(issueFlow, target.getWarehouseCode(), target.getWarehouseName(),
					site.getLocationCode(), site.getLocationName(), detail.getContainerCode());
			require(wmsInoutFlowMapper.insert(inFlow(wmsAllocationBill, issueFlow, target.getWarehouseCode(),
					target.getWarehouseName(), site.getLocationCode(), site.getLocationName(), detail.getContainerCode(),
					"RECEIVE")) == 1, "调拨接收流水保存失败");
			wmsLiveStockService.releaseTransferSourceContainer(issueFlow);
		}
		wmsAllocationBill.setAllocationState(RECEIVED);
		wmsAllocationBill.setReceiveRequestId(wmsAllocationBillAdd.getRequestId());
		wmsAllocationBill.setReceiveRequestSnapshot(receiveSnapshot);
		wmsAllocationBill.setFinishTime(LocalDateTime.now());
		wmsAllocationBill.setFinishByName(operatorName(user));
		require(updateById(wmsAllocationBill), "调拨接收状态更新失败");
		return Boolean.TRUE;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean cancel(WmsAllocationCancelDTO command) {
		require(command != null && command.getId() != null, "调拨单ID不能为空");
		requireText(command.getRequestId(), "取消请求唯一号不能为空");
		command.setRequestId(command.getRequestId().trim());
		require(StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
				"取消原因不能为空且不能超过500字");
		command.setReason(command.getReason().trim());
		String cancelSnapshot = JSONUtil.toJsonStr(Map.of("reason", command.getReason()));
		WmsAllocationBillEntity bill = lockBill(command.getId());
		requireFactory(bill.getFactoryCode());
		requireWarehousePermission("wms_wmsAllocationBill_cancel", bill.getOldWarehouseCode(),
				"无权取消该源仓的调拨单");
		if (Objects.equals(bill.getCancelRequestId(), command.getRequestId())
				&& Set.of(RETURN_PENDING, RETURN_EXCEPTION_PENDING, RETURN_EXCEPTION_CLOSED,
				CANCELLED, PARTIALLY_CANCELLED).contains(bill.getAllocationState())) {
			require(Objects.equals(bill.getCancelRequestSnapshot(), cancelSnapshot),
					"取消幂等号已被不同请求使用");
			return Boolean.TRUE;
		}
		require(Set.of(IN_TRANSIT, PARTIALLY_RECEIVED).contains(bill.getAllocationState()),
				"仅在途或部分接收调拨单可以取消剩余在途数量");
		boolean partialReceived = PARTIALLY_RECEIVED.equals(bill.getAllocationState());
		BigDecimal cancelledSupplyQuantity = BigDecimal.ZERO;
		if (Boolean.TRUE.equals(bill.getSourceDeducted())) {
			Map<Long, BigDecimal> received = receivedQuantities(bill.getId());
			boolean hasOutstanding = false;
			for (WmsInoutFlowEntity issueFlow : issueFlows(bill)) {
				require(issueFlow.getMatNum() != null && Double.isFinite(issueFlow.getMatNum())
						&& issueFlow.getMatNum() > 0, "调拨发出流水数量异常");
				BigDecimal remaining = BigDecimal.valueOf(issueFlow.getMatNum())
						.subtract(received.getOrDefault(issueFlow.getId(), BigDecimal.ZERO));
				require(remaining.signum() >= 0, "调拨接收数量超过原发出数量，须先核账");
				if (remaining.signum() == 0) continue;
				hasOutstanding = true;
				if (Objects.equals(issueFlow.getMatCode(), bill.getSourceMaterialCode())) {
					cancelledSupplyQuantity = cancelledSupplyQuantity.add(remaining);
				}
			}
			require(hasOutstanding, "调拨单已无待退回数量，请完成最终接收");
		}
		MomUser user = SecurityUtils.getUser();
		bill.setAllocationState(Boolean.TRUE.equals(bill.getSourceDeducted()) ? RETURN_PENDING
				: partialReceived ? PARTIALLY_CANCELLED : CANCELLED);
		bill.setCancelRequestId(command.getRequestId());
		bill.setCancelRequestSnapshot(cancelSnapshot);
		bill.setCancelReason(command.getReason());
		bill.setCancelBy(user.getUsername());
		bill.setCancelByName(operatorName(user));
		bill.setCancelTime(LocalDateTime.now());
		if (!Boolean.TRUE.equals(bill.getSourceDeducted())) {
			bill.setFinishTime(bill.getCancelTime());
			bill.setFinishByName(operatorName(user));
		}
		require(updateById(bill), "调拨取消状态更新失败");
		if (bill.getSourceMrpId() != null && cancelledSupplyQuantity.signum() > 0) {
			wmsPlanShortageEventOutboxService.enqueueTransferCancelled(bill, cancelledSupplyQuantity);
		}
		return Boolean.TRUE;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean confirmReturn(WmsAllocationReturnDTO command) {
		require(command != null && command.getId() != null, "待退回调拨单ID不能为空");
		requireText(command.getRequestId(), "退回确认请求号不能为空");
		require(command.getRequestId().trim().length() <= 64, "退回确认请求号不能超过64字");
		require(StrUtil.isNotBlank(command.getEvidence()) && command.getEvidence().trim().length() <= 500,
				"实物退回证据不能为空且不能超过500字");
		require(command.getLines() != null && !command.getLines().isEmpty()
				&& command.getLines().size() <= 500, "退回确认必须逐行核对且最多500行");
		require(TenantContextHolder.getTenantId() != null, "退回确认缺少租户上下文");
		String requestId = command.getRequestId().trim();
		String snapshot = returnSnapshot(command);
		WmsAllocationBillEntity bill = lockBill(command.getId());
		requireFactory(bill.getFactoryCode());
		requireWarehousePermission("wms_wmsAllocationBill_cancel", bill.getOldWarehouseCode(),
				"无权确认该源仓的调拨退回");
		WmsAllocationReturnReceiptEntity duplicate = wmsAllocationReturnReceiptMapper.selectOne(
				new LambdaQueryWrapper<WmsAllocationReturnReceiptEntity>()
						.eq(WmsAllocationReturnReceiptEntity::getRequestId, requestId));
		if (duplicate != null) {
			require(Objects.equals(duplicate.getAllocationBillId(), bill.getId())
							&& Objects.equals(duplicate.getFactoryCode(), bill.getFactoryCode()),
					"退回确认幂等号不属于当前调拨单");
			require(Objects.equals(duplicate.getOperatorCode(), SecurityUtils.getUser().getUsername()),
					"退回确认幂等号不属于当前操作人");
			require(Objects.equals(duplicate.getRequestSnapshot(), snapshot), "退回确认幂等号已被不同请求使用");
			return Boolean.TRUE;
		}
		require(RETURN_PENDING.equals(bill.getAllocationState())
				&& Boolean.TRUE.equals(bill.getSourceDeducted()), "仅待退回的调拨单可以确认实物返还");

		Map<Long, BigDecimal> received = receivedQuantities(bill.getId());
		Map<Long, BigDecimal> returned = returnedQuantities(bill.getId());
		List<WmsInoutFlowEntity> issues = issueFlows(bill);
		Map<Long, WmsAllocationReturnDTO.Line> lines = new HashMap<>();
		for (WmsAllocationReturnDTO.Line line : command.getLines()) {
			require(line != null && line.getSourceFlowId() != null
							&& line.getReturnedQuantity() != null && line.getReturnedQuantity().signum() >= 0
							&& lines.putIfAbsent(line.getSourceFlowId(), line) == null,
					"退回明细来源流水不能为空、重复或数量无效");
		}
		Map<Long, BigDecimal> outstanding = new LinkedHashMap<>();
		for (WmsInoutFlowEntity issue : issues) {
			require(issue.getMatNum() != null && Double.isFinite(issue.getMatNum()) && issue.getMatNum() > 0,
					"调拨发出流水数量异常");
			BigDecimal remaining = BigDecimal.valueOf(issue.getMatNum())
					.subtract(received.getOrDefault(issue.getId(), BigDecimal.ZERO))
					.subtract(returned.getOrDefault(issue.getId(), BigDecimal.ZERO));
			require(remaining.signum() >= 0, "调拨接收与退回数量超过原发出数量，须先核账");
			if (remaining.signum() > 0) outstanding.put(issue.getId(), remaining);
		}
		require(!outstanding.isEmpty(), "调拨单已无待退回数量");
		require(lines.keySet().equals(outstanding.keySet()), "退回确认必须覆盖全部且仅覆盖未结发出流水");
		BigDecimal returnTotal = BigDecimal.ZERO;
		BigDecimal remainingTotal = BigDecimal.ZERO;
		for (Map.Entry<Long, BigDecimal> entry : outstanding.entrySet()) {
			BigDecimal quantity = lines.get(entry.getKey()).getReturnedQuantity();
			require(quantity.compareTo(entry.getValue()) <= 0, "本次退回数量不能超过该流水未结数量");
			returnTotal = returnTotal.add(quantity);
			remainingTotal = remainingTotal.add(entry.getValue().subtract(quantity));
		}
		require(returnTotal.signum() > 0, "本次至少需要确认一项实际退回数量");

		MdmWarehouseEntity source = requireWarehouse(bill.getOldWarehouseCode(), bill.getFactoryCode());
		Map<Long, MdmWarehouseLocationEntity> sites = new HashMap<>();
		for (WmsInoutFlowEntity issue : issues) {
			if (!outstanding.containsKey(issue.getId())
					|| lines.get(issue.getId()).getReturnedQuantity().signum() == 0) continue;
			sites.put(issue.getId(), requireLocation(issue.getSiteCode(), source.getWarehouseCode(), bill.getFactoryCode()));
			requireContainer(issue.getContainerCode(), source);
		}
		MomUser user = SecurityUtils.getUser();
		LocalDateTime now = LocalDateTime.now();
		WmsAllocationReturnReceiptEntity receipt = new WmsAllocationReturnReceiptEntity()
				.setAllocationBillId(bill.getId()).setRequestId(requestId).setRequestSnapshot(snapshot)
				.setEvidence(command.getEvidence().trim()).setReturnedQuantity(returnTotal)
				.setRemainingQuantity(remainingTotal).setOperatorCode(user.getUsername())
				.setOperatorName(operatorName(user)).setConfirmedTime(now).setFactoryCode(bill.getFactoryCode());
		require(wmsAllocationReturnReceiptMapper.insert(receipt) == 1, "调拨退回确认记录保存失败");
		for (WmsInoutFlowEntity issue : issues) {
			WmsAllocationReturnDTO.Line line = lines.get(issue.getId());
			if (line == null || line.getReturnedQuantity().signum() == 0) continue;
			BigDecimal quantity = line.getReturnedQuantity();
			MdmWarehouseLocationEntity site = sites.get(issue.getId());
			WmsInoutFlowEntity returnSource = quantityCopy(issue, quantity);
			wmsLiveStockService.transferIn(returnSource, source.getWarehouseCode(), source.getWarehouseName(),
					site.getLocationCode(), site.getLocationName(), issue.getContainerCode());
			WmsInoutFlowEntity returnFlow = inFlow(bill, returnSource, source.getWarehouseCode(),
					source.getWarehouseName(), site.getLocationCode(), site.getLocationName(),
					issue.getContainerCode(), "CANCEL_RETURN");
			returnFlow.setSourceFlowId(issue.getId());
			returnFlow.setOperationRequestId(requestId);
			require(wmsInoutFlowMapper.insert(returnFlow) == 1, "调拨退回流水保存失败");
		}
		bill.setReturnRequestId(requestId);
		bill.setReturnRequestSnapshot(snapshot);
		bill.setReturnEvidence(command.getEvidence().trim());
		bill.setReturnBy(user.getUsername());
		bill.setReturnTime(now);
		if (remainingTotal.signum() == 0) {
			issues.forEach(wmsLiveStockService::releaseTransferSourceContainer);
			bill.setAllocationState(received.values().stream().anyMatch(value -> value.signum() > 0)
					? PARTIALLY_CANCELLED : CANCELLED);
			bill.setFinishTime(now);
			bill.setFinishByName(operatorName(user));
		}
		require(updateById(bill), "调拨退回确认状态更新失败");
		return Boolean.TRUE;
	}

	@Override
	public List<WmsCrossWarehouseTransferDTO.Recommendation> transferRecommendations(
			WmsCrossWarehouseTransferDTO.Query query) {
		require(query != null && StrUtil.isNotBlank(query.getFactoryCode()), "跨仓建议必须指定工厂");
		require(query.getRequirements() != null && !query.getRequirements().isEmpty(), "跨仓建议需求不能为空");
		require(query.getRequirements().size() <= 100, "单次跨仓建议最多处理100条缺料需求");
		String factoryCode = query.getFactoryCode().trim();
		String currentFactory = FactoryContextHolder.getFactoryCode();
		require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, factoryCode),
				"跨仓建议工厂超出当前数据范围");

		List<WmsCrossWarehouseTransferDTO.Requirement> requirements = query.getRequirements().stream()
				.peek(item -> {
					require(item != null && StrUtil.isNotBlank(item.getReferenceId())
									&& StrUtil.isNotBlank(item.getTargetWarehouseCode())
									&& StrUtil.isNotBlank(item.getMaterialCode()) && item.getRequiredNum() != null
									&& item.getRequiredNum().compareTo(BigDecimal.ZERO) > 0,
							"跨仓建议需求缺少引用、目标仓、物料或有效数量");
					item.setReferenceId(item.getReferenceId().trim());
					item.setTargetWarehouseCode(item.getTargetWarehouseCode().trim());
					item.setMaterialCode(item.getMaterialCode().trim());
				})
				.sorted(Comparator.comparing(WmsCrossWarehouseTransferDTO.Requirement::getDemandDate,
								Comparator.nullsLast(LocalDate::compareTo))
						.thenComparing(WmsCrossWarehouseTransferDTO.Requirement::getReferenceId))
				.toList();
		require(requirements.stream().map(WmsCrossWarehouseTransferDTO.Requirement::getReferenceId).distinct().count()
				== requirements.size(), "跨仓建议需求引用不能重复");

		Set<String> materialCodes = requirements.stream().map(WmsCrossWarehouseTransferDTO.Requirement::getMaterialCode)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		R<List<MdmMaterialEntity>> materialResponse = remoteMaterialService.getListByCodes(new ArrayList<>(materialCodes));
		require(materialResponse != null && materialResponse.isOk() && materialResponse.getData() != null,
				"无法读取物料安全库存，跨仓建议已故障关闭");
		Map<String, BigDecimal> safetyByMaterial = materialResponse.getData().stream()
				.filter(item -> StrUtil.isNotBlank(item.getMaterialCode()))
				.collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode,
						item -> Optional.ofNullable(item.getMinStock()).orElse(BigDecimal.ZERO), (left, right) -> left));
		require(safetyByMaterial.keySet().containsAll(materialCodes), "存在未建档物料，无法计算跨仓安全库存");

		MdmWarehouseEntity warehouseFilter = new MdmWarehouseEntity();
		warehouseFilter.setFactoryCode(factoryCode);
		warehouseFilter.setStatus(Boolean.TRUE);
		R<List<MdmWarehouseEntity>> warehouseResponse = remoteWarehouseService.getList(warehouseFilter);
		require(warehouseResponse != null && warehouseResponse.isOk() && warehouseResponse.getData() != null,
				"无法读取工厂仓库，跨仓建议已故障关闭");
		Map<String, MdmWarehouseEntity> warehouses = warehouseResponse.getData().stream()
				.filter(item -> Boolean.TRUE.equals(item.getStatus()) && Objects.equals(factoryCode, item.getFactoryCode()))
				.filter(item -> StrUtil.isNotBlank(item.getWarehouseCode()))
				.collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, item -> item, (left, right) -> left,
						LinkedHashMap::new));
		require(requirements.stream().allMatch(item -> warehouses.containsKey(item.getTargetWarehouseCode())),
				"目标仓不存在、未启用或不属于当前工厂");

		List<WmsLiveStockEntity> usableStocks = wmsLiveStockService.list(
						new LambdaQueryWrapper<WmsLiveStockEntity>()
								.eq(WmsLiveStockEntity::getFactoryCode, factoryCode)
								.in(WmsLiveStockEntity::getMatCode, materialCodes)
								.gt(WmsLiveStockEntity::getMatNum, 0))
				.stream().filter(WmsStockAvailability::isUsable)
				.filter(stock -> warehouses.containsKey(stock.getWarehouseCode()))
				.sorted(Comparator.comparing(WmsLiveStockEntity::getMatValidEndDate,
								Comparator.nullsLast(LocalDate::compareTo))
						.thenComparing(WmsLiveStockEntity::getInTime,
								Comparator.nullsLast(LocalDateTime::compareTo))
						.thenComparing(WmsLiveStockEntity::getId))
				.toList();
		Map<String, TransferStockPool> pools = buildTransferPools(usableStocks, warehouses, safetyByMaterial);
		Map<String, BigDecimal> totalInTransit = targetInTransitTotals(factoryCode,
				requirements.stream().map(WmsCrossWarehouseTransferDTO.Requirement::getTargetWarehouseCode)
						.collect(Collectors.toSet()), materialCodes);
		Map<String, BigDecimal> remainingInTransit = new HashMap<>(totalInTransit);
		LocalDateTime evidenceAsOf = LocalDateTime.now();

		boolean routePolicyRequired = Boolean.TRUE.equals(query.getRoutePolicyRequired());
		List<WmsTransferRoutePolicyDTO.RouteRequirement> routeRequirements = requirements.stream()
				.flatMap(requirement -> pools.values().stream()
						.filter(pool -> Objects.equals(pool.materialCode, requirement.getMaterialCode()))
						.filter(pool -> !Objects.equals(pool.warehouseCode, requirement.getTargetWarehouseCode()))
						.map(pool -> {
							WmsTransferRoutePolicyDTO.RouteRequirement route =
									new WmsTransferRoutePolicyDTO.RouteRequirement();
							route.setSourceWarehouseCode(pool.warehouseCode);
							route.setTargetWarehouseCode(requirement.getTargetWarehouseCode());
							route.setUrgencyLevel(urgencyLevel(requirement));
							return route;
						})).distinct().toList();
		Map<String, WmsTransferRoutePolicyDTO.RoutePlan> resolvedRoutePlans = Map.of();
		if (routePolicyRequired && !routeRequirements.isEmpty()) {
			WmsTransferRoutePolicyDTO.BatchQuery routeQuery = new WmsTransferRoutePolicyDTO.BatchQuery();
			routeQuery.setFactoryCode(factoryCode);
			routeQuery.setDepartureTime(evidenceAsOf);
			routeQuery.setRoutes(routeRequirements);
			resolvedRoutePlans = wmsTransferRoutePolicyService.resolveBatch(routeQuery);
		}
		final Map<String, WmsTransferRoutePolicyDTO.RoutePlan> routePlans = resolvedRoutePlans;

		List<WmsCrossWarehouseTransferDTO.Recommendation> result = new ArrayList<>();
		for (WmsCrossWarehouseTransferDTO.Requirement requirement : requirements) {
			String targetKey = transferKey(requirement.getTargetWarehouseCode(), requirement.getMaterialCode());
			BigDecimal transitAvailable = remainingInTransit.getOrDefault(targetKey, BigDecimal.ZERO);
			BigDecimal appliedTransit = transitAvailable.min(requirement.getRequiredNum());
			remainingInTransit.put(targetKey, transitAvailable.subtract(appliedTransit));
			BigDecimal remaining = requirement.getRequiredNum().subtract(appliedTransit);
			List<WmsCrossWarehouseTransferDTO.SourceCandidate> candidates = new ArrayList<>();
			String urgency = urgencyLevel(requirement);
			boolean transferableRouteMissing = false;
			for (TransferStockPool pool : pools.values().stream()
					.filter(item -> Objects.equals(item.materialCode, requirement.getMaterialCode()))
					.filter(item -> !Objects.equals(item.warehouseCode, requirement.getTargetWarehouseCode()))
					.sorted(Comparator.comparing((TransferStockPool item) -> {
						WmsTransferRoutePolicyDTO.RoutePlan route = routePlans.get(wmsTransferRoutePolicyService.routeKey(
								item.warehouseCode, requirement.getTargetWarehouseCode(), urgency));
						return route == null ? LocalDateTime.MAX : route.getEstimatedArrivalTime();
					}).thenComparing(item -> item.warehouseCode)).toList()) {
				BigDecimal transferable = pool.transferable();
				if (transferable.compareTo(BigDecimal.ZERO) <= 0 || remaining.compareTo(BigDecimal.ZERO) <= 0) continue;
				WmsTransferRoutePolicyDTO.RoutePlan route = routePlans.get(wmsTransferRoutePolicyService.routeKey(
						pool.warehouseCode, requirement.getTargetWarehouseCode(), urgency));
				if (routePolicyRequired && route == null) {
					transferableRouteMissing = true;
					candidates.add(new WmsCrossWarehouseTransferDTO.SourceCandidate()
							.setSourceWarehouseCode(pool.warehouseCode).setSourceWarehouseName(pool.warehouseName)
							.setUsablePhysicalNum(pool.initialPhysical).setActiveReservedNum(pool.initialReserved)
							.setSafetyStockNum(pool.safetyStock).setTransferableNum(transferable)
							.setSuggestedNum(BigDecimal.ZERO).setUrgencyLevel(urgency)
							.setRoutePolicyMatched(Boolean.FALSE)
							.setRoutePolicyGap("未配置当前日期、路线和紧急等级可用的调拨时效策略")
							.setAllocations(List.of()));
					continue;
				}
				BigDecimal suggested = transferable.min(remaining);
				List<WmsCrossWarehouseTransferDTO.StockAllocation> allocations = pool.allocate(suggested);
				BigDecimal allocated = allocations.stream().map(WmsCrossWarehouseTransferDTO.StockAllocation::getSuggestedNum)
						.reduce(BigDecimal.ZERO, BigDecimal::add);
				WmsCrossWarehouseTransferDTO.SourceCandidate candidate = new WmsCrossWarehouseTransferDTO.SourceCandidate()
						.setSourceWarehouseCode(pool.warehouseCode).setSourceWarehouseName(pool.warehouseName)
						.setUsablePhysicalNum(pool.initialPhysical).setActiveReservedNum(pool.initialReserved)
						.setSafetyStockNum(pool.safetyStock).setTransferableNum(transferable)
						.setSuggestedNum(allocated).setUrgencyLevel(urgency).setRoutePolicyMatched(Boolean.TRUE)
						.setAllocations(allocations);
				if (route == null) {
					candidate.setTransferLeadHours(LEGACY_V6_TRANSFER_LEAD_HOURS)
							.setTransferLeadMinutes(LEGACY_V6_TRANSFER_LEAD_HOURS * 60)
							.setCalendarMode("CONTINUOUS")
							.setTransportMode("LEGACY_STANDARD")
							.setEstimatedArrivalTime(evidenceAsOf.plusHours(LEGACY_V6_TRANSFER_LEAD_HOURS));
				} else {
					candidate.setRoutePolicyId(route.getPolicyId()).setRoutePolicyCode(route.getPolicyCode())
							.setRoutePolicyName(route.getPolicyName())
							.setRoutePolicyFingerprint(route.getPolicyFingerprint())
							.setTransportMode(route.getTransportMode())
							.setRoutePolicyUrgencyLevel(route.getPolicyUrgencyLevel())
							.setCalendarMode(route.getCalendarMode())
							.setTransferLeadMinutes(route.getLeadTimeMinutes())
							.setTransferLeadHours(route.getTransferLeadHours())
							.setEstimatedArrivalTime(route.getEstimatedArrivalTime());
				}
				candidates.add(candidate);
				remaining = remaining.subtract(allocated);
			}
			BigDecimal suggested = candidates.stream().map(WmsCrossWarehouseTransferDTO.SourceCandidate::getSuggestedNum)
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			String status = remaining.compareTo(BigDecimal.ZERO) == 0
					? (suggested.compareTo(BigDecimal.ZERO) > 0 ? "FULL" : "IN_TRANSIT_COVERED")
					: transferableRouteMissing
					? (suggested.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL_ROUTE_POLICY_MISSING" : "ROUTE_POLICY_MISSING")
					: suggested.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "NO_SOURCE";
			WmsCrossWarehouseTransferDTO.Recommendation recommendation =
					new WmsCrossWarehouseTransferDTO.Recommendation()
							.setReferenceId(requirement.getReferenceId())
							.setTargetWarehouseCode(requirement.getTargetWarehouseCode())
							.setTargetWarehouseName(warehouses.get(requirement.getTargetWarehouseCode()).getWarehouseName())
							.setMaterialCode(requirement.getMaterialCode()).setRequiredNum(requirement.getRequiredNum())
							.setTargetInTransitNum(totalInTransit.getOrDefault(targetKey, BigDecimal.ZERO))
							.setAppliedInTransitNum(appliedTransit).setSuggestedTransferNum(suggested)
							.setUncoveredNum(remaining).setStatus(status)
							.setEvidenceComplete(!status.contains("ROUTE_POLICY_MISSING"))
							.setEvidenceAsOf(evidenceAsOf).setSourceCandidates(candidates);
			recommendation.setEvidenceFingerprint(transferFingerprint(recommendation));
			result.add(recommendation);
		}
		return result;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public WmsCrossWarehouseTransferDTO.Receipt executeAgentTransfer(
			WmsCrossWarehouseTransferDTO.AgentCommand command) {
		require(command != null && StrUtil.isNotBlank(command.getRequestId()), "Agent调拨执行请求号不能为空");
		command.setRequestId(command.getRequestId().trim());
		require(StrUtil.isNotBlank(command.getFactoryCode()) && command.getMrpId() != null
						&& StrUtil.isNotBlank(command.getIssueNo())
						&& StrUtil.isNotBlank(command.getTargetWarehouseCode())
						&& StrUtil.isNotBlank(command.getSourceWarehouseCode())
						&& StrUtil.isNotBlank(command.getMaterialCode())
						&& command.getExpectedShortageNum() != null
						&& command.getExpectedShortageNum().compareTo(BigDecimal.ZERO) > 0
						&& command.getExpectedTargetInTransitNum() != null
						&& StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint())
						&& command.getTransferNum() != null && command.getTransferNum().compareTo(BigDecimal.ZERO) > 0
						&& command.getAllocations() != null && !command.getAllocations().isEmpty(),
				"Agent调拨命令缺少MRP、生产计划、仓库、物料、冻结证据或有效数量");
		if (Boolean.TRUE.equals(command.getRoutePolicyRequired())) {
			require(command.getRoutePolicyId() != null && StrUtil.isNotBlank(command.getRoutePolicyCode())
							&& StrUtil.isNotBlank(command.getRoutePolicyFingerprint())
							&& StrUtil.isNotBlank(command.getTransportMode())
							&& StrUtil.isNotBlank(command.getUrgencyLevel()),
					"Agent调拨命令缺少冻结路线策略");
		}
		require(command.getTransferNum().compareTo(command.getExpectedShortageNum()) <= 0,
				"Agent调拨数量不能超过冻结缺口");
		require(StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
				"Agent调拨必须填写不超过500字的业务原因");
		Map<Long, WmsCrossWarehouseTransferDTO.StockAllocation> requested = new LinkedHashMap<>();
		BigDecimal requestedTotal = BigDecimal.ZERO;
		for (WmsCrossWarehouseTransferDTO.StockAllocation allocation : command.getAllocations()) {
			require(allocation != null && allocation.getStockId() != null && allocation.getSuggestedNum() != null
							&& allocation.getSuggestedNum().compareTo(BigDecimal.ZERO) > 0
							&& requested.put(allocation.getStockId(), allocation) == null,
					"Agent调拨库存分配不能为空、重复或非正数");
			requestedTotal = requestedTotal.add(allocation.getSuggestedNum());
		}
		require(requestedTotal.compareTo(command.getTransferNum()) == 0, "Agent调拨明细合计与调拨数量不一致");
		baseMapper.lockAgentTransfer("REQUEST:" + command.getRequestId());
		WmsAllocationBillEntity existing = getOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getRequestId, command.getRequestId()), false);
		if (existing != null) return existingAgentTransferReceipt(existing, command);
		String factoryCode = command.getFactoryCode().trim();
		String currentFactory = FactoryContextHolder.getFactoryCode();
		require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, factoryCode),
				"Agent调拨工厂超出当前数据范围");
		MdmWarehouseEntity source = requireWarehouse(command.getSourceWarehouseCode().trim(), factoryCode);
		MdmWarehouseEntity target = requireWarehouse(command.getTargetWarehouseCode().trim(), factoryCode);
		require(!Objects.equals(source.getWarehouseCode(), target.getWarehouseCode()), "Agent调拨源仓和目标仓不能相同");
		WmsTransferRoutePolicyDTO.RoutePlan currentRoute = null;
		if (Boolean.TRUE.equals(command.getRoutePolicyRequired())) {
			WmsTransferRoutePolicyDTO.PreviewQuery routeQuery = new WmsTransferRoutePolicyDTO.PreviewQuery();
			routeQuery.setFactoryCode(factoryCode);
			routeQuery.setSourceWarehouseCode(source.getWarehouseCode());
			routeQuery.setTargetWarehouseCode(target.getWarehouseCode());
			routeQuery.setUrgencyLevel(command.getUrgencyLevel());
			routeQuery.setDepartureTime(LocalDateTime.now());
			currentRoute = wmsTransferRoutePolicyService.preview(routeQuery);
			require(Objects.equals(currentRoute.getPolicyId(), command.getRoutePolicyId())
							&& Objects.equals(currentRoute.getPolicyCode(), command.getRoutePolicyCode())
							&& Objects.equals(currentRoute.getPolicyFingerprint(), command.getRoutePolicyFingerprint())
							&& Objects.equals(currentRoute.getTransportMode(), command.getTransportMode()),
					"调拨路线、运输方式、日历或时效策略已变化，旧审批失效");
		}
		baseMapper.lockAgentTransfer("TARGET:" + factoryCode + ":" + target.getWarehouseCode()
				+ ":" + command.getMaterialCode());
		BigDecimal currentTransit = targetInTransitTotals(factoryCode, Set.of(target.getWarehouseCode()),
				Set.of(command.getMaterialCode())).getOrDefault(transferKey(target.getWarehouseCode(), command.getMaterialCode()),
				BigDecimal.ZERO);
		require(currentTransit.compareTo(command.getExpectedTargetInTransitNum()) == 0,
				"目标仓在途数量已变化，旧调拨审批失效，请重新运行Agent");

		List<WmsLiveStockEntity> lockedStocks = wmsLiveStockService.list(
				new LambdaQueryWrapper<WmsLiveStockEntity>()
						.eq(WmsLiveStockEntity::getFactoryCode, factoryCode)
						.eq(WmsLiveStockEntity::getWarehouseCode, source.getWarehouseCode())
						.eq(WmsLiveStockEntity::getMatCode, command.getMaterialCode())
						.gt(WmsLiveStockEntity::getMatNum, 0).orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
		Map<Long, WmsLiveStockEntity> stockById = lockedStocks.stream()
				.collect(Collectors.toMap(WmsLiveStockEntity::getId, item -> item));
		BigDecimal physical = BigDecimal.ZERO;
		BigDecimal reserved = BigDecimal.ZERO;
		for (WmsLiveStockEntity stock : lockedStocks) {
			if (!WmsStockAvailability.isUsable(stock)) continue;
			physical = physical.add(stockNum(stock));
			reserved = reserved.add(wmsMaterialReservationService.activeReservedNum(stock.getId()));
		}
		BigDecimal safety = safetyStock(command.getMaterialCode());
		require(physical.subtract(reserved).subtract(safety).max(BigDecimal.ZERO)
						.compareTo(command.getTransferNum()) >= 0,
				"源仓扣除活动预留和安全库存后已不足，旧调拨审批失效");
		for (WmsCrossWarehouseTransferDTO.StockAllocation allocation : requested.values()) {
			WmsLiveStockEntity stock = stockById.get(allocation.getStockId());
			require(stock != null && WmsStockAvailability.isUsable(stock),
					"冻结的源库存不存在或质量/效期状态已不可用，旧调拨审批失效");
			BigDecimal rowAvailable = stockNum(stock)
					.subtract(wmsMaterialReservationService.activeReservedNum(stock.getId())).max(BigDecimal.ZERO);
			require(rowAvailable.compareTo(allocation.getSuggestedNum()) >= 0,
					"冻结的源库存可用数量已变化，旧调拨审批失效");
		}

		WmsAllocationBillAddDTO bill = new WmsAllocationBillAddDTO();
		bill.setRequestId(command.getRequestId());
		bill.setBillNo("DB-AG-" + IdUtil.getSnowflakeNextIdStr());
		bill.setOldWarehouseCode(source.getWarehouseCode());
		bill.setOldWarehouseName(source.getWarehouseName());
		bill.setOldWarehouseType(source.getWarehouseType());
		bill.setNewWarehouseCode(target.getWarehouseCode());
		bill.setNewWarehouseName(target.getWarehouseName());
		bill.setNewWarehouseType(target.getWarehouseType());
		bill.setBillType("agent");
		bill.setFactoryCode(factoryCode);
		bill.setSourceBusinessType("PLAN_MRP");
		bill.setSourceBusinessNo(command.getIssueNo().trim());
		bill.setSourceMrpId(command.getMrpId());
		bill.setSourceMaterialCode(command.getMaterialCode().trim());
		bill.setSourceShortageQuantity(command.getExpectedShortageNum());
		if (currentRoute != null) {
			bill.setTransferRoutePolicyId(currentRoute.getPolicyId());
			bill.setTransferRoutePolicyCode(currentRoute.getPolicyCode());
			bill.setTransferRoutePolicySnapshot(JSONUtil.toJsonStr(currentRoute));
			bill.setTransportMode(currentRoute.getTransportMode());
			bill.setUrgencyLevel(currentRoute.getUrgencyLevel());
			bill.setEstimatedArrivalTime(currentRoute.getEstimatedArrivalTime());
		}
		bill.setRemark("Agent齐套欠料受控调拨；MRP=" + command.getMrpId() + "；业务="
				+ StrUtil.blankToDefault(command.getBusinessNo(), "-") + "；" + command.getReason().trim());
		bill.setDetails(requested.values().stream().map(allocation -> {
			WmsDetailDTO detail = new WmsDetailDTO();
			detail.setSourceStockId(allocation.getStockId());
			detail.setOperateNum(allocation.getSuggestedNum().doubleValue());
			return detail;
		}).toList());
		require(Boolean.TRUE.equals(saveAllocationBill(bill)), "Agent正式调拨单创建失败");
		WmsAllocationBillEntity saved = getOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getRequestId, command.getRequestId()), false);
		require(saved != null, "Agent正式调拨单已执行但回执读取失败");
		return transferReceipt(saved, command.getMaterialCode(), command.getTransferNum());
	}

	/**
	 * 通过id查询调拨单
	 *
	 * @param id id
	 * @return R
	 */
	@Override
	public WmsAllocationBillVO getDetails(Long id) {
		WmsAllocationBillVO wmsAllocationBillVO = new WmsAllocationBillVO();
		WmsAllocationBillEntity byId = this.getById(id);
		require(byId != null, "调拨单不存在");
		requireFactory(byId.getFactoryCode());
		require(permissionService.hasPermission(
						"wms_wmsAllocationBill_view",
						"wms_wmsAllocationBill_view_" + byId.getOldWarehouseCode(),
						"wms_wmsAllocationBill_cancel_" + byId.getOldWarehouseCode(),
						"wms_wmsAllocationReceive_view",
						"wms_wmsAllocationReceive_view_" + byId.getNewWarehouseCode(),
						"wms_wmsAllocationReceive_rec_" + byId.getNewWarehouseCode())
						|| permissionService.hasPermission("wms_wmsAllocationReceive_rec"),
				"无权查看该调拨单");
		BeanUtils.copyProperties(byId, wmsAllocationBillVO);
		List<WmsInoutFlowEntity> wmsInoutFlowEntities = Boolean.TRUE.equals(byId.getSourceDeducted())
				? issueFlows(byId)
				: wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
				.eq(WmsInoutFlowEntity::getBillNo, wmsAllocationBillVO.getBillNo())
				.eq(WmsInoutFlowEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInoutFlowEntity::getFactoryCode, byId.getFactoryCode())
				.eq(WmsInoutFlowEntity::getWarehouseCode, byId.getOldWarehouseCode())
				.eq(WmsInoutFlowEntity::getOperateType, InOutEnum.OUT.getCode())
				.eq(WmsInoutFlowEntity::getBillType, OutBillTypeEnum.ALLOCATION_OUT.getCode()));
		List<WmsInoutFlowEntity> terminalFlows = Boolean.TRUE.equals(byId.getSourceDeducted())
				? wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
				.eq(WmsInoutFlowEntity::getBillId, byId.getId())
				.in(WmsInoutFlowEntity::getTransferAction, "RECEIVE", "CANCEL_RETURN", "OVER_ACCEPT")
				.orderByAsc(WmsInoutFlowEntity::getId)) : List.of();
		Map<Long, List<WmsInoutFlowEntity>> terminalBySourceFlow = terminalFlows.stream()
				.filter(flow -> flow.getSourceFlowId() != null)
				.collect(Collectors.groupingBy(WmsInoutFlowEntity::getSourceFlowId));
		Map<Long, WmsTransferVarianceLineEntity> varianceBySourceFlow = byId.getVarianceCaseId() == null ? Map.of()
				: wmsTransferVarianceLineMapper.selectList(new LambdaQueryWrapper<WmsTransferVarianceLineEntity>()
						.eq(WmsTransferVarianceLineEntity::getCaseId, byId.getVarianceCaseId())).stream()
				.collect(Collectors.toMap(WmsTransferVarianceLineEntity::getSourceFlowId,
						item -> item, (left, right) -> right));
		Map<Long, BigDecimal> returnExceptionByFlow = RETURN_EXCEPTION_CLOSED.equals(byId.getAllocationState())
				&& byId.getReturnExceptionCaseId() != null
				? wmsAllocationReturnExceptionLineMapper.selectList(
						new LambdaQueryWrapper<WmsAllocationReturnExceptionLineEntity>()
								.eq(WmsAllocationReturnExceptionLineEntity::getCaseId, byId.getReturnExceptionCaseId()))
				.stream().collect(Collectors.toMap(WmsAllocationReturnExceptionLineEntity::getSourceFlowId,
						WmsAllocationReturnExceptionLineEntity::getExceptionQuantity, BigDecimal::add)) : Map.of();
		List<WmsDetailDTO> details = wmsInoutFlowEntities.stream()
				.map(wmsInoutFlowEntity -> {
					WmsDetailDTO wmsDetailDTO = new WmsDetailDTO();
					BeanUtils.copyProperties(wmsInoutFlowEntity, wmsDetailDTO);
					wmsDetailDTO.setOldSiteCode(wmsInoutFlowEntity.getSiteCode());
					wmsDetailDTO.setOldSiteName(wmsInoutFlowEntity.getSiteName());
					wmsDetailDTO.setOldWarehouseCode(wmsInoutFlowEntity.getWarehouseCode());
					wmsDetailDTO.setOldWarehouseName(wmsInoutFlowEntity.getWarehouseName());
					wmsDetailDTO.setFlowId(wmsInoutFlowEntity.getId());
					wmsDetailDTO.setSourceStockId(wmsInoutFlowEntity.getSourceStockId());
					wmsDetailDTO.setOldContainerCode(wmsInoutFlowEntity.getContainerCode());
					wmsDetailDTO.setBillId(Boolean.TRUE.equals(byId.getSourceDeducted())
							? byId.getId() : wmsInoutFlowEntity.getSourceStockId());
					wmsDetailDTO.setOperateNum(wmsInoutFlowEntity.getMatNum());
					BigDecimal issued = BigDecimal.valueOf(wmsInoutFlowEntity.getMatNum());
					List<WmsInoutFlowEntity> terminals = terminalBySourceFlow
							.getOrDefault(wmsInoutFlowEntity.getId(), List.of());
					BigDecimal received = terminals.stream().filter(flow -> "RECEIVE".equals(flow.getTransferAction()))
							.map(flow -> BigDecimal.valueOf(flow.getMatNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
					BigDecimal cancelled = terminals.stream().filter(flow -> "CANCEL_RETURN".equals(flow.getTransferAction()))
							.map(flow -> BigDecimal.valueOf(flow.getMatNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
					BigDecimal rawRemaining = issued.subtract(received).subtract(cancelled);
					boolean open = Set.of(IN_TRANSIT, PARTIALLY_RECEIVED, RETURN_PENDING, RETURN_EXCEPTION_PENDING)
							.contains(byId.getAllocationState());
					WmsTransferVarianceLineEntity variance = varianceBySourceFlow.get(wmsInoutFlowEntity.getId());
					BigDecimal shortQuantity = variance == null ? BigDecimal.ZERO :
							Optional.ofNullable(variance.getShortQuantity()).orElse(BigDecimal.ZERO);
					BigDecimal exceptionQuantity = returnExceptionByFlow.getOrDefault(
							wmsInoutFlowEntity.getId(), BigDecimal.ZERO);
					BigDecimal unreconciled = Boolean.TRUE.equals(byId.getSourceDeducted())
							? (open ? rawRemaining.min(BigDecimal.ZERO).abs()
							: rawRemaining.subtract(shortQuantity).subtract(exceptionQuantity).abs()) : BigDecimal.ZERO;
					wmsDetailDTO.setIssuedQuantity(issued);
					wmsDetailDTO.setReceivedQuantity(received);
					wmsDetailDTO.setInTransitQuantity(open ? rawRemaining.max(BigDecimal.ZERO) : BigDecimal.ZERO);
					wmsDetailDTO.setShortQuantity(shortQuantity);
					wmsDetailDTO.setReturnExceptionQuantity(exceptionQuantity);
					wmsDetailDTO.setOverQuantity(variance == null ? BigDecimal.ZERO : variance.getOverQuantity());
					wmsDetailDTO.setUnreconciledQuantity(unreconciled);
					wmsDetailDTO.setTransferLineState(unreconciled.signum() > 0 ? "RECONCILIATION_REQUIRED"
							: variance != null
							? ("RESOLVED".equals(variance.getDispositionStatus()) ? "VARIANCE_RESOLVED" : "VARIANCE_PENDING")
							: RETURN_EXCEPTION_PENDING.equals(byId.getAllocationState()) && rawRemaining.signum() > 0
							? RETURN_EXCEPTION_PENDING
							: RETURN_EXCEPTION_CLOSED.equals(byId.getAllocationState()) && exceptionQuantity.signum() > 0
							? RETURN_EXCEPTION_CLOSED
							: RETURN_PENDING.equals(byId.getAllocationState()) && rawRemaining.signum() > 0
							? RETURN_PENDING
							: cancelled.signum() > 0 ? "CANCELLED"
							: rawRemaining.signum() == 0 ? "RECEIVED"
							: received.signum() > 0 ? "PARTIAL" : "IN_TRANSIT");
					WmsInoutFlowEntity destination = terminals.stream()
							.filter(flow -> Set.of("RECEIVE", "OVER_ACCEPT").contains(flow.getTransferAction()))
							.reduce((left, right) -> right).orElse(null);
					if (destination != null) {
						wmsDetailDTO.setNewWarehouseCode(destination.getWarehouseCode());
						wmsDetailDTO.setNewWarehouseName(destination.getWarehouseName());
						wmsDetailDTO.setNewSiteCode(destination.getSiteCode());
						wmsDetailDTO.setNewSiteName(destination.getSiteName());
						wmsDetailDTO.setContainerCode(destination.getContainerCode());
					}
					return wmsDetailDTO;
				}).toList();
		wmsAllocationBillVO.setDetails(details);
		wmsAllocationBillVO.setReturnReceipts(byId.getReturnRequestId() == null ? List.of() :
				Optional.ofNullable(wmsAllocationReturnReceiptMapper.selectList(
								new LambdaQueryWrapper<WmsAllocationReturnReceiptEntity>()
										.eq(WmsAllocationReturnReceiptEntity::getAllocationBillId, byId.getId())
										.orderByAsc(WmsAllocationReturnReceiptEntity::getConfirmedTime)
										.orderByAsc(WmsAllocationReturnReceiptEntity::getId)))
						.orElse(List.of()));
		return wmsAllocationBillVO;
	}

	private Boolean legacyReceive(WmsAllocationBillEntity bill, WmsAllocationBillAddDTO command) {
		require(command.getDetails() != null && !command.getDetails().isEmpty(), "调拨接收明细不能为空");
		List<WmsInoutFlowEntity> issued = wmsInoutFlowMapper.selectList(
				new LambdaQueryWrapper<WmsInoutFlowEntity>()
						.eq(WmsInoutFlowEntity::getBillNo, bill.getBillNo())
						.eq(WmsInoutFlowEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(WmsInoutFlowEntity::getFactoryCode, bill.getFactoryCode())
						.eq(WmsInoutFlowEntity::getWarehouseCode, bill.getOldWarehouseCode())
						.eq(WmsInoutFlowEntity::getOperateType, InOutEnum.OUT.getCode())
						.eq(WmsInoutFlowEntity::getBillType, OutBillTypeEnum.ALLOCATION_OUT.getCode())
						.orderByAsc(WmsInoutFlowEntity::getId).last("FOR UPDATE"));
		Map<Long, WmsDetailDTO> requested = validateLegacyReceipt(bill, command, issued);
		MdmWarehouseEntity target = requireWarehouse(bill.getNewWarehouseCode(), bill.getFactoryCode());
		for (WmsInoutFlowEntity original : issued) {
			WmsDetailDTO detail = requested.get(original.getId());
			MdmWarehouseLocationEntity site = requireLocation(detail.getNewSiteCode(), target.getWarehouseCode(),
					bill.getFactoryCode());
			requireContainer(detail.getContainerCode(), target);
			Long sourceStockId = original.getSourceStockId();
			WmsLiveStockEntity stock = wmsLiveStockService.transferOut(sourceStockId,
					original.getMatNum(), bill.getOldWarehouseCode());
			requireLegacySourceMatches(bill, original, stock);
			WmsInoutFlowEntity source = issueFlow(bill, stock, sourceStockId);
			source.setId(original.getId());
			wmsLiveStockService.transferIn(source, target.getWarehouseCode(), target.getWarehouseName(),
					site.getLocationCode(), site.getLocationName(), detail.getContainerCode());
			WmsInoutFlowEntity receipt = inFlow(bill, source, target.getWarehouseCode(),
					target.getWarehouseName(), site.getLocationCode(), site.getLocationName(),
					detail.getContainerCode(), "RECEIVE");
			receipt.setOperationRequestId(command.getRequestId());
			require(wmsInoutFlowMapper.insert(receipt) == 1, "历史调拨接收流水保存失败");
			wmsLiveStockService.releaseTransferSourceContainer(source);
		}
		MomUser user = SecurityUtils.getUser();
		bill.setAllocationState(RECEIVED);
		bill.setReceiveRequestId(command.getRequestId());
		bill.setReceiveRequestSnapshot(receiveRequestSnapshot(command));
		bill.setFinishTime(LocalDateTime.now());
		bill.setFinishByName(operatorName(user));
		require(updateById(bill), "调拨接收状态更新失败");
		return Boolean.TRUE;
	}

	private Map<Long, WmsDetailDTO> validateLegacyReceipt(WmsAllocationBillEntity bill,
	                                                      WmsAllocationBillAddDTO command,
	                                                      List<WmsInoutFlowEntity> issued) {
		require(issued != null && !issued.isEmpty(), "历史调拨缺少可核对的发出流水");
		Map<Long, WmsInoutFlowEntity> issuedById = new HashMap<>();
		for (WmsInoutFlowEntity flow : issued) {
			require(flow != null && flow.getId() != null && issuedById.put(flow.getId(), flow) == null
							&& Objects.equals(flow.getTenantId(), TenantContextHolder.getTenantId())
							&& Objects.equals(flow.getFactoryCode(), bill.getFactoryCode())
							&& (Objects.equals(flow.getBillId(), bill.getId())
							|| Objects.equals(flow.getBillId(), flow.getSourceStockId()))
							&& Objects.equals(flow.getBillNo(), bill.getBillNo())
							&& Objects.equals(flow.getWarehouseCode(), bill.getOldWarehouseCode())
							&& Objects.equals(flow.getOperateType(), InOutEnum.OUT.getCode())
							&& Objects.equals(flow.getBillType(), OutBillTypeEnum.ALLOCATION_OUT.getCode())
							&& flow.getMatNum() != null && Double.isFinite(flow.getMatNum()) && flow.getMatNum() > 0,
					"历史调拨发出流水范围或数量异常，请先核对");
		}
		Map<Long, WmsDetailDTO> requested = new HashMap<>();
		for (WmsDetailDTO detail : command.getDetails()) {
			require(detail != null && detail.getFlowId() != null
							&& requested.put(detail.getFlowId(), detail) == null
							&& issuedById.containsKey(detail.getFlowId()),
					"接收明细包含重复或不属于本调拨单的发出流水");
			WmsInoutFlowEntity flow = issuedById.get(detail.getFlowId());
			Long stockId = flow.getSourceStockId();
			require(stockId != null && stockId > 0,
					"历史调拨发出流水缺少源库存ID，须先核对并补齐来源，禁止自动接收");
			require(Objects.equals(stockId, detail.getBillId())
							&& detail.getOperateNum() != null && Double.isFinite(detail.getOperateNum())
							&& BigDecimal.valueOf(detail.getOperateNum())
							.compareTo(BigDecimal.valueOf(flow.getMatNum())) == 0,
					"历史调拨必须按原发出库存和数量完整接收");
		}
		require(requested.size() == issuedById.size(), "历史调拨必须完整接收全部发出明细");
		return requested;
	}

	private void requireLegacySourceMatches(WmsAllocationBillEntity bill,
	                                        WmsInoutFlowEntity original,
	                                        WmsLiveStockEntity stock) {
		require(stock != null && Objects.equals(stock.getTenantId(), TenantContextHolder.getTenantId())
						&& Objects.equals(stock.getFactoryCode(), bill.getFactoryCode())
						&& Objects.equals(stock.getSiteCode(), original.getSiteCode())
						&& Objects.equals(stock.getMatCode(), original.getMatCode())
						&& Objects.equals(stock.getMatBatch(), original.getMatBatch())
						&& Objects.equals(stock.getMatUnit(), original.getMatUnit())
						&& Objects.equals(stock.getSupplierCode(), original.getSupplierCode())
						&& Objects.equals(StrUtil.blankToDefault(stock.getStockOwnerType(), "OWNED"),
						StrUtil.blankToDefault(original.getStockOwnerType(), "OWNED"))
						&& Objects.equals(stock.getStockOwnerCode(), original.getStockOwnerCode())
						&& Objects.equals(stock.getMatBarcode(), original.getMatBarcode()),
				"历史调拨源库存身份已变化，请先核对原发出流水");
	}

	private WmsAllocationBillEntity lockBill(Long id) {
		WmsAllocationBillEntity bill = getOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getId, id).last("FOR UPDATE"), false);
		if (bill == null) {
			throw new CheckedException("调拨单不存在");
		}
		return bill;
	}

	private List<WmsInoutFlowEntity> issueFlows(WmsAllocationBillEntity bill) {
		List<WmsInoutFlowEntity> flows = wmsInoutFlowMapper.selectList(
				new LambdaQueryWrapper<WmsInoutFlowEntity>()
						.eq(WmsInoutFlowEntity::getBillId, bill.getId())
						.eq(WmsInoutFlowEntity::getOperateType, InOutEnum.OUT.getCode())
						.eq(WmsInoutFlowEntity::getTransferAction, "ISSUE")
						.orderByAsc(WmsInoutFlowEntity::getId));
		require(!flows.isEmpty(), "调拨单缺少发起出库流水，禁止继续操作");
		return flows;
	}

	private WmsInoutFlowEntity issueFlow(WmsAllocationBillEntity bill, WmsLiveStockEntity stock,
	                                     Long sourceStockId) {
		WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
		BeanUtils.copyProperties(stock, flow);
		clearInsertIdentity(flow);
		flow.setBillId(bill.getId());
		flow.setBillNo(bill.getBillNo());
		flow.setSourceStockId(sourceStockId);
		flow.setTransferAction("ISSUE");
		flow.setStockInTime(stock.getInTime());
		flow.setOperateType(InOutEnum.OUT.getCode());
		flow.setBillType(OutBillTypeEnum.ALLOCATION_OUT.getCode());
		flow.setFactoryCode(bill.getFactoryCode());
		return flow;
	}

	private WmsInoutFlowEntity inFlow(WmsAllocationBillEntity bill, WmsInoutFlowEntity source,
	                                  String warehouseCode, String warehouseName,
	                                  String siteCode, String siteName, String containerCode,
	                                  String action) {
		WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
		BeanUtils.copyProperties(source, flow);
		clearInsertIdentity(flow);
		flow.setBillId(bill.getId());
		flow.setBillNo(bill.getBillNo());
		flow.setSourceFlowId(source.getId());
		flow.setSourceStockId(source.getSourceStockId());
		flow.setWarehouseCode(warehouseCode);
		flow.setWarehouseName(warehouseName);
		flow.setSiteCode(siteCode);
		flow.setSiteName(siteName);
		flow.setContainerCode(containerCode);
		flow.setOperateType(InOutEnum.IN.getCode());
		flow.setBillType(InBillTypeEnum.ALLOCATION.getCode());
		flow.setTransferAction(action);
		flow.setFactoryCode(bill.getFactoryCode());
		return flow;
	}

	private void clearInsertIdentity(WmsInoutFlowEntity flow) {
		flow.setId(null);
		flow.setCreateBy(null);
		flow.setCreateTime(null);
		flow.setUpdateBy(null);
		flow.setUpdateTime(null);
		flow.setDelFlag(null);
	}

	private MdmWarehouseEntity requireWarehouse(String code, String factoryCode) {
		requireCode(code, "仓库编码不能为空");
		require(!code.contains("*") && !code.contains("?"), "仓库编码不能包含权限通配符");
		R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(code.trim());
		MdmWarehouseEntity warehouse = response == null ? null : response.getData();
		require(response != null && response.isOk() && warehouse != null && Boolean.TRUE.equals(warehouse.getStatus()),
				"仓库[" + code + "]不存在或未启用");
		require(StrUtil.isBlank(warehouse.getFactoryCode()) || Objects.equals(factoryCode, warehouse.getFactoryCode()),
				"仓库[" + code + "]不属于当前工厂");
		return warehouse;
	}

	private MdmWarehouseLocationEntity requireLocation(String code, String warehouseCode, String factoryCode) {
		requireCode(code, "库位编码不能为空");
		R<MdmWarehouseLocationEntity> response = remoteWarehouseLocationService.getByCode(code.trim());
		MdmWarehouseLocationEntity site = response == null ? null : response.getData();
		require(response != null && response.isOk() && site != null && Boolean.TRUE.equals(site.getStatus()),
				"目标库位[" + code + "]不存在或未启用");
		require(Objects.equals(warehouseCode, site.getWarehouseCode()), "目标库位不属于调拨目标仓库");
		require(StrUtil.isBlank(site.getFactoryCode()) || Objects.equals(factoryCode, site.getFactoryCode()),
				"目标库位不属于当前工厂");
		return site;
	}

	private void requireContainer(String code, MdmWarehouseEntity warehouse) {
		boolean required = Objects.equals(warehouse.getIsContainer(), 1);
		require(!required || StrUtil.isNotBlank(code), "仓库[" + warehouse.getWarehouseCode() + "]必须指定容器");
		if (StrUtil.isBlank(code)) {
			return;
		}
		WmsContainerBarcodeEntity container = wmsContainerBarcodeMapper.selectOne(
				new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
						.eq(WmsContainerBarcodeEntity::getCode, code.trim()).last("LIMIT 1"));
		require(container != null && Objects.equals(warehouse.getWarehouseCode(), container.getWarehouseCode()),
				"容器[" + code + "]不存在或不属于仓库[" + warehouse.getWarehouseCode() + "]");
		require(StrUtil.isBlank(container.getFactoryCode()) || StrUtil.isBlank(warehouse.getFactoryCode())
						|| Objects.equals(warehouse.getFactoryCode(), container.getFactoryCode()),
				"容器[" + code + "]不属于当前工厂");
	}

	private String issueRequestSnapshot(WmsAllocationBillAddDTO command) {
		List<Map<String, Object>> lines = command.getDetails().stream().map(detail -> {
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("sourceStockId", detail.getSourceStockId() == null ? detail.getBillId() : detail.getSourceStockId());
			line.put("quantity", detail.getOperateNum());
			return line;
		}).sorted(Comparator.comparing(line -> Objects.toString(line.get("sourceStockId"), ""))).toList();
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("billNo", command.getBillNo());
		snapshot.put("factory", command.getFactoryCode());
		snapshot.put("sourceWarehouse", command.getOldWarehouseCode());
		snapshot.put("targetWarehouse", command.getNewWarehouseCode());
		snapshot.put("routePolicyId", command.getTransferRoutePolicyId());
		snapshot.put("routePolicyCode", command.getTransferRoutePolicyCode());
		snapshot.put("routePolicySnapshot", command.getTransferRoutePolicySnapshot());
		snapshot.put("transportMode", command.getTransportMode());
		snapshot.put("urgencyLevel", command.getUrgencyLevel());
		snapshot.put("transferPurpose", command.getTransferPurpose());
		snapshot.put("parentAllocationBillId", command.getParentAllocationBillId());
		snapshot.put("varianceCaseId", command.getVarianceCaseId());
		snapshot.put("sourceBusinessType", command.getSourceBusinessType());
		snapshot.put("sourceBusinessNo", command.getSourceBusinessNo());
		snapshot.put("sourceMrpId", command.getSourceMrpId());
		snapshot.put("sourceMaterialCode", command.getSourceMaterialCode());
		snapshot.put("sourceShortageQuantity", command.getSourceShortageQuantity());
		snapshot.put("lines", lines);
		return JSONUtil.toJsonStr(snapshot);
	}

	private String receiveRequestSnapshot(WmsAllocationBillAddDTO command) {
		List<Map<String, Object>> lines = command.getDetails().stream().map(detail -> {
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("flowId", detail.getFlowId());
			line.put("quantity", detail.getOperateNum());
			line.put("targetSite", StrUtil.trim(detail.getNewSiteCode()));
			line.put("targetContainer", StrUtil.trim(detail.getContainerCode()));
			return line;
		}).sorted(Comparator.comparing(line -> Objects.toString(line.get("flowId"), ""))).toList();
		return JSONUtil.toJsonStr(Map.of("billId", command.getId(), "lines", lines));
	}

	private String returnSnapshot(WmsAllocationReturnDTO command) {
		List<Map<String, Object>> lines = command.getLines().stream().map(line -> {
			require(line != null && line.getSourceFlowId() != null && line.getReturnedQuantity() != null,
					"退回确认明细不能为空");
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("sourceFlowId", line.getSourceFlowId());
			row.put("quantity", line.getReturnedQuantity().stripTrailingZeros().toPlainString());
			return row;
		}).sorted(Comparator.comparing(row -> Objects.toString(row.get("sourceFlowId"), ""))).toList();
		return JSONUtil.toJsonStr(Map.of("billId", command.getId(),
				"evidence", command.getEvidence().trim(), "lines", lines));
	}

	private Map<String, TransferStockPool> buildTransferPools(List<WmsLiveStockEntity> stocks,
	                                                          Map<String, MdmWarehouseEntity> warehouses,
	                                                          Map<String, BigDecimal> safetyByMaterial) {
		Map<String, TransferStockPool> pools = new LinkedHashMap<>();
		for (WmsLiveStockEntity stock : stocks) {
			String key = transferKey(stock.getWarehouseCode(), stock.getMatCode());
			TransferStockPool pool = pools.computeIfAbsent(key, ignored -> new TransferStockPool(
					stock.getWarehouseCode(), warehouses.get(stock.getWarehouseCode()).getWarehouseName(),
					stock.getMatCode(), safetyByMaterial.getOrDefault(stock.getMatCode(), BigDecimal.ZERO)));
			BigDecimal physical = stockNum(stock);
			BigDecimal reserved = wmsMaterialReservationService.activeReservedNum(stock.getId());
			pool.initialPhysical = pool.initialPhysical.add(physical);
			pool.initialReserved = pool.initialReserved.add(reserved);
			pool.rows.add(new TransferStockRow(stock, physical.subtract(reserved).max(BigDecimal.ZERO)));
		}
		return pools;
	}

	private Map<String, BigDecimal> targetInTransitTotals(String factoryCode, Set<String> targetWarehouses,
	                                                      Set<String> materialCodes) {
		if (targetWarehouses.isEmpty() || materialCodes.isEmpty()) return Map.of();
		List<WmsAllocationBillEntity> bills = list(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getFactoryCode, factoryCode)
				.in(WmsAllocationBillEntity::getAllocationState, IN_TRANSIT, PARTIALLY_RECEIVED)
				.eq(WmsAllocationBillEntity::getSourceDeducted, true)
				.in(WmsAllocationBillEntity::getNewWarehouseCode, targetWarehouses));
		if (bills.isEmpty()) return Map.of();
		Map<Long, String> targetByBill = bills.stream().collect(Collectors.toMap(
				WmsAllocationBillEntity::getId, WmsAllocationBillEntity::getNewWarehouseCode));
		List<WmsInoutFlowEntity> issueFlows = wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
				.in(WmsInoutFlowEntity::getBillId, targetByBill.keySet())
				.eq(WmsInoutFlowEntity::getTransferAction, "ISSUE")
				.in(WmsInoutFlowEntity::getMatCode, materialCodes));
		Map<Long, BigDecimal> received = new HashMap<>();
		if (!issueFlows.isEmpty()) {
			wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
							.in(WmsInoutFlowEntity::getBillId, targetByBill.keySet())
							.eq(WmsInoutFlowEntity::getTransferAction, "RECEIVE"))
					.forEach(flow -> received.merge(flow.getSourceFlowId(), BigDecimal.valueOf(flow.getMatNum()),
							BigDecimal::add));
		}
		Map<String, BigDecimal> totals = new HashMap<>();
		issueFlows.forEach(flow -> {
			BigDecimal remaining = BigDecimal.valueOf(flow.getMatNum())
					.subtract(received.getOrDefault(flow.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
			if (remaining.signum() > 0) totals.merge(
					transferKey(targetByBill.get(flow.getBillId()), flow.getMatCode()), remaining, BigDecimal::add);
		});
		return totals;
	}

	private Map<Long, BigDecimal> receivedQuantities(Long billId) {
		Map<Long, BigDecimal> received = new HashMap<>();
		wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
						.eq(WmsInoutFlowEntity::getBillId, billId)
						.eq(WmsInoutFlowEntity::getTransferAction, "RECEIVE"))
				.forEach(flow -> received.merge(flow.getSourceFlowId(), BigDecimal.valueOf(flow.getMatNum()),
						BigDecimal::add));
		return received;
	}

	private Map<Long, BigDecimal> returnedQuantities(Long billId) {
		Map<Long, BigDecimal> returned = new HashMap<>();
		wmsInoutFlowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
						.eq(WmsInoutFlowEntity::getBillId, billId)
						.eq(WmsInoutFlowEntity::getTransferAction, "CANCEL_RETURN"))
				.forEach(flow -> returned.merge(flow.getSourceFlowId(), BigDecimal.valueOf(flow.getMatNum()),
						BigDecimal::add));
		return returned;
	}

	private WmsInoutFlowEntity quantityCopy(WmsInoutFlowEntity source, BigDecimal quantity) {
		WmsInoutFlowEntity copy = new WmsInoutFlowEntity();
		BeanUtils.copyProperties(source, copy);
		copy.setMatNum(quantity.doubleValue());
		return copy;
	}

	private BigDecimal safetyStock(String materialCode) {
		try {
			R<MdmMaterialEntity> response = remoteMaterialService.getDetails(materialCode);
			require(response != null && response.isOk() && response.getData() != null,
					"无法读取物料[" + materialCode + "]安全库存");
			return Optional.ofNullable(response.getData().getMinStock()).orElse(BigDecimal.ZERO);
		} catch (CheckedException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new CheckedException("读取物料[" + materialCode + "]安全库存失败，Agent调拨已故障关闭", ex);
		}
	}

	private String transferFingerprint(WmsCrossWarehouseTransferDTO.Recommendation recommendation) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("referenceId", recommendation.getReferenceId());
		evidence.put("targetWarehouseCode", recommendation.getTargetWarehouseCode());
		evidence.put("materialCode", recommendation.getMaterialCode());
		evidence.put("requiredNum", recommendation.getRequiredNum());
		evidence.put("targetInTransitNum", recommendation.getTargetInTransitNum());
		evidence.put("appliedInTransitNum", recommendation.getAppliedInTransitNum());
		evidence.put("suggestedTransferNum", recommendation.getSuggestedTransferNum());
		evidence.put("uncoveredNum", recommendation.getUncoveredNum());
		evidence.put("status", recommendation.getStatus());
		evidence.put("sources", recommendation.getSourceCandidates().stream().map(candidate -> {
			Map<String, Object> source = new LinkedHashMap<>();
			source.put("warehouse", candidate.getSourceWarehouseCode());
			source.put("physical", candidate.getUsablePhysicalNum());
			source.put("reserved", candidate.getActiveReservedNum());
			source.put("safety", candidate.getSafetyStockNum());
			source.put("transferable", candidate.getTransferableNum());
			source.put("suggested", candidate.getSuggestedNum());
			source.put("leadHours", candidate.getTransferLeadHours());
			// v6证据摘要必须保持原形；仅v7路线策略或显式路线缺口纳入新增字段。
			if (candidate.getRoutePolicyId() != null || Boolean.FALSE.equals(candidate.getRoutePolicyMatched())) {
				source.put("leadMinutes", candidate.getTransferLeadMinutes());
				source.put("routePolicyId", candidate.getRoutePolicyId());
				source.put("routePolicyCode", candidate.getRoutePolicyCode());
				source.put("routePolicyFingerprint", candidate.getRoutePolicyFingerprint());
				source.put("transportMode", candidate.getTransportMode());
				source.put("urgencyLevel", candidate.getUrgencyLevel());
				source.put("routePolicyUrgencyLevel", candidate.getRoutePolicyUrgencyLevel());
				source.put("calendarMode", candidate.getCalendarMode());
				source.put("routePolicyMatched", candidate.getRoutePolicyMatched());
				source.put("routePolicyGap", candidate.getRoutePolicyGap());
			}
			source.put("stocks", candidate.getAllocations().stream().map(allocation -> {
				Map<String, Object> stock = new LinkedHashMap<>();
				stock.put("stockId", allocation.getStockId());
				stock.put("siteCode", allocation.getSiteCode());
				stock.put("containerCode", allocation.getContainerCode());
				stock.put("batchNo", allocation.getBatchNo());
				stock.put("supplierCode", allocation.getSupplierCode());
				stock.put("validEndDate", allocation.getValidEndDate());
				stock.put("stockInTime", allocation.getStockInTime());
				stock.put("available", allocation.getAvailableNum());
				stock.put("quantity", allocation.getSuggestedNum());
				return stock;
			}).toList());
			return source;
		}).toList());
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(JSONUtil.toJsonStr(evidence).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new CheckedException("跨仓建议证据摘要生成失败", ex);
		}
	}

	private WmsCrossWarehouseTransferDTO.Receipt existingAgentTransferReceipt(
			WmsAllocationBillEntity existing, WmsCrossWarehouseTransferDTO.AgentCommand command) {
		require(Boolean.TRUE.equals(existing.getSourceDeducted())
						&& Objects.equals(existing.getFactoryCode(), command.getFactoryCode())
						&& Objects.equals(existing.getOldWarehouseCode(), command.getSourceWarehouseCode())
						&& Objects.equals(existing.getNewWarehouseCode(), command.getTargetWarehouseCode())
						&& Objects.equals(existing.getSourceMrpId(), command.getMrpId())
						&& Objects.equals(existing.getSourceBusinessNo(), command.getIssueNo())
						&& Objects.equals(existing.getSourceMaterialCode(), command.getMaterialCode())
						&& existing.getSourceShortageQuantity() != null
						&& existing.getSourceShortageQuantity().compareTo(command.getExpectedShortageNum()) == 0,
				"Agent调拨幂等号已被不同请求使用");
		if (Boolean.TRUE.equals(command.getRoutePolicyRequired())) {
			require(Objects.equals(existing.getTransferRoutePolicyId(), command.getRoutePolicyId())
							&& Objects.equals(existing.getTransferRoutePolicyCode(), command.getRoutePolicyCode())
							&& Objects.equals(existing.getTransportMode(), command.getTransportMode())
							&& Objects.equals(existing.getUrgencyLevel(), command.getUrgencyLevel()),
					"Agent调拨幂等号已被不同路线策略使用");
		}
		Map<Long, BigDecimal> expected = command.getAllocations().stream().collect(Collectors.toMap(
				WmsCrossWarehouseTransferDTO.StockAllocation::getStockId,
				WmsCrossWarehouseTransferDTO.StockAllocation::getSuggestedNum, (left, right) -> left));
		List<WmsInoutFlowEntity> flows = issueFlows(existing);
		require(flows.stream().allMatch(flow -> Objects.equals(command.getMaterialCode(), flow.getMatCode())),
				"Agent调拨幂等号已被不同物料请求使用");
		Map<Long, BigDecimal> actual = flows.stream()
				.collect(Collectors.toMap(WmsInoutFlowEntity::getSourceStockId,
						flow -> BigDecimal.valueOf(flow.getMatNum()), BigDecimal::add));
		require(expected.size() == actual.size() && expected.entrySet().stream().allMatch(entry ->
						actual.containsKey(entry.getKey()) && entry.getValue().compareTo(actual.get(entry.getKey())) == 0),
				"Agent调拨幂等号已被不同库存分配使用");
		return transferReceipt(existing, command.getMaterialCode(), command.getTransferNum());
	}

	private WmsCrossWarehouseTransferDTO.Receipt transferReceipt(WmsAllocationBillEntity bill, String materialCode,
	                                                             BigDecimal transferNum) {
		return new WmsCrossWarehouseTransferDTO.Receipt().setAllocationBillId(bill.getId())
				.setBillNo(bill.getBillNo()).setAllocationState(bill.getAllocationState())
				.setRequestId(bill.getRequestId()).setSourceWarehouseCode(bill.getOldWarehouseCode())
				.setTargetWarehouseCode(bill.getNewWarehouseCode()).setMaterialCode(materialCode)
				.setTransferNum(transferNum);
	}

	private BigDecimal stockNum(WmsLiveStockEntity stock) {
		return stock == null || stock.getMatNum() == null ? BigDecimal.ZERO : BigDecimal.valueOf(stock.getMatNum());
	}

	private String transferKey(String warehouseCode, String materialCode) {
		return warehouseCode + "\u001f" + materialCode;
	}

	private String urgencyLevel(WmsCrossWarehouseTransferDTO.Requirement requirement) {
		if (StrUtil.isNotBlank(requirement.getUrgencyLevel())) {
			String explicit = requirement.getUrgencyLevel().trim().toUpperCase(Locale.ROOT);
			require(Set.of("NORMAL", "URGENT", "EMERGENCY").contains(explicit), "跨仓需求紧急等级无效");
			return explicit;
		}
		LocalDate demandDate = requirement.getDemandDate();
		if (demandDate == null || !demandDate.isAfter(LocalDate.now())) return "EMERGENCY";
		if (!demandDate.isAfter(LocalDate.now().plusDays(1))) return "URGENT";
		return "NORMAL";
	}

	private void requireText(String value, String message) {
		require(StrUtil.isNotBlank(value) && value.length() <= 64, message);
	}

	private void requireCode(String value, String message) {
		require(StrUtil.isNotBlank(value) && value.length() <= 100, message);
	}

	private void requireWarehousePermission(String permission, String warehouseCode, String message) {
		require(permissionService.hasPermission(permission, permission + "_" + warehouseCode), message);
	}

	private void requireFactory(String factoryCode) {
		String currentFactory = FactoryContextHolder.getFactoryCode();
		require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, factoryCode),
				"业务数据超出当前工厂范围");
	}

	private void requireTransferConfiguration(String sourceWarehouseCode, String targetWarehouseCode) {
		require(warehouseBusinessGuard.check(sourceWarehouseCode, "allocation", false),
				"源仓未配置调拨发出业务");
		require(warehouseBusinessGuard.check(targetWarehouseCode, "allocation", true),
				"目标仓未配置调拨接收业务");
	}

	private String operatorName(MomUser user) {
		return StrUtil.blankToDefault(user.getName(), user.getUsername());
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * app-新增调拨单
	 *
	 * @param wmsAllocationBillAdd 调拨单接收DTO
	 * @return R
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean appSaveAllocationBill(WmsAllocationBillAppDTO wmsAllocationBillAdd) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		WmsAllocationMobileCommand.normalize(wmsAllocationBillAdd, scope.factoryCode());
		String factoryCode = scope.factoryCode();
		requireCode(wmsAllocationBillAdd.getOldWarehouseCode(), "调出仓库不能为空");
		requireCode(wmsAllocationBillAdd.getNewWarehouseCode(), "调入仓库不能为空");
		require(!wmsAllocationBillAdd.getOldWarehouseCode().contains("*") && !wmsAllocationBillAdd.getOldWarehouseCode().contains("?"),
				"仓库编码不能包含权限通配符");
		requireWarehousePermission("wms_wmsAllocationBill_add", wmsAllocationBillAdd.getOldWarehouseCode(), "无权从该仓库发起调拨");
		wmsAllocationBillAdd.setFactoryCode(factoryCode);
		String requestSnapshot = appRequestSnapshot(wmsAllocationBillAdd);
		// 同一租户同号重试先串行，再读取永久回执；避免并发检查均未命中后重复扣库存。
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && baseMapper.lockAgentTransfer(
						"MOBILE_REQUEST:" + tenant + ":" + wmsAllocationBillAdd.getRequestId()) == 1,
				"锁定移动调拨请求失败");
		var cancelledOriginal = mobileCancelReceipts.find(scope.tenantId(), wmsAllocationBillAdd.getRequestId());
		if (cancelledOriginal != null) {
			WmsAllocationMobileReceiptService.cancelled(cancelledOriginal, scope,
					wmsAllocationBillAdd.getRequestId(), wmsAllocationBillAdd.getOldWarehouseCode());
			require(Objects.equals(requestSnapshot, cancelledOriginal.requestSnapshot()), "调拨幂等号已被不同请求使用");
			throw new CheckedException("原调拨请求已撤销，请核对原结果后重新发起");
		}
		WmsAllocationBillEntity existing = getOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
				.eq(WmsAllocationBillEntity::getTenantId, scope.tenantId())
				.eq(WmsAllocationBillEntity::getFactoryCode, factoryCode)
				.eq(WmsAllocationBillEntity::getRequestId, wmsAllocationBillAdd.getRequestId()), false);
		if (existing != null) {
			require(Objects.equals(existing.getRequestSnapshot(), requestSnapshot), "调拨幂等号已被不同请求使用");
			WmsAllocationMobileReceiptService.confirmed(existing, scope,
					wmsAllocationBillAdd.getRequestId(), wmsAllocationBillAdd.getOldWarehouseCode());
			return Boolean.TRUE;
		}
		warehouseBusinessGuard.checkMobile(wmsAllocationBillAdd.getOldWarehouseCode());
		warehouseBusinessGuard.checkMobile(wmsAllocationBillAdd.getNewWarehouseCode());
		MdmWarehouseEntity source = requireWarehouse(wmsAllocationBillAdd.getOldWarehouseCode(), factoryCode);
		MdmWarehouseEntity target = requireWarehouse(wmsAllocationBillAdd.getNewWarehouseCode(), factoryCode);
		require(!Objects.equals(source.getWarehouseCode(), target.getWarehouseCode()), "源仓和目标仓不能相同");
		requireTransferConfiguration(source.getWarehouseCode(), target.getWarehouseCode());
		require(count(new LambdaQueryWrapper<WmsAllocationBillEntity>()
						.eq(WmsAllocationBillEntity::getBillNo, wmsAllocationBillAdd.getBillNo())) == 0,
				"调拨单号已存在");
		MdmWarehouseLocationEntity sourceSite = requireLocation(wmsAllocationBillAdd.getOldSiteCode(),
				source.getWarehouseCode(), factoryCode);
		MdmWarehouseLocationEntity siteEntity = requireLocation(wmsAllocationBillAdd.getNewSiteCode(),
				target.getWarehouseCode(), factoryCode);
		requireContainer(wmsAllocationBillAdd.getOldContainerCode(), source);
		requireContainer(wmsAllocationBillAdd.getNewContainerCode(), target);
		require(StrUtil.isNotBlank(wmsAllocationBillAdd.getMatUnit()), "即时调拨必须指定物料标准单位");
		wmsAllocationBillAdd.setOldWarehouseName(source.getWarehouseName());
		wmsAllocationBillAdd.setOldSiteName(sourceSite.getLocationName());
		wmsAllocationBillAdd.setNewWarehouseName(target.getWarehouseName());
		wmsAllocationBillAdd.setNewSiteName(siteEntity.getLocationName());
		wmsAllocationBillAdd.setFactoryCode(factoryCode);
		MomUser user = SecurityUtils.getUser();
		WmsAllocationBillEntity wmsAllocationBill = new WmsAllocationBillEntity();
		BeanUtils.copyProperties(wmsAllocationBillAdd, wmsAllocationBill);
		wmsAllocationBill.setTenantId(scope.tenantId());
		wmsAllocationBill.setRequestSnapshot(requestSnapshot);
		wmsAllocationBill.setAllocationState(RECEIVED);
		wmsAllocationBill.setSourceDeducted(Boolean.TRUE);
		wmsAllocationBill.setReceiveRequestId(wmsAllocationBillAdd.getRequestId());
		wmsAllocationBill.setReceiveRequestSnapshot(requestSnapshot);
		wmsAllocationBill.setIssueTime(LocalDateTime.now());
		wmsAllocationBill.setIssueBy(user.getUsername());
		wmsAllocationBill.setIssueByName(operatorName(user));
		wmsAllocationBill.setFinishTime(LocalDateTime.now());
		wmsAllocationBill.setFinishByName(operatorName(user));
		require(this.save(wmsAllocationBill), "即时调拨单保存失败");

		wmsAllocationBillAdd.setAllocationBillId(wmsAllocationBill.getId());
		wmsAllocationBillAdd.setBillNo(wmsAllocationBill.getBillNo());
		wmsAllocationBillAdd.setFactoryCode(wmsAllocationBill.getFactoryCode());
		WmsLiveStockMoveDTO wmsLiveStockMoveDTO = new WmsLiveStockMoveDTO();
		BeanUtils.copyProperties(wmsAllocationBillAdd, wmsLiveStockMoveDTO);
		List<WmsStockMoveSlice> slices = wmsLiveStockService.billStockMoveWithEvidence(wmsLiveStockMoveDTO);
		recordImmediateTransferFlows(wmsAllocationBill, slices, wmsAllocationBillAdd);
		return Boolean.TRUE;
	}

	void recordImmediateTransferFlows(WmsAllocationBillEntity bill, List<WmsStockMoveSlice> slices,
	                                  WmsAllocationBillAppDTO command) {
		require(bill != null && bill.getId() != null && command != null && slices != null && !slices.isEmpty(),
				"即时调拨缺少实际移动结果");
		BigDecimal recorded = BigDecimal.ZERO;
		for (WmsStockMoveSlice slice : slices) {
			require(slice != null && slice.sourceStock() != null && slice.sourceStock().getId() != null
					&& slice.targetStockId() != null && Double.isFinite(slice.quantity())
					&& slice.quantity() > 0, "即时调拨返回无效库存切片");
			WmsLiveStockEntity source = slice.sourceStock();
			require(Objects.equals(source.getWarehouseCode(), command.getOldWarehouseCode())
							&& Objects.equals(source.getSiteCode(), command.getOldSiteCode())
							&& Objects.equals(source.getFactoryCode(), bill.getFactoryCode()),
					"即时调拨来源库存与单据不一致");
			if (StrUtil.isNotBlank(command.getMatUnit())) {
				require(Objects.equals(source.getMatUnit(), command.getMatUnit()),
						"即时调拨标准单位与实际来源库存不一致");
			}
			if (command.getSourceStockId() != null) {
				require(Objects.equals(command.getSourceStockId(), source.getId()),
						"即时调拨来源库存与扫码选择不一致");
			}
			if (Boolean.TRUE.equals(source.getSerialManaged())) {
				require(Objects.equals(command.getSourceStockId(), source.getId())
								&& StrUtil.isNotBlank(command.getMatBarcode())
								&& Objects.equals(command.getMatBarcode(), source.getMatBarcode()),
						"单件序列号调拨必须核对精确来源库存和实物条码");
				WmsSerialStockGuard.requireWhole(source, BigDecimal.valueOf(slice.quantity()));
			}
			recorded = recorded.add(BigDecimal.valueOf(slice.quantity()));
		}
		require(command.getOperateNum() != null && Double.isFinite(command.getOperateNum())
						&& recorded.compareTo(BigDecimal.valueOf(command.getOperateNum())) == 0,
				"即时调拨流水数量与实际移动数量不一致");
		for (WmsStockMoveSlice slice : slices) {
			WmsLiveStockEntity proof = new WmsLiveStockEntity();
			BeanUtils.copyProperties(slice.sourceStock(), proof);
			proof.setMatNum(slice.quantity());
			WmsInoutFlowEntity issue = issueFlow(bill, proof, slice.sourceStock().getId());
			require(wmsInoutFlowMapper.insert(issue) == 1 && issue.getId() != null,
					"即时调拨出库流水保存失败");
			WmsInoutFlowEntity receipt = inFlow(bill, issue, command.getNewWarehouseCode(),
					command.getNewWarehouseName(), command.getNewSiteCode(), command.getNewSiteName(),
					command.getNewContainerCode(), "RECEIVE");
			require(wmsInoutFlowMapper.insert(receipt) == 1, "即时调拨入库流水保存失败");
		}
	}

	private String appRequestSnapshot(WmsAllocationBillAppDTO command) {
		return WmsAllocationMobileCommand.snapshot(command);
	}

	/**
	 * 导出excel 表格
	 *
	 * @param wmsAllocationBill 查询条件
	 * @param ids               导出指定ID
	 * @return excel 文件流
	 */
	@Override
	public List<WmsAllocationBillExcelVO> export(WmsAllocationBillEntity wmsAllocationBill, Long[] ids) {
		return this.list(Wrappers.lambdaQuery(wmsAllocationBill).in(ArrayUtil.isNotEmpty(ids), WmsAllocationBillEntity::getId, ids))
				.stream()
				.map(allocationBill -> {
					WmsAllocationBillExcelVO wmsAllocationBillExcelVO = new WmsAllocationBillExcelVO();
					BeanUtils.copyProperties(allocationBill, wmsAllocationBillExcelVO);
					return wmsAllocationBillExcelVO;
				})
				.collect(Collectors.toList());
	}

	private static final class TransferStockPool {
		private final String warehouseCode;
		private final String warehouseName;
		private final String materialCode;
		private final BigDecimal safetyStock;
		private final List<TransferStockRow> rows = new ArrayList<>();
		private BigDecimal initialPhysical = BigDecimal.ZERO;
		private BigDecimal initialReserved = BigDecimal.ZERO;

		private TransferStockPool(String warehouseCode, String warehouseName, String materialCode,
		                          BigDecimal safetyStock) {
			this.warehouseCode = warehouseCode;
			this.warehouseName = warehouseName;
			this.materialCode = materialCode;
			this.safetyStock = safetyStock;
		}

		private BigDecimal transferable() {
			BigDecimal rowAvailable = rows.stream().map(row -> row.remaining)
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			return rowAvailable.subtract(safetyStock).max(BigDecimal.ZERO);
		}

		private List<WmsCrossWarehouseTransferDTO.StockAllocation> allocate(BigDecimal requested) {
			BigDecimal remainingRequest = requested.min(transferable());
			List<WmsCrossWarehouseTransferDTO.StockAllocation> result = new ArrayList<>();
			for (TransferStockRow row : rows) {
				if (remainingRequest.compareTo(BigDecimal.ZERO) <= 0) break;
				BigDecimal before = row.remaining;
				BigDecimal take = before.min(remainingRequest);
				if (take.compareTo(BigDecimal.ZERO) <= 0) continue;
				WmsLiveStockEntity stock = row.stock;
				result.add(new WmsCrossWarehouseTransferDTO.StockAllocation().setStockId(stock.getId())
						.setSiteCode(stock.getSiteCode()).setSiteName(stock.getSiteName())
						.setContainerCode(stock.getContainerCode()).setBatchNo(stock.getMatBatch())
						.setSupplierCode(stock.getSupplierCode()).setValidEndDate(stock.getMatValidEndDate())
						.setStockInTime(stock.getInTime()).setAvailableNum(before).setSuggestedNum(take));
				row.remaining = row.remaining.subtract(take);
				remainingRequest = remainingRequest.subtract(take);
			}
			return result;
		}
	}

	private static final class TransferStockRow {
		private final WmsLiveStockEntity stock;
		private BigDecimal remaining;

		private TransferStockRow(WmsLiveStockEntity stock, BigDecimal remaining) {
			this.stock = stock;
			this.remaining = remaining;
		}
	}
}
