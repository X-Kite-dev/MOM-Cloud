package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesManualTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskCommandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 普通父工单详情与管理命令共用的实际组织、根互斥、修订准入及独立草稿作废边界。
 * 不依赖工单业务Service，避免手工编辑与普通命令相互调用形成循环依赖。
 */
@Component
@RequiredArgsConstructor
public class MesTaskCommandGuard {

	private final MesActualFactoryAccessService access;
	private final MesProductionTaskMutex mutex;
	private final MesInspectionRevisionHold hold;
	private final MesTaskCommandMapper mapper;
	private final MesManualTaskMapper manual;

	/**
	 * 详情核对只使用当前实名读取工厂、租户和准确ID，不把列表对象当成授权。
	 */
	public MesTaskEntity read(Long id) {
		require(id != null && id > 0, "生产工单ID无效");
		String factory = access.require("read");
		MesTaskEntity task = mapper.observe(TenantContextHolder.getTenantId(), factory, id);
		require(task != null, "当前租户工厂未找到生产工单");
		return task;
	}

	/**
	 * 按原始工单编码读取当前实名租户和实际工厂内唯一的未删除父工单。
	 * 编码不修剪、不补默认；查询最多两条，重复编码不能任取首条作为详情依据。
	 *
	 * @param code 客户端传入的准确工单编码，必须非空白
	 * @return 当前实际组织内具有有效原ID的唯一父工单
	 * @throws CheckedException 编码无效、实际组织无读取权限、未找到、重复或返回归属无法核验
	 */
	public MesTaskEntity readByCode(String code) {
		require(code != null && !code.isBlank(), "生产工单编码不能为空");
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		List<MesTaskEntity> tasks = mapper.observeByCode(tenant, factory, code);
		require(tasks != null, "生产工单查询结果无法核验，请重试");
		require(!tasks.isEmpty(), "当前租户工厂未找到生产工单");
		require(tasks.size() == 1, "当前租户工厂存在重复工单编码，请按原工单ID核对");
		MesTaskEntity task = tasks.get(0);
		require(task != null && task.getId() != null && task.getId() > 0
				&& Objects.equals(task.getTenantId(), tenant)
				&& Objects.equals(task.getFactoryCode(), factory)
				&& Objects.equals(task.getCode(), code), "生产工单身份或归属无法核验，请核对原单");
		return task;
	}

	/**
	 * 在调用方READ_COMMITTED事务中先无锁定位真实根，取同根互斥和hold，再锁父行重核。
	 * 客户端code不参与选锁；归属变化只拒绝，不在持行锁后换取另一个根锁。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public MesTaskEntity lock(Long id) {
		require(id != null && id > 0, "生产工单ID无效");
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		MesTaskEntity before = mapper.observe(tenant, factory, id);
		require(before != null, "当前租户工厂未找到生产工单");
		mutex.lock(before.getCode());
		hold.requireOpen(before.getCode());
		MesTaskEntity current = mapper.lock(tenant, factory, id);
		require(current != null && Objects.equals(before.getId(), current.getId())
						&& Objects.equals(before.getCode(), current.getCode())
						&& Objects.equals(before.getTenantId(), current.getTenantId())
						&& Objects.equals(before.getFactoryCode(), current.getFactoryCode()),
				"生产工单归属已变化，请核对原单");
		return current;
	}

	/**
	 * 编辑和首次下达前核查全部子历史；不能通过逻辑删除旧工序获得第二次下达资格。
	 */
	public void requireNoChildren(MesTaskEntity task) {
		List<String> facts = mapper.childFacts(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(facts != null && facts.isEmpty(), "工单已有关联工序/派工历史，不能编辑草稿或重复下达");
	}

	/**
	 * 只允许正式独立MOM手工草稿作废；原受理回执仍保留，不代替上游撤回或现场终止。
	 * 必须传入本事务已经通过lock取得的父行，关联事实为空也不代替数量和来源校验。
	 */
	public void requireIndependentDraft(MesTaskEntity task) {
		require(MesManualTaskService.owns(task) && "CREATE".equals(task.getStatus()),
				"仅未下达的独立手工草稿可直接作废；计划或返工请从原来源办理");
		require("MOM".equals(task.getSourceSystem()) && "MOM_MRP".equals(task.getMrpAuthority())
						&& "MOM_ORDER_ONLY".equals(task.getProductionOrderSyncMode())
						&& Objects.equals(task.getCode(), task.getProductionOrder()),
				"手工草稿的独立MOM来源不完整，请核查原建单依据");
		require(task.getStartTime() == null && task.getFinishTime() == null
						&& zero(task.getCompleteNum()) && zero(task.getInStockNum())
						&& zero(task.getUnInStockNum()) && zero(task.getUnPassNum())
						&& zero(task.getPlannedNum()) && zero(task.getAlreadyNoticeNum()),
				"工单已有执行数量、排产或时间事实，或数量依据缺失，不能直接作废");
		require(noUpstream(task), "工单存在计划、APS、ERP或返工来源，须从原业务办理撤回");

		String request = task.getSourceRequestId().substring(MesManualTaskService.PREFIX.length());
		var receipt = manual.receipt(task.getTenantId(), task.getFactoryCode(), request);
		require(receipt != null && "ACCEPTED".equals(receipt.getState())
						&& Objects.equals(receipt.getTaskId(), task.getId())
						&& Objects.equals(receipt.getTaskCode(), task.getCode())
						&& Objects.equals(receipt.getCommandFingerprint(), task.getSourceRequestFingerprint())
						&& Objects.equals(receipt.getCommandFingerprint(), fingerprint(receipt.getCommandPayload())),
				"原手工建单受理回执缺失或与工单不一致，不能直接作废");
		List<String> facts = mapper.draftFacts(task.getTenantId(), task.getFactoryCode(), task.getId(), task.getCode());
		require(facts != null && facts.isEmpty(),
				"工单已有关联事实，不能直接作废：" + (facts == null ? "查询结果未知" : String.join("、", facts)));
	}

	/**
	 * 独立草稿不得夹带任何上游分配身份或冻结来源，空白字符串沿原可空字段合同处理。
	 */
	private boolean noUpstream(MesTaskEntity task) {
		return blank(task.getPlanNo()) && blank(task.getSourcePlanNo()) && blank(task.getSourcePlanVersion())
				&& blank(task.getResourceId()) && blank(task.getErpProductionOrderNo())
				&& task.getFormalPlanContractId() == null && blank(task.getFormalPlanContractNo())
				&& task.getFormalPlanContractVersion() == null && blank(task.getFormalPlanContractFingerprint())
				&& blank(task.getFormalPlanAllocationSnapshot()) && task.getFormalPlanAllocationVersion() == null
				&& task.getApsTaskId() == null && task.getApsBatchId() == null && blank(task.getApsBatchNo())
				&& blank(task.getApsBatchType()) && blank(task.getApsBatchFingerprint())
				&& blank(task.getApsOperationSnapshot()) && blank(task.getMaterialReservationSnapshot())
				&& blank(task.getReworkRequestId()) && blank(task.getSourceTaskCode())
				&& blank(task.getSourceProduceTaskCode()) && blank(task.getSourceInspectionType())
				&& task.getSourceInspectionTaskId() == null && task.getSourceHandleRecordId() == null
				&& task.getReworkMaterialPlanId() == null && blank(task.getReworkProcessCode())
				&& blank(task.getReworkRouteNodeCode()) && blank(task.getReworkTimingPolicySnapshot())
				&& blank(task.getReworkEngineeringMode()) && task.getReworkEngineeringOverrideId() == null
				&& blank(task.getReworkEngineeringOverrideHash()) && blank(task.getReworkEngineeringScopeSnapshot())
				&& blank(task.getReworkEngineeringScopeFingerprint()) && blank(task.getReworkIdentifierScopeSnapshot())
				&& blank(task.getReworkIdentifierScopeFingerprint());
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private boolean zero(BigDecimal value) {
		return value != null && value.signum() == 0;
	}

	/**
	 * 原建单正文的SHA只用于核对已受理来源，不生成或修改任何永久回执。
	 */
	private String fingerprint(String raw) {
		require(raw != null, "原手工建单正文缺失");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException failure) {
			throw new IllegalStateException("无法核对手工建单指纹", failure);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
