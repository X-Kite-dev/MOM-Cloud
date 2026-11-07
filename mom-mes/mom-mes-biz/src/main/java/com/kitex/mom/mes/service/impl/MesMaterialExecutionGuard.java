package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.entity.MesWarehouseStockFeedJudgeEntity;
import com.kitex.mom.mes.enums.MesPickingBillStateEnum;
import com.kitex.mom.mes.mapper.MesPhysicalTransformSourceLockMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWarehouseMaterialInBillMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillMapper;
import com.kitex.mom.mes.mapper.MesWarehouseStockFeedJudgeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工单关联材料写入的根互斥及既有修订准入。先由实际组织的原单定位工单，
 * 再按原根键取try锁及冻结检查，最后锁业务行；不凭物料/批次猜归属，不授予人员权限。
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class MesMaterialExecutionGuard {

	private final MesPhysicalTransformSourceLockMapper locks;
	private final MesInspectionRevisionHold holds;
	private final MesTaskMapper tasks;
	private final MesWarehousePickingBillMapper pickings;
	private final MesWarehouseMaterialInBillMapper notices;
	private final MesWarehouseStockFeedJudgeMapper returns;

	/**
	 * 同组织同原号先串行再查一次永久回执，已有回执无需重新取得生产许可。
	 * 该锁本身不允许新写；失败立即回滚，避免跨工单导入/回调反序等待。
	 */
	public void request(String kind, String requestId) {
		scope();
		require(Set.of("WMS_ISSUE", "WMS_RETURN_RECEIPT", "MATERIAL_RECEIPT").contains(kind), "材料原号类型无效");
		code(requestId, "材料原请求号");
		String key = JSONUtil.toJsonStr(
				List.of("MES_MATERIAL_REQUEST_LOCK_V1", tenant().toString(), factory(), kind, requestId));
		require(Boolean.TRUE.equals(locks.tryTask(key)), "材料原请求正在处理，请保留原号核对");
	}

	/**
	 * 取得当前实际工单的既有根锁及修订准入；必须先于派工/单据/库存行锁。
	 * 工单SHARE重读与首次无锁查询使用不同语句，不能沿MyBatis锁前缓存推断身份稳定。
	 */
	public void task(String code) {
		scope();
		code(code, "材料实际工单号");
		var initial = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory())
				.eq(MesTaskEntity::getCode, code).last("LIMIT 2"));
		require(initial.size() == 1, "材料来源没有当前组织唯一生产工单");
		require(Boolean.TRUE.equals(locks.tryTask(MesProductionTaskMutex.key(tenant(), factory(), code))),
				"生产工单正在处理，请保留材料原号稍后核对");
		holds.requireOpen(code);
		var current = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant()).eq(MesTaskEntity::getFactoryCode, factory())
				.eq(MesTaskEntity::getCode, code).last("LIMIT 2 FOR SHARE NOWAIT"));
		require(current.size() == 1 && Objects.equals(initial.get(0).getId(), current.get(0).getId()),
				"材料来源工单身份已变化，请重新核对");
	}

	/**
	 * 实际领料归属先于领料行锁；历史无工单归属保持无根，不猜测为当前产品工单。
	 */
	public MesWarehousePickingBillEntity picking(Long id) {
		scope();
		var before = pickings.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
				.eq(MesWarehousePickingBillEntity::getTenantId, tenant())
				.eq(MesWarehousePickingBillEntity::getFactoryCode, factory())
				.eq(MesWarehousePickingBillEntity::getId, id));
		require(before != null, "当前组织未找到原生产领料单");
		if (present(before.getTaskCode())) {
			task(before.getTaskCode());
		}
		var current = pickings.selectOne(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
				.eq(MesWarehousePickingBillEntity::getTenantId, tenant())
				.eq(MesWarehousePickingBillEntity::getFactoryCode, factory())
				.eq(MesWarehousePickingBillEntity::getId, id).last("FOR UPDATE NOWAIT"));
		require(current != null && Objects.equals(before.getTaskCode(), current.getTaskCode())
				&& Objects.equals(before.getBillNo(), current.getBillNo()), "领料来源归属已变化，请重新核对");
		return current;
	}

	/**
	 * 正式非MES_PICKING通知允许billId为空；有原领料时先保护其真实工单，再锁通知。
	 */
	public MesWarehouseMaterialInBillEntity notice(Long id) {
		scope();
		var before = notices.selectOne(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
				.eq(MesWarehouseMaterialInBillEntity::getTenantId, tenant())
				.eq(MesWarehouseMaterialInBillEntity::getFactoryCode, factory())
				.eq(MesWarehouseMaterialInBillEntity::getId, id));
		require(before != null, "当前组织未找到收料通知");
		if (before.getBillId() != null) {
			var picking = picking(before.getBillId());
			require(!MesPickingBillStateEnum.CREATED.getCode().equals(picking.getStatus()), "领料单尚未正式提交");
		}
		var current = notices.selectOne(new LambdaQueryWrapper<MesWarehouseMaterialInBillEntity>()
				.eq(MesWarehouseMaterialInBillEntity::getTenantId, tenant())
				.eq(MesWarehouseMaterialInBillEntity::getFactoryCode, factory())
				.eq(MesWarehouseMaterialInBillEntity::getId, id).last("FOR UPDATE NOWAIT"));
		require(current != null && Objects.equals(before.getBillId(), current.getBillId())
				&& Objects.equals(before.getWmsOutDetailId(), current.getWmsOutDetailId())
				&& Objects.equals(before.getResourceNo(), current.getResourceNo()), "收料原单归属已变化，请重新核对");
		return current;
	}

	/**
	 * 退料非空taskCode必须沿同组织唯一原领料复核，不能重用旧LIMIT1推断。
	 * 无工单来源不补造根；有声称归属却无法核验时拒绝新回写，既有永久回执仍由入口先回读。
	 */
	public MesWarehouseStockFeedJudgeEntity returnRequest(Long id) {
		scope();
		var before = returns.selectOne(new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
				.eq(MesWarehouseStockFeedJudgeEntity::getTenantId, tenant())
				.eq(MesWarehouseStockFeedJudgeEntity::getFactoryCode, factory())
				.eq(MesWarehouseStockFeedJudgeEntity::getId, id));
		require(before != null, "当前组织未找到原生产退料申请");
		if (present(before.getTaskCode())) {
			code(before.getBillNo(), "退料原领料单号");
			var sources = pickings.selectList(new LambdaQueryWrapper<MesWarehousePickingBillEntity>()
					.eq(MesWarehousePickingBillEntity::getTenantId, tenant())
					.eq(MesWarehousePickingBillEntity::getFactoryCode, factory())
					.eq(MesWarehousePickingBillEntity::getBillNo, before.getBillNo()).last("LIMIT 2"));
			require(sources.size() == 1 && Objects.equals(before.getTaskCode(), sources.get(0).getTaskCode()),
					"退料申请工单与唯一原领料来源不符，请先核对历史归属");
			var source = picking(sources.get(0).getId());
			require(Objects.equals(before.getBillNo(), source.getBillNo()) &&
							Objects.equals(before.getTaskCode(), source.getTaskCode()),
					"退料原领料归属已变化，请重新核对");
		}
		var current = returns.selectOne(new LambdaQueryWrapper<MesWarehouseStockFeedJudgeEntity>()
				.eq(MesWarehouseStockFeedJudgeEntity::getTenantId, tenant())
				.eq(MesWarehouseStockFeedJudgeEntity::getFactoryCode, factory())
				.eq(MesWarehouseStockFeedJudgeEntity::getId, id).last("FOR UPDATE NOWAIT"));
		require(current != null && Objects.equals(before.getTaskCode(), current.getTaskCode())
				&& Objects.equals(before.getBillNo(), current.getBillNo()), "退料申请来源归属已变化，请重新核对");
		return current;
	}

	/**
	 * 只核可信上下文，不把内部系统事件改成人员代办或从payload恢复租户。
	 */
	private void scope() {
		MesQualityGateSourceEvidence.scope();
	}

	/**
	 * 当前真实认证租户。
	 */
	private Long tenant() {
		return TenantContextHolder.getTenantId();
	}

	/**
	 * 当前真实工厂上下文。
	 */
	private String factory() {
		return FactoryContextHolder.getFactoryCode();
	}

	/**
	 * 历史空归属保持未知；非空原值不裁剪后重绑。
	 */
	private boolean present(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * 材料原号及工单键遵循原100字符合同，拒绝控制字符。
	 */
	private void code(String value, String label) {
		require(present(value) && value.length() <= 100 && value.chars().noneMatch(Character::isISOControl),
				label + "缺失或格式不完整");
	}

	/**
	 * 缺归属/锁冲突均停止本次事务，不能带着部分材料事实继续写入。
	 */
	private void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
