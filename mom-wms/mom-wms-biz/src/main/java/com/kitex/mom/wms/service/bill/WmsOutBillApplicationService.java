package com.kitex.mom.wms.service.bill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.service.outBill.WmsOutBillFactory;
import com.kitex.mom.wms.service.outBill.WmsOutBillHandler;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kitex.mom.wms.service.bill.WmsOutBillAccess.Action;
import static com.kitex.mom.wms.service.bill.WmsOutBillAccess.Family;
import static com.kitex.mom.wms.service.bill.WmsOutBillAccess.Original;

/**
 * 通用及固定销售/领料入口的办理门面：按实际组织、固定业务族和仓库动作核准后派发原处理器。
 * 不修改共享处理器、MES或专用来源的准入合同；批量和ID写入以统一外层事务覆盖预检与执行。
 */
@Service
@RequiredArgsConstructor
public class WmsOutBillApplicationService {

	private final WmsOutBillAccess access;

	/**
	 * 按原查询条件读取当前入口授权仓的出库列表，固定族类型不能由外部扩大。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Page<WmsOutBillEntity> page(Family family, Page page, WmsInBillDetailQueryDTO query) {
		QueryWrapper<WmsOutBillEntity> wrapper = query(family, query, false);
		access.restrictMain(family, wrapper, Action.VIEW);
		return handler(query.getBillType()).page(page, wrapper);
	}

	/**
	 * 统计范围与通用列表授权相同，保留既有按状态汇总合同。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Map<String, Long> count(Family family, WmsInBillDetailQueryDTO query) {
		QueryWrapper<WmsOutBillEntity> wrapper = query(family, query, true);
		access.restrictMain(family, wrapper, Action.VIEW);
		return handler(query.getBillType()).getCount(wrapper);
	}

	/**
	 * 明细只使用服务端可信组织和授权仓库，不信任外部scope参数。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Page<WmsOutBillDetailEntity> pageDetails(Family family, Page page, WmsInBillDetailQueryDTO query) {
		access.restrictDetails(family, query, Action.VIEW);
		return handler(query.getBillType()).pageDetails(page, query);
	}

	/**
	 * 详情先核实际原仓查看权限，再按存储类型读取，不用默认类型绕过专用处理。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsOutBillEntity view(Family family, Long id) {
		Original row = one(family, id, Action.VIEW, false);
		WmsOutBillEntity bill = handler(row.type()).selectById(id);
		require(bill != null, "出库单已失效，请刷新后重选");
		return bill;
	}

	/**
	 * 固定族深详情保留原返回合同，并核原单类型、工厂和实际原仓查看权限。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsOutBillEntity deepView(Family family, Long id) {
		Original row = one(family, id, Action.VIEW, false);
		WmsOutBillEntity bill = handler(row.type()).getDeepById(id);
		require(bill != null, "出库单已失效，请刷新后重选");
		return bill;
	}

	/**
	 * 当前固定入口的角色动作能力；不代表库存、业务配置或工厂写入已经准入。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Map<String, List<String>> capabilities(Family family, List<String> warehouseCodes) {
		return access.capabilities(family, warehouseCodes);
	}

	/**
	 * 通知选库核实际通知inout及通用新增权限；调拨和盘点关联通知由原专用入口办理。
	 * 当前读取不生成草稿、不补写来源身份。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsOutBillEntity stockForNotice(Family family, Long id) {
		Original row = access.notice(family, id, false);
		access.requireStockSelection(family, row.warehouse());
		WmsOutBillEntity result = WmsOutNoticeFactory.getHandler(row.type()).getLiveStockByNoticeId(id);
		require(result != null, "原出库通知库存读取未完成，请刷新后重试");
		return result;
	}

	/**
	 * 当前入口新增先核目标仓add/add_review；关联通知加锁核原类型、原仓及inout，不能借查看权限创建。
	 * 正文组织由服务端覆盖，销售、领料、调拨及其他子类型继续进入原处理器和来源门禁。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean create(Family family, WmsOutBillEntity input, boolean pass) {
		require(input != null && input.getId() == null, "新增出库单不能携带既有单据ID");
		access.requireInputType(family, input);
		access.requireCreate(family, input.getWarehouseCode(), pass);
		if (input.getNoticeId() != null) {
			Original notice = access.notice(family, input.getNoticeId(), true);
			require(Objects.equals(notice.type(), input.getBillType())
							&& Objects.equals(notice.warehouse(), input.getWarehouseCode()),
					"出库类型或仓库与原通知不一致，请重新读取通知");
		}
		applyOrganization(input);
		return succeeded(pass ? handler(input.getBillType()).addAndPass(input)
				: handler(input.getBillType()).saveDeep(input));
	}

	/**
	 * 原单类型和关联通知冻结；改仓须同时具备原仓与目标仓edit，通知来源编辑限制仍由原处理器执行。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean update(Family family, WmsOutBillEntity input) {
		require(input != null, "请提交完整出库单");
		Original row = one(family, input.getId(), Action.EDIT, true);
		access.requireInputType(family, input);
		access.requireEdit(family, row, input.getBillType(), input.getNoticeId(), input.getWarehouseCode());
		applyOrganization(input);
		return succeeded(handler(row.type()).updateDeep(input));
	}

	/**
	 * 全批升序加锁并核身份、权限及可删状态后按原类型分组；任一失败整体回滚。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean remove(Family family, String[] supplied) {
		List<Original> rows = access.originals(family, access.identifiers(supplied), Action.DELETE, true);
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (Original row : rows) {
			InOutBillStateEnum state = InOutBillStateEnum.getByCode(row.state());
			require(state != null && state.isEditable(), "所选出库单存在不可删除状态，请刷新后核对");
			groups.computeIfAbsent(row.type(), ignored -> new java.util.ArrayList<>()).add(row.id().toString());
		}
		for (Map.Entry<String, List<String>> group : groups.entrySet()) {
			succeeded(handler(group.getKey()).removeDeep(group.getValue().toArray(String[]::new)));
		}
		return Boolean.TRUE;
	}

	/**
	 * 提交按原单类型和原仓commit核准，原状态与实物身份检查继续生效。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean commit(Family family, Long id) {
		return succeeded(handler(one(family, id, Action.COMMIT, true).type()).commitBill(id));
	}

	/**
	 * 审核按原单review核准并派发原类型，保留领料投递、采购退供等专用后续处理。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean pass(Family family, Long id) {
		return succeeded(handler(one(family, id, Action.REVIEW, true).type()).passBill(id));
	}

	/**
	 * 驳回只取原单ID和原因，处理器以实际原类型选择，不信任正文的业务类型。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean reject(Family family, WmsOutBillEntity input) {
		require(input != null, "请选择待审核出库单");
		Original row = one(family, input.getId(), Action.REVIEW, true);
		WmsOutBillEntity command = new WmsOutBillEntity();
		command.setId(row.id());
		command.setBillType(row.type());
		command.setRejectReason(input.getRejectReason());
		return succeeded(handler(row.type()).rejectBill(command));
	}

	/**
	 * 指定主单ID全部核验export，未选择时沿查询；授权与数据共用只读快照。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsOutBillExcelVO> exportMain(Family family, WmsInBillDetailQueryDTO query, Long[] ids) {
		access.checkExportIds(family, ids, false);
		QueryWrapper<WmsOutBillEntity> wrapper;
		if (ids != null && ids.length > 0) {
			wrapper = new QueryWrapper<>();
			wrapper.in("id", (Object[]) ids);
		} else {
			wrapper = query(family, query, false);
		}
		access.restrictMain(family, wrapper, Action.EXPORT);
		return handler(null).list(wrapper);
	}

	/**
	 * 明细选择身份整批核验export并强制覆盖组织和授权仓，空选择沿当前筛选。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsOutBillDetailExcelVO> exportDetails(Family family, WmsInBillDetailQueryDTO query,
	                                                   Long[] ids) {
		Long[] selected = ids == null || ids.length == 0 ? null : ids;
		access.checkExportIds(family, selected, true);
		access.restrictDetails(family, query, Action.EXPORT);
		return handler(query.getBillType()).listDetails(query, selected);
	}

	/**
	 * 保留既有主表/统计查询差别，授权边界由调用方追加，通知身份继续精确Long绑定。
	 */
	private QueryWrapper<WmsOutBillEntity> query(Family family, WmsInBillDetailQueryDTO query, boolean count) {
		require(query != null, "请提交出库查询条件");
		access.queryType(family, query.getBillType());
		Long noticeId = query.noticeIdValue();
		QueryWrapper<WmsOutBillEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(query.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, query.getCreateTime().get(0));
			if (query.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, query.getCreateTime().get(1));
			}
		}
		wrapper.lambda()
				.like(StrUtil.isNotBlank(query.getBillNo()), WmsOutBillEntity::getBillNo, query.getBillNo())
				.eq(noticeId != null, WmsOutBillEntity::getNoticeId, noticeId)
				.eq(StrUtil.isNotBlank(query.getWarehouseCode()), WmsOutBillEntity::getWarehouseCode,
						query.getWarehouseCode())
				.like(StrUtil.isNotBlank(query.getCreateBy()), WmsOutBillEntity::getCreateBy,
						query.getCreateBy())
				.eq(StrUtil.isNotBlank(query.getBillType()), WmsOutBillEntity::getBillType,
						query.getBillType());
		if (!count) {
			wrapper.lambda()
					.eq(StrUtil.isNotBlank(query.getState()), WmsOutBillEntity::getState, query.getState())
					.in(family == Family.PRODUCE && CollUtil.isNotEmpty(query.getStateList()),
							WmsOutBillEntity::getState,
							query.getStateList())
					.orderByAsc(WmsOutBillEntity::getState).orderByDesc(BaseCore::getCreateTime);
		}
		return wrapper;
	}

	private Original one(Family family, Long id, Action action, boolean lock) {
		return access.originals(family, Collections.singletonList(id), action, lock).get(0);
	}

	private WmsOutBillHandler<WmsOutBillEntity, WmsOutBillDetailEntity> handler(String type) {
		return WmsOutBillFactory.getHandler(type);
	}

	/**
	 * 组织只来自已核准上下文，不将请求中的租户或工厂写进主单、明细。
	 */
	private void applyOrganization(WmsOutBillEntity input) {
		input.setTenantId(TenantContextHolder.getTenantId());
		input.setFactoryCode(FactoryContextHolder.getFactoryCode());
		if (input.getDetails() != null) {
			for (WmsOutBillDetailEntity detail : input.getDetails()) {
				if (detail != null) {
					detail.setTenantId(TenantContextHolder.getTenantId());
					detail.setFactoryCode(FactoryContextHolder.getFactoryCode());
				}
			}
		}
	}

	private Boolean succeeded(Boolean result) {
		require(Boolean.TRUE.equals(result), "出库办理未确认完成，本次操作已回滚");
		return Boolean.TRUE;
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
