package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPickingWmsV1DTO;
import com.kitex.mom.mes.mapper.MesPickingWmsV1Mapper;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.kitex.mom.mes.service.impl.MesPickingWmsCanonical.*;

/**
 * WMS公开V1消费编排；运输与查询不持本域业务锁，原号/原文由短事务Journal可靠保存。
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MesPickingWmsFacade {
	private final MesPickingWmsJournal journal;
	private final RemoteProducePickingNoticeService wms;

	/**
	 * 本域持久准备后返回原件；prepare不向WMS发送写命令。
	 */
	public MesPickingWmsV1DTO.Context prepare(MesPickingWmsV1DTO.Prepare command) {
		return journal.prepare(command);
	}

	/**
	 * 原号查询优先；只在显式执行时按冻结完整批量运输，永不降级legacy或换号。
	 */
	public MesPickingWmsV1DTO.Context execute(MesPickingWmsV1DTO.Request request) {
		return resolve(request, true);
	}

	/**
	 * 不运输新的WMS写命令，核实原Proof并登记/应用本域状态；不是只读GET。
	 */
	public MesPickingWmsV1DTO.Context reconcile(MesPickingWmsV1DTO.Request request) {
		return resolve(request, false);
	}

	/**
	 * 网络异常及NOT_FOUND均保留原命令，不自动循环、不记虚假拒绝终态。
	 */
	private MesPickingWmsV1DTO.Context resolve(MesPickingWmsV1DTO.Request request, boolean transport) {
		var row = journal.load(request, transport);
		if ("ACCEPTED".equals(row.getState())) {
			return journal.apply(request);
		}
		var frozen = MesPickingWmsProof.frozen(row);
		List<Receipt> proof = lookup(row, frozen);
		if (proof == null) {
			if (!transport) {
				throw new CheckedException("原WMS回执尚未完整查到，请保留原号继续核对；未发送新的受理或撤回");
			}
			// 再核当前原号仍可运输；取消和旧受理即使交叉也由WMS同来源修订锁裁决。
			journal.load(request, true);
			if ("SUBMIT".equals(frozen.action())) {
				probe(row, frozen);
				proof = data(wms.acceptBatch(frozen.commands()), "WMS批量受理结果未确认");
			} else {
				proof = List.of(data(wms.cancelSource(frozen.cancel()), "WMS来源撤回结果未确认"));
			}
		}
		proof = MesPickingWmsProof.verify(row, proof, journal.knownSubmission(row));
		journal.record(request, proof);
		return journal.apply(request);
	}

	/**
	 * 逐原号查询完整外层及Proof；部分查到不能冒充完整批受理，执行仍重放完整原批。
	 */
	private List<Receipt> lookup(MesPickingWmsV1Mapper.Command row, MesPickingWmsProof.Frozen frozen) {
		List<Receipt> proofs = new ArrayList<>();
		boolean absent = false;
		if ("CANCEL".equals(frozen.action())) {
			Lookup lookup = data(wms.receiptByRequest(row.getRequestId(), ""), "原撤回回执读取失败");
			verifyLookup(row, row.getRequestId(), lookup);
			return "FOUND".equals(lookup.status()) ? List.of(lookup.receipt()) : null;
		}
		for (Command command : frozen.commands()) {
			Lookup lookup = data(wms.receiptByRequest(command.getRequestId(), command.getNotice().getWarehouseCode()),
					"原分仓受理回执读取失败");
			verifyLookup(row, command.getRequestId(), lookup);
			if ("FOUND".equals(lookup.status())) {
				proofs.add(lookup.receipt());
			} else {
				absent = true;
			}
		}
		return absent ? null : proofs;
	}

	/**
	 * 用实际来源的公开只读合同核运行V1，失败不回退旧add；状态与受理永久Proof分开。
	 */
	private void probe(MesPickingWmsV1Mapper.Command row, MesPickingWmsProof.Frozen frozen) {
		for (Command command : frozen.commands()) {
			Source source = data(wms.receiptBySource(command.getNotice().getWarehouseCode(), row.getBillId(),
							row.getSourceRevision()),
					"WMS V1来源合同不可核验，原命令未改号");
			require(source.contractVersion() == 1 && Objects.equals(source.tenantId(), row.getTenantId())
							&& row.getFactoryCode().equals(source.factoryCode()) &&
							Objects.equals(source.documentId(), row.getBillId())
							&& source.requestedRevision() == row.getSourceRevision()
							&& command.getNotice().getWarehouseCode().equals(source.warehouseCode())
							&& Set.of("ACCEPTED", "NOT_FOUND").contains(source.acceptanceStatus()),
					"WMS来源版本/组织/修订不符或已有legacy，禁止新建通知");
			if ("ACCEPTED".equals(source.acceptanceStatus())) {
				require(Set.of("0", "1", "2").contains(source.noticeState()), "原通知已经作废，不能重建旧修订");
			}
		}
	}

	/**
	 * FOUND必须附完整Proof，NOT_FOUND必须无Proof；服务降级不解释为查无。
	 */
	private void verifyLookup(MesPickingWmsV1Mapper.Command row, String request, Lookup lookup) {
		require(lookup.contractVersion() == 1 && Objects.equals(lookup.tenantId(), row.getTenantId())
						&& row.getFactoryCode().equals(lookup.factoryCode()) && request.equals(lookup.requestId())
						&& ("FOUND".equals(lookup.status()) && lookup.receipt() != null
						|| "NOT_FOUND".equals(lookup.status()) && lookup.receipt() == null),
				"WMS原请求外层回执不完整或范围不一致");
	}

	/**
	 * HTTP成功也必须核R业务码及实际数据；失败永不声明原动作没有发生。
	 */
	private <T> T data(R<T> response, String message) {
		if (response == null || !response.isOk() || response.getData() == null) {
			throw new CheckedException(
					message + "，请保留原请求核对" + (response == null ? "" : "：" + response.getMsg()));
		}
		return response.getData();
	}
}
