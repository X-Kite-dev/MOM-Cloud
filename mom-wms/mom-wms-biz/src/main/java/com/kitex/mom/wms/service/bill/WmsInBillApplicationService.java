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
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.service.inBill.WmsInBillFactory;
import com.kitex.mom.wms.service.inBill.WmsInBillHandler;
import com.kitex.mom.wms.service.inNotice.WmsInNoticeFactory;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.kitex.mom.wms.service.bill.WmsInBillAccess.Action;
import static com.kitex.mom.wms.service.bill.WmsInBillAccess.Family;
import static com.kitex.mom.wms.service.bill.WmsInBillAccess.Original;

/**
 * 通用及固定采购/成品/生产退料/销售退货入口的办理门面：按实际组织、固定业务族和仓库动作核准后派发原处理器。
 * 不修改共享处理器、MES或专用来源的准入合同；批量和ID写入以统一外层事务覆盖预检与执行。
 */
@Service
@RequiredArgsConstructor
public class WmsInBillApplicationService {

	private final WmsInBillAccess access;

	/**
	 * 按原查询条件读取当前入口授权仓的入库列表，固定族类型不能由外部扩大。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Page<WmsInBillEntity> page(Family family, Page page, WmsInBillDetailQueryDTO query) {
		QueryWrapper<WmsInBillEntity> wrapper = query(family, query, false);
		access.restrictMain(family, wrapper, Action.VIEW);
		return handler(query.getBillType()).page(page, wrapper);
	}

	/**
	 * 统计范围与通用列表授权相同，保留既有按状态汇总合同。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Map<String, Long> count(Family family, WmsInBillDetailQueryDTO query) {
		QueryWrapper<WmsInBillEntity> wrapper = query(family, query, true);
		access.restrictMain(family, wrapper, Action.VIEW);
		return handler(query.getBillType()).getCount(wrapper);
	}

	/**
	 * 明细只使用服务端可信组织和授权仓库，不信任外部scope参数。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Page<WmsInBillDetailEntity> pageDetails(Family family, Page page, WmsInBillDetailQueryDTO query) {
		access.restrictDetails(family, query, Action.VIEW);
		return handler(query.getBillType()).pageDetails(page, query);
	}

	/**
	 * 详情先核实际原仓查看权限，再按存储类型读取，不用默认类型绕过专用处理。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsInBillEntity view(Family family, Long id) {
		Original row = one(family, id, Action.VIEW, false);
		WmsInBillEntity bill = handler(row.type()).selectById(id);
		require(bill != null, "入库单已失效，请刷新后重选");
		return bill;
	}

	/**
	 * 建单来源核实际通知inout及当前入口新增或直接审核权限；其它来源仍走原专用入口。
	 * 当前读取不生成草稿、不占用数量、不补写来源身份。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public WmsInNoticeEntity notice(Family family, Long id) {
		Original row = access.notice(family, id, false);
		access.requireSourceSelection(family, row.warehouse());
		WmsInNoticeEntity result = WmsInNoticeFactory.getHandler(row.type()).selectById(id);
		require(result != null, "原入库通知读取未完成，请刷新后重试");
		return result;
	}

	/**
	 * 当前入口新增先核目标仓add/add_review；关联通知加锁核原类型、原仓及inout，不能借查看权限创建。
	 * 正文组织由服务端覆盖，采购、成品、退料及其他已支持子类型继续进入原处理器和来源门禁。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean create(Family family, WmsInBillEntity input, boolean pass) {
		require(input != null && input.getId() == null, "新增入库单不能携带既有单据ID");
		access.requireInputType(family, input);
		access.requireCreate(family, input.getWarehouseCode(), pass);
		if (input.getNoticeId() != null) {
			Original notice = access.notice(family, input.getNoticeId(), true);
			require(Objects.equals(notice.type(), input.getBillType())
							&& Objects.equals(notice.warehouse(), input.getWarehouseCode()),
					"入库类型或仓库与原通知不一致，请重新读取通知");
		}
		applyOrganization(input);
		return succeeded(pass ? handler(input.getBillType()).addAndPass(input)
				: handler(input.getBillType()).saveDeep(input));
	}

	/**
	 * 原单类型和关联通知冻结；改仓须同时具备原仓与目标仓edit，通知来源编辑限制仍由原处理器执行。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean update(Family family, WmsInBillEntity input) {
		require(input != null, "请提交完整入库单");
		Original row = one(family, input.getId(), Action.EDIT, true);
		if (input.getBillType() == null) {
			input.setBillType(row.type());
		}
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
			require(state != null && state.isEditable(), "所选入库单存在不可删除状态，请刷新后核对");
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
	 * 审核按原单review核准并派发原类型，保留采购收料回写、退料实收及MES回写Outbox等原业务处理。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean pass(Family family, Long id) {
		return succeeded(handler(one(family, id, Action.REVIEW, true).type()).passBill(id));
	}

	/**
	 * 驳回只取原单ID和原因，处理器以实际原类型选择，不信任正文的业务类型。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public Boolean reject(Family family, WmsInBillEntity input) {
		require(input != null, "请选择待审核入库单");
		Original row = one(family, input.getId(), Action.REVIEW, true);
		WmsInBillEntity command = new WmsInBillEntity();
		command.setId(row.id());
		command.setBillType(row.type());
		command.setRejectReason(input.getRejectReason());
		return succeeded(handler(row.type()).rejectBill(command));
	}

	/**
	 * 指定主单ID全部核验export，未选择时沿查询；授权与数据共用只读快照。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<WmsInBillExcelVO> exportMain(Family family, WmsInBillDetailQueryDTO query, Long[] ids) {
		access.checkExportIds(family, ids, false);
		QueryWrapper<WmsInBillEntity> wrapper;
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
	public List<WmsInBillDetailExcelVO> exportDetails(Family family, WmsInBillDetailQueryDTO query,
	                                                  Long[] ids) {
		Long[] selected = ids == null || ids.length == 0 ? null : ids;
		access.checkExportIds(family, selected, true);
		access.restrictDetails(family, query, Action.EXPORT);
		return handler(query.getBillType()).listDetails(query, selected);
	}

	/**
	 * 保留既有主表/统计查询差别，授权边界由调用方追加，通知身份继续精确Long绑定。
	 */
	private QueryWrapper<WmsInBillEntity> query(Family family, WmsInBillDetailQueryDTO query, boolean count) {
		require(query != null, "请提交入库查询条件");
		access.queryType(family, query.getBillType());
		Long noticeId = query.noticeIdValue();
		QueryWrapper<WmsInBillEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(query.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, query.getCreateTime().get(0));
			if (query.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, query.getCreateTime().get(1));
			}
		}
		wrapper.lambda()
				.like(StrUtil.isNotBlank(query.getBillNo()), WmsInBillEntity::getBillNo, query.getBillNo())
				.eq(noticeId != null, WmsInBillEntity::getNoticeId, noticeId)
				.eq(StrUtil.isNotBlank(query.getWarehouseCode()), WmsInBillEntity::getWarehouseCode,
						query.getWarehouseCode())
				.like(StrUtil.isNotBlank(query.getCreateBy()), WmsInBillEntity::getCreateBy,
						query.getCreateBy())
				.eq(StrUtil.isNotBlank(query.getBillType()), WmsInBillEntity::getBillType,
						query.getBillType());
		if (!count) {
			wrapper.lambda()
					.eq(StrUtil.isNotBlank(query.getState()), WmsInBillEntity::getState, query.getState())
					.orderByAsc(WmsInBillEntity::getState).orderByDesc(BaseCore::getCreateTime);
		}
		return wrapper;
	}

	private Original one(Family family, Long id, Action action, boolean lock) {
		return access.originals(family, Collections.singletonList(id), action, lock).get(0);
	}

	private WmsInBillHandler<WmsInBillEntity, WmsInBillDetailEntity> handler(String type) {
		return WmsInBillFactory.getHandler(type);
	}

	/**
	 * 组织只来自已核准上下文，不将请求中的租户或工厂写进主单、明细。
	 * 明细仓库代码统一沿已核准的主单目标仓，避免改仓后遗留旧仓或跨仓伪造；原位置仍由处理器按目标仓重验。
	 * 仓名仅随头部显示元数据同步，不作为主数据身份或授权依据。
	 */
	private void applyOrganization(WmsInBillEntity input) {
		input.setTenantId(TenantContextHolder.getTenantId());
		input.setFactoryCode(FactoryContextHolder.getFactoryCode());
		if (input.getDetails() != null) {
			for (WmsInBillDetailEntity detail : input.getDetails()) {
				if (detail != null) {
					detail.setTenantId(TenantContextHolder.getTenantId());
					detail.setFactoryCode(FactoryContextHolder.getFactoryCode());
					detail.setWarehouseCode(input.getWarehouseCode());
					detail.setWarehouseName(input.getWarehouseName());
				}
			}
		}
	}

	private Boolean succeeded(Boolean result) {
		require(Boolean.TRUE.equals(result), "入库办理未确认完成，本次操作已回滚");
		return Boolean.TRUE;
	}

	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
