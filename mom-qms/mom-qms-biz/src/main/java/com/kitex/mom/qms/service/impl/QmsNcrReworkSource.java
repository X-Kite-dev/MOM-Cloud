package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.dto.MesInventoryQualityOrigin;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import com.kitex.mom.wms.dto.WmsSpecialReturnQualityDTO;
import lombok.RequiredArgsConstructor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

import static com.kitex.mom.qms.service.impl.QmsNcrReworkRepository.require;

/**
 * NCR原案、MRB实名发布证据及实际工厂准入；不以账号文本推断决策人ID。
 */
@Component
@RequiredArgsConstructor
public class QmsNcrReworkSource {
	private final QmsBadInfoMapper headers;
	private final QmsBadInfoDetailMapper details;
	private final QmsMrbProposalMapper proposals;
	private final QmsMrbOperationMapper operations;
	private final RemoteMdmFactoryAccessService factories;
	private final QmsNcrReworkRepository ledger;
	private final SqlSessionTemplate sqlSession;

	/**
	 * 本次请求实名组织上下文；每个入口重新检查当前权限，不持久化令牌或角色。
	 *
	 * @param tenant  实际租户编号
	 * @param factory 实际工厂编码
	 * @param actor   当前实名人员快照
	 */
	public record Scope(long tenant, String factory, Actor actor) {
	}

	/**
	 * 同组织明细、主案、已发布提案和精确首HOLD/parent来源。
	 *
	 * @param detail   原分类明细
	 * @param header   原NCR主案
	 * @param proposal 已发布MRB；无证据历史可空
	 * @param key      精确原件键；缺历史证据可空
	 */
	public record Case(QmsBadInfoDetailEntity detail, QmsBadInfoEntity header, QmsMrbProposal proposal, Key key) {
	}

	/**
	 * 检查当前真实岗位及MDM实际工厂准入；拒绝内部跳过和无法核验的身份。
	 */
	public Scope access(String permission, boolean write) {
		var user = SecurityUtils.getUser();
		String factory = FactoryContextHolder.getFactoryCode();
		Long tenant = TenantContextHolder.getTenantId();
		require(user != null && user.getId() != null && user.getId() > 0 && tenant != null && tenant > 0
						&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
						&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && factory != null && !factory.isBlank(),
				"返工须实名登录并选择实际租户和工厂");
		require(user.getUsername() != null && !user.getUsername().isBlank() && user.getUsername().length() <= 100
						&& user.getName() != null && !user.getName().isBlank() && user.getName().length() <= 200,
				"当前实名账号/姓名不完整或超长，请先治理用户资料");
		require(new PermissionService().hasPermission(permission), "当前用户缺少返工业务岗位权限");
		var response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(factory)
				.setRequiredPermission(write ? "write" : "read"));
		require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
				"实际工厂授权不足或MDM不可核验");
		ledger.ready();
		return new Scope(tenant, factory, new Actor(user.getId().toString(), user.getUsername(), user.getName()));
	}

	/**
	 * 所有写入口遵循detail→header→proposal锁序；查询清理MyBatis本地缓存避免回读旧版本。
	 */
	public Case load(long id, Scope scope, boolean lock, boolean eligible) {
		sqlSession.clearCache();
		var dq = new LambdaQueryWrapper<QmsBadInfoDetailEntity>().eq(QmsBadInfoDetailEntity::getId, id)
				.eq(QmsBadInfoDetailEntity::getTenantId, scope.tenant())
				.eq(QmsBadInfoDetailEntity::getFactoryCode, scope.factory());
		if (lock) {
			dq.last("FOR UPDATE");
		}
		var detail = details.selectOne(dq);
		require(detail != null, "当前实际工厂NCR分类不存在");
		var hq = new LambdaQueryWrapper<QmsBadInfoEntity>().eq(QmsBadInfoEntity::getId, detail.getBadId())
				.eq(QmsBadInfoEntity::getTenantId, scope.tenant())
				.eq(QmsBadInfoEntity::getFactoryCode, scope.factory());
		if (lock) {
			hq.last("FOR UPDATE");
		}
		var header = headers.selectOne(hq);
		require(header != null && !MesInventoryQualityOrigin.isMesResource(header.getResourceType())
				&& Objects.equals(header.getResourceType(), detail.getResourceType()), "不是同组织的仓储NCR来源");
		if (eligible) {
			require("WAIT_VERIFY".equals(detail.getStatus()) && "REWORK".equals(detail.getHandleResult())
					&& "SUCCESS".equals(detail.getWmsSyncStatus()) && "SUCCESS".equals(header.getHoldSyncStatus())
					&& !"CLOSED".equals(header.getStatus()), "仅当前已成功REWORK且待独立复核明细可办理；历史闭案只读");
		}
		QmsMrbProposal proposal = null;
		if (detail.getMrbProposalId() != null) {
			var pq = new LambdaQueryWrapper<QmsMrbProposal>().eq(QmsMrbProposal::getId, detail.getMrbProposalId())
					.eq(QmsMrbProposal::getTenantId, scope.tenant()).eq(QmsMrbProposal::getFactoryCode, scope.factory())
					.eq(QmsMrbProposal::getBadId, header.getId()).eq(QmsMrbProposal::getDetailId, detail.getId());
			if (lock) {
				pq.last("FOR UPDATE");
			}
			proposal = proposals.selectOne(pq);
		}
		// 历史/原号读取先返回受权主明细，不能因当前已换非REWORK决策而拒绝原永久事实。
		if (!eligible) {
			return new Case(detail, header, proposal, null);
		}
		require(proposal != null && "APPLIED".equals(proposal.getStatus()) && proposal.getExecutorId() != null
						&& proposal.getExecutorId() > 0 && proposal.getExecutionTime() != null,
				"缺少实名MRB发布证据，请人工治理原案");
		var applied = operations.selectList(new LambdaQueryWrapper<QmsMrbOperation>()
				.eq(QmsMrbOperation::getTenantId, scope.tenant()).eq(QmsMrbOperation::getFactoryCode, scope.factory())
				.eq(QmsMrbOperation::getProposalId, proposal.getId()).eq(QmsMrbOperation::getDetailId, id)
				.eq(QmsMrbOperation::getOperationType, "APPLY"));
		require(applied.size() == 1 && Objects.equals(applied.get(0).getOperatorId(), proposal.getExecutorId())
				&& Objects.equals(applied.get(0).getRequestId(), proposal.getExecutionRequestId())
				&& Objects.equals(applied.get(0).getFingerprint(), proposal.getExecutionFingerprint())
				&& "APPLIED".equals(applied.get(0).getAfterStatus()), "MRB提案与永久APPLY人员/内容证据不一致");
		var disposition = ledger.decode(proposal.getCommandJson(), com.kitex.mom.qms.dto.req.QmsBadHandleReq.class);
		require("REWORK".equals(disposition.getHandleResult()) &&
				Objects.equals(disposition.getDetailId(), detail.getId())
				&& header.getHoldOperationId() != null && detail.getWmsOperationId() != null
				&& header.getHoldNo() != null && !header.getHoldNo().isBlank(), "缺少真实REWORK决策或首隔离来源");
		if (eligible) {
			require(Objects.equals(disposition.getHandleRemark(), detail.getHandleRemark())
							&& Objects.equals(disposition.getHandleFile(), detail.getHandleFile())
							&& Objects.equals(disposition.getExpectedCompletionTime(), detail.getExpectedCompletionTime()),
					"当前分类处置与已发布MRB原文不一致");
		}
		WmsSpecialReturnQualityDTO.Binding binding = null;
		if ("WMS_SPECIAL_RETURN".equals(header.getResourceType())) {
			binding = new WmsSpecialReturnQualityDTO.Binding(ledger.decode(header.getWmsSpecialReturnTargetJson(),
					WmsSpecialReturnQualityDTO.Target.class), header.getHoldOperationId().toString(), null);
		}
		require(detail.getQuantityUnit() == null || detail.getQuantityUnit().isBlank()
				|| header.getQuantityUnit() == null || header.getQuantityUnit().isBlank()
				|| detail.getQuantityUnit().equals(header.getQuantityUnit()), "主案与分类的原显式单位不一致");
		String unit = detail.getQuantityUnit();
		if (unit == null || unit.isBlank()) {
			unit = header.getQuantityUnit();
		}
		return new Case(detail, header, proposal,
				new Key(Long.toString(scope.tenant()), scope.factory(), header.getId().toString(),
						detail.getId().toString(), detail.getDetailNo(), proposal.getId().toString(),
						header.getHoldOperationId().toString(),
						detail.getWmsOperationId().toString(), header.getHoldNo(), quantity(detail.getJudgeNum()), unit,
						binding));
	}

	/**
	 * 输入Key仅inspect允许单位空；规范输出必须保持全部来源并冻结实际基本单位。
	 */
	public void sourceMatches(Key key, Source source) {
		require(source != null && source.version() == 1 && source.key() != null && source.key().unit() != null
				&& !source.key().unit().isBlank() && source.warehouseCode() != null && !source.warehouseCode().isBlank()
				&& source.pieces() != null && !source.pieces().isEmpty(), "WMS未返回完整实际原件");
		var k = source.key();
		var frozen = new Key(key.tenantId(), key.factoryCode(), key.ncrId(), key.detailId(), key.detailNo(),
				key.proposalId(),
				key.rootHoldOperationId(), key.parentOperationId(), key.holdNo(), key.quantity(), k.unit(),
				key.binding());
		require(frozen.equals(k) && (key.unit() == null || key.unit().isBlank() || key.unit().equals(k.unit())),
				"WMS原件与当前NCR版本、数量或原显式单位不一致");
	}

	/**
	 * 历史只读来源使用该版本真实发布者，不为查询猜造人员。
	 */
	public String decider(Source source) {
		var key = source.key();
		var row = proposals.selectOne(new LambdaQueryWrapper<QmsMrbProposal>()
				.eq(QmsMrbProposal::getTenantId, id(key.tenantId()))
				.eq(QmsMrbProposal::getFactoryCode, key.factoryCode())
				.eq(QmsMrbProposal::getId, id(key.proposalId())).eq(QmsMrbProposal::getDetailId, id(key.detailId()))
				.eq(QmsMrbProposal::getBadId, id(key.ncrId())));
		require(row != null && row.getExecutorId() != null && "APPLIED".equals(row.getStatus()),
				"历史原MRB实名发布证据缺失");
		return row.getExecutorId().toString();
	}

	public static long id(String text) {
		require(text != null && text.matches("[1-9][0-9]{0,18}"), "须使用有效的永久编号文本");
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			throw new com.kitex.common.core.exception.CheckedException("永久编号超出范围");
		}
	}

	public static String request(String value) {
		require(value != null && value.matches("[A-Za-z0-9_-]{16,64}"), "永久请求号须为16至64位安全随机字符");
		return value;
	}

	public static String text(String value) {
		require(value != null && !value.trim().isEmpty() && value.trim().length() <= 2000,
				"实际措施、凭证或独立依据须为1至2000字");
		return value.trim();
	}

	public static String quantity(BigDecimal value) {
		require(value != null && value.signum() > 0 && value.scale() <= 8 && value.precision() - value.scale() <= 16,
				"实际数量必须为有效基本单位正数量，最多8位小数");
		return value.stripTrailingZeros().toPlainString();
	}
}
