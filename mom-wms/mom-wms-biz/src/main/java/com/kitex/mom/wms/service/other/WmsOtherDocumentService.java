package com.kitex.mom.wms.service.other;

import com.kitex.common.core.exception.CheckedException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.vo.*;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeFactory;
import com.kitex.mom.wms.service.outBill.WmsOutBillFactory;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import com.kitex.mom.wms.service.WmsOtherBusinessTypes;

import static com.kitex.mom.wms.service.other.WmsOtherDocumentAccess.Kind.*;

import com.kitex.mom.wms.service.other.WmsOtherDocumentAccess.Kind;
import com.kitex.mom.wms.service.other.WmsOtherDocumentAccess.Original;

/**
 * 其他收发ID动作的事务编排：先核原单真实类型和权限，再派发原专用处理器。
 * 批量删除先锁定并核对全部原单，分型处理共用一个事务，不重写原来源、审核或库存语义。
 */
@Service
@RequiredArgsConstructor
public class WmsOtherDocumentService {
	private final WmsOtherDocumentAccess access;

	/**
	 * 新增其他入库单：关联通知沿原仓出入库权限，手工建单沿新增权限。
	 * 同事务锁定原通知并核类型/仓库；直接通过另核独立权限，原专用来源门禁保持。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean createInBill(WmsInBillEntity input, boolean pass) {
		require(input != null, "请提交完整入库单");
		String type = WmsOtherBusinessTypes.requireInbound(input.getBillType());
		if (input.getNoticeId() == null) {
			access.requireCreate(IN_BILL, input.getWarehouseCode(), pass ? "add_review" : "add");
		} else {
			var notice = one(IN_NOTICE, input.getNoticeId(), "inout", true);
			require(Objects.equals(notice.type(), type)
							&& Objects.equals(notice.warehouse(), input.getWarehouseCode()),
					"单据类型或仓库与原通知不一致，请重新读取通知");
			if (pass) {
				access.requireCreate(IN_BILL, notice.warehouse(), "add_review");
			}
		}
		var handler = WmsInBillFactory.getHandler(type);
		return succeeded(pass ? handler.addAndPass(input) : handler.saveDeep(input));
	}

	/**
	 * 新增其他出库单：关联通知沿原仓出入库权限，手工建单沿新增权限。
	 * 同事务锁定原通知并核类型/仓库；直接通过另核独立权限，原专用来源门禁保持。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean createOutBill(WmsOutBillEntity input, boolean pass) {
		require(input != null, "请提交完整出库单");
		String type = WmsOtherBusinessTypes.requireOutbound(input.getBillType());
		if (input.getNoticeId() == null) {
			access.requireCreate(OUT_BILL, input.getWarehouseCode(), pass ? "add_review" : "add");
		} else {
			var notice = one(OUT_NOTICE, input.getNoticeId(), "inout", true);
			require(Objects.equals(notice.type(), type)
							&& Objects.equals(notice.warehouse(), input.getWarehouseCode()),
					"单据类型或仓库与原通知不一致，请重新读取通知");
			if (pass) {
				access.requireCreate(OUT_BILL, notice.warehouse(), "add_review");
			}
		}
		var handler = WmsOutBillFactory.getHandler(type);
		return succeeded(pass ? handler.addAndPass(input) : handler.saveDeep(input));
	}

	/**
	 * 查看实际其他入库单；不以查看动作获取写锁。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsInBillEntity viewInBill(Long id) {
		return WmsInBillFactory.getHandler(one(IN_BILL, id, "view", false).type()).selectById(id);
	}

	/**
	 * 查看实际其他出库单，并沿原类型获取可用库存等详情。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsOutBillEntity viewOutBill(Long id) {
		return WmsOutBillFactory.getHandler(one(OUT_BILL, id, "view", false).type()).selectById(id);
	}

	/**
	 * 原草稿类型保持不变；修改前锁原单并核原仓及目标仓编辑权限。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean updateInBill(WmsInBillEntity input) {
		require(input != null, "请提交完整入库单");
		var row = one(IN_BILL, input.getId(), "edit", true);
		access.requireEdit(IN_BILL, row, input.getBillType(), input.getWarehouseCode());
		return succeeded(WmsInBillFactory.getHandler(row.type()).updateDeep(input));
	}

	/**
	 * 原出库草稿按保存类型编辑，采购退供等仍由原处理器核对批准来源。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean updateOutBill(WmsOutBillEntity input) {
		require(input != null, "请提交完整出库单");
		var row = one(OUT_BILL, input.getId(), "edit", true);
		access.requireEdit(OUT_BILL, row, input.getBillType(), input.getWarehouseCode());
		return succeeded(WmsOutBillFactory.getHandler(row.type()).updateDeep(input));
	}

	/**
	 * 提交前锁定其他入库原单，状态及来源校验继续由原类型处理。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean commitInBill(Long id) {
		return succeeded(WmsInBillFactory.getHandler(one(IN_BILL, id, "commit", true).type()).commitBill(id));
	}

	/**
	 * 提交其他出库原单，禁止以其他入口提交生产领料或销售单。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean commitOutBill(Long id) {
		return succeeded(WmsOutBillFactory.getHandler(one(OUT_BILL, id, "commit", true).type()).commitBill(id));
	}

	/**
	 * 审核实际其他入库单；实物及通知联动仍在原处理器事务内。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean passInBill(Long id) {
		return succeeded(WmsInBillFactory.getHandler(one(IN_BILL, id, "review", true).type()).passBill(id));
	}

	/**
	 * 审核按真实出库类型派发，不能用默认处理器跳过采购退供等专用门禁。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean passOutBill(Long id) {
		return succeeded(WmsOutBillFactory.getHandler(one(OUT_BILL, id, "review", true).type()).passBill(id));
	}

	/**
	 * 驳回只使用原单身份及原类型，原因由原审核合同校验。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean rejectInBill(WmsInBillEntity input) {
		require(input != null, "请选择待审核入库单");
		return succeeded(WmsInBillFactory.getHandler(one(IN_BILL, input.getId(), "review", true).type())
				.rejectBill(input));
	}

	/**
	 * 其他出库驳回保留专用类型及原审核校验。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean rejectOutBill(WmsOutBillEntity input) {
		require(input != null, "请选择待审核出库单");
		return succeeded(WmsOutBillFactory.getHandler(one(OUT_BILL, input.getId(), "review", true).type())
				.rejectBill(input));
	}

	/**
	 * 查看原其他入库通知，不能读取采购或成品通知。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsInNoticeEntity viewInNotice(Long id) {
		return WmsInNoticeFactory.getHandler(one(IN_NOTICE, id, "view", false).type()).selectById(id);
	}

	/**
	 * 查看原其他出库通知，沿原处理器组装真实明细。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsOutNoticeEntity viewOutNotice(Long id) {
		return WmsOutNoticeFactory.getHandler(one(OUT_NOTICE, id, "view", false).type()).selectById(id);
	}

	/**
	 * 选库须具备实际原仓的出入库权限，不以通知查看权限代替。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsOutBillEntity stockForOutNotice(Long id) {
		return WmsOutNoticeFactory.getHandler(one(OUT_NOTICE, id, "inout", false).type())
				.getLiveStockByNoticeId(id);
	}

	/**
	 * 原通知按原类型编辑；通知维护沿生成菜单的新增/维护权限。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean updateInNotice(WmsInNoticeEntity input) {
		require(input != null, "请提交完整入库通知");
		var row = one(IN_NOTICE, input.getId(), "add", true);
		access.requireEdit(IN_NOTICE, row, input.getBillType(), input.getWarehouseCode());
		return succeeded(WmsInNoticeFactory.getHandler(row.type()).updateDeep(input));
	}

	/**
	 * 出库通知修改不接受切换业务类型或未经授权的目标仓。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean updateOutNotice(WmsOutNoticeEntity input) {
		require(input != null, "请提交完整出库通知");
		var row = one(OUT_NOTICE, input.getId(), "add", true);
		access.requireEdit(OUT_NOTICE, row, input.getBillType(), input.getWarehouseCode());
		return succeeded(WmsOutNoticeFactory.getHandler(row.type()).updateDeep(input));
	}

	/**
	 * 作废前锁定实际其他入库通知，原来源及已入库阻断继续生效。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public void voidInNotice(Long id) {
		WmsInNoticeFactory.getHandler(one(IN_NOTICE, id, "del", true).type()).voidObjs(id);
	}

	/**
	 * 其他出库通知按原类型作废，不进入生产领料等来源撤销通道。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public void voidOutNotice(Long id) {
		WmsOutNoticeFactory.getHandler(one(OUT_NOTICE, id, "del", true).type()).voidObjs(id);
	}

	/**
	 * 全部原入库单预检后分型删除，任一处理器失败则整批回滚。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean removeInBills(String[] ids) {
		return remove(IN_BILL, ids);
	}

	/**
	 * 全部原出库单预检后分型删除，不留下部分成功批次。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean removeOutBills(String[] ids) {
		return remove(OUT_BILL, ids);
	}

	/**
	 * 删除其他入库通知草稿；不改变原来源通知不可删除规则。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean removeInNotices(String[] ids) {
		return remove(IN_NOTICE, ids);
	}

	/**
	 * 删除其他出库通知草稿；每张原单必须均有效且有权。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean removeOutNotices(String[] ids) {
		return remove(OUT_NOTICE, ids);
	}

	/**
	 * 主表导出与身份/权限核对共用快照，不能在核对后改仓或切换类型绕过范围。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsInBillExcelVO> exportInBills(QueryWrapper<WmsInBillEntity> wrapper, Long[] ids) {
		access.checkExportIds(IN_BILL, ids, false);
		access.restrictMain(IN_BILL, wrapper, "export");
		return WmsInBillFactory.getHandler("other_in").list(wrapper);
	}

	/**
	 * 明细ID、主单组织与授权仓库共用快照；空选择沿当前筛选导出，空授权不输出记录。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsInBillDetailExcelVO> exportInBillDetails(WmsInBillDetailQueryDTO query, Long[] ids) {
		Long[] selected = ids == null || ids.length == 0 ? null : ids;
		access.checkExportIds(IN_BILL, selected, true);
		access.restrictDetails(IN_BILL, query, "export");
		return WmsInBillFactory.getHandler("other_in").listDetails(query, selected);
	}

	/**
	 * 主表导出与身份/权限核对共用快照，不能在核对后改仓或切换类型绕过范围。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsInNoticeExcelVO> exportInNotices(QueryWrapper<WmsInNoticeEntity> wrapper, Long[] ids) {
		access.checkExportIds(IN_NOTICE, ids, false);
		access.restrictMain(IN_NOTICE, wrapper, "export");
		return WmsInNoticeFactory.getHandler("other_in").list(wrapper);
	}

	/**
	 * 明细ID、主单组织与授权仓库共用快照；空选择沿当前筛选导出，空授权不输出记录。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsInNoticeDetailExcelVO> exportInNoticeDetails(WmsInNoticeDetailQueryDTO query, Long[] ids) {
		Long[] selected = ids == null || ids.length == 0 ? null : ids;
		access.checkExportIds(IN_NOTICE, selected, true);
		access.restrictDetails(IN_NOTICE, query, "export");
		return WmsInNoticeFactory.getHandler("other_in").listDetails(query, selected);
	}

	/**
	 * 主表导出与身份/权限核对共用快照，不能在核对后改仓或切换类型绕过范围。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsOutBillExcelVO> exportOutBills(QueryWrapper<WmsOutBillEntity> wrapper, Long[] ids) {
		access.checkExportIds(OUT_BILL, ids, false);
		access.restrictMain(OUT_BILL, wrapper, "export");
		return WmsOutBillFactory.getHandler("other_out").list(wrapper);
	}

	/**
	 * 明细ID、主单组织与授权仓库共用快照；空选择沿当前筛选导出，空授权不输出记录。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsOutBillDetailExcelVO> exportOutBillDetails(WmsInBillDetailQueryDTO query, Long[] ids) {
		Long[] selected = ids == null || ids.length == 0 ? null : ids;
		access.checkExportIds(OUT_BILL, selected, true);
		access.restrictDetails(OUT_BILL, query, "export");
		return WmsOutBillFactory.getHandler("other_out").listDetails(query, selected);
	}

	/**
	 * 主表导出与身份/权限核对共用快照，不能在核对后改仓或切换类型绕过范围。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsOutNoticeExcelVO> exportOutNotices(QueryWrapper<WmsOutNoticeEntity> wrapper, Long[] ids) {
		access.checkExportIds(OUT_NOTICE, ids, false);
		access.restrictMain(OUT_NOTICE, wrapper, "export");
		return WmsOutNoticeFactory.getHandler("other_out").list(wrapper);
	}

	/**
	 * 明细ID、主单组织与授权仓库共用快照；空选择沿当前筛选导出，空授权不输出记录。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsOutNoticeDetailExcelVO> exportOutNoticeDetails(WmsInNoticeDetailQueryDTO query, Long[] ids) {
		Long[] selected = ids == null || ids.length == 0 ? null : ids;
		access.checkExportIds(OUT_NOTICE, selected, true);
		access.restrictDetails(OUT_NOTICE, query, "export");
		return WmsOutNoticeFactory.getHandler("other_out").listDetails(query, selected);
	}

	private Original one(Kind kind, Long id, String action, boolean lock) {
		return access.originals(kind, Collections.singletonList(id), action, lock).get(0);
	}

	/**
	 * 原单升序全部加锁后再分型，事务覆盖整个批次；不把部分失败包装成成功。
	 */
	private Boolean remove(Kind kind, String[] supplied) {
		var rows = access.originals(kind, access.identifiers(supplied), "del", true);
		var groups = new LinkedHashMap<String, List<String>>();
		for (var row : rows) {
			groups.computeIfAbsent(row.type(), ignored -> new java.util.ArrayList<>()).add(row.id().toString());
		}
		for (var group : groups.entrySet()) {
			String[] ids = group.getValue().toArray(String[]::new);
			Boolean result = switch (kind) {
				case IN_BILL -> WmsInBillFactory.getHandler(group.getKey()).removeDeep(ids);
				case OUT_BILL -> WmsOutBillFactory.getHandler(group.getKey()).removeDeep(ids);
				case IN_NOTICE -> WmsInNoticeFactory.getHandler(group.getKey()).removeDeep(ids);
				case OUT_NOTICE -> WmsOutNoticeFactory.getHandler(group.getKey()).removeDeep(ids);
			};
			require(Boolean.TRUE.equals(result), "批量删除未全部完成，本次操作已回滚");
		}
		return Boolean.TRUE;
	}

	/**
	 * 必须成功的单据写入以真实结果收口，false/null触发同事务回滚。
	 */
	private Boolean succeeded(Boolean result) {
		require(Boolean.TRUE.equals(result), "单据办理未完成，本次操作已回滚");
		return Boolean.TRUE;
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
