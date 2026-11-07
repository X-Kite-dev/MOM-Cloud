package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.mapper.MesPickingWmsV1Mapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;

import java.time.LocalDateTime;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领料永久来源的结构门禁、登记及旧入口保护，不依赖领料CRUD以避免循环。
 */
@Component
@RequiredArgsConstructor
public class MesPickingWmsSourceGuard {
	private final MesPickingWmsV1Mapper mapper;
	private final MesTaskMapper tasks;

	/**
	 * 普通上下文可解释未安装；不可将异常当作旧来源许可。
	 */
	public boolean available() {
		try {
			return Integer.valueOf(1).equals(mapper.ready());
		} catch (RuntimeException failure) {
			return false;
		}
	}

	/**
	 * 所有新写先证明完整本域结构及在途修订保护，未就绪失败闭合。
	 */
	public void requireReady() {
		if (!available()) {
			throw new CheckedException("请先安装并核验MES领料WMS V1完整结构及修订在途守卫");
		}
	}

	/**
	 * 当前准确组织原source，不允许调用方指定组织。
	 */
	public MesPickingWmsV1Mapper.Source source(Long billId) {
		requireReady();
		return mapper.source(TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), billId);
	}

	/**
	 * 只在真实新领料INSERT事务内登记，永久来源不得收编历史领料。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void register(MesWarehousePickingBillEntity bill) {
		requireReady();
		var candidates = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, bill.getTenantId())
				.eq(MesTaskEntity::getFactoryCode, bill.getFactoryCode())
				.eq(MesTaskEntity::getCode, bill.getTaskCode()).last("LIMIT 2"));
		if (candidates.size() != 1 || bill.getCreateBy() == null || bill.getCreateBy().isBlank()) {
			throw new CheckedException("新领料原工单身份或创建账号未完整保存");
		}
		var source = new MesPickingWmsV1Mapper.Source();
		source.setTenantId(bill.getTenantId());
		source.setFactoryCode(bill.getFactoryCode());
		source.setBillId(bill.getId());
		source.setBillNo(bill.getBillNo());
		source.setTaskId(candidates.get(0).getId());
		source.setTaskCode(bill.getTaskCode());
		source.setCurrentRevision(0);
		source.setPhase("DRAFT");
		source.setCreateBy(bill.getCreateBy());
		source.setCreateTime(LocalDateTime.now());
		source.setUpdateTime(source.getCreateTime());
		if (mapper.insertSource(source) != 1) {
			throw new CheckedException("新领料V1永久来源登记失败，已回滚");
		}
	}

	/**
	 * V1单不得从旧GET写路径绕过永久命令，legacy仍沿原兼容合同。
	 */
	public void requireLegacy(Long id) {
		if (source(id) != null) {
			throw new CheckedException("本单已登记V1来源，请在领料协作面板按原命令办理");
		}
	}

	/**
	 * 未决及已受理来源不可编辑；DRAFT/CANCELLED保留原受控草稿编辑。
	 */
	public void requireEditable(Long id) {
		var source = source(id);
		if (source != null && !Set.of("DRAFT", "CANCELLED").contains(source.getPhase())) {
			throw new CheckedException("领料原命令尚未结束或已经受理，不能修改或删除原来源");
		}
	}

	/**
	 * WMS正式回调可以早于本域受理apply；只能允许正在提交的准确V1来源。
	 */
	public boolean acceptsEarlyIssue(Long id) {
		var source = source(id);
		return source != null && Set.of("PREPARED", "ACCEPTED", "CANCELLING").contains(source.getPhase());
	}
}
