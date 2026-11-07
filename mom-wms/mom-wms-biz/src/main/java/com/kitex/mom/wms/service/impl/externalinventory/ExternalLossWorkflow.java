package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 外部命令唯一事务入口。先串行化原号、核读权并回原终态，首次办理后再写一次永久结果。
 */
@Service
@RequiredArgsConstructor
public class ExternalLossWorkflow {
	private final ExternalLossStore store;
	private final ExternalLossCodec codec;
	private final ExternalLossAccess access;
	private final ExternalLossBasis basis;
	private final ExternalLossMandates mandates;
	private final ExternalLossEvidence evidence;
	private final ExternalLossApprovals approvals;

	/**
	 * 在可实际访问的范围发行实名原号和分动作当前资格。
	 */
	public Context<?> context(String domain, String key, boolean mobile) {
		return access.context(domain, key, mobile);
	}

	/**
	 * 只作静态规范化与本动作实名岗位核验，原号由调用方已取得的context提供。
	 */
	@Transactional(readOnly = true)
	public Envelope prepare(String domain, Envelope e) {
		var p = codec.parse(domain, e, true);
		var i = access.identity();
		access.read(i, p.scope(), "OM1".equals(domain));
		access.job(i, p.scope(), domain, p.action());
		return p.envelope();
	}

	/**
	 * 仅查询永久原件；不按手机开关、今日货主、配置或库存推测过去。
	 */
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
	public Resolution<?> lookup(String domain, Envelope e) {
		var p = codec.parse(domain, e, false);
		access.read(access.identity(), p.scope(), "OM1".equals(domain));
		return store.find(p);
	}

	/**
	 * HTTP直接调用Spring代理开独立事务；END与execute在同一租户原号锁上仲裁。
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
	public Resolution<?> mutate(String domain, Envelope e, boolean mobile, boolean ending, MultipartFile upload) {
		var p = codec.parse(domain, e, false);
		Identity i = access.identity();
		require(i.tenant().toString().equals(p.scope().tenantId()) &&
				i.actor().toString().equals(p.scope().actorId()) &&
				i.factory().equals(p.scope().factoryCode()), "原号实名身份或组织不匹配");
		store.commandLock(p);
		access.read(i, p.scope(), "OM1".equals(domain));
		var prior = store.find(p);
		if (!"ABSENT".equals(prior.status())) {
			return prior;
		}
		store.ready();
		access.job(i, p.scope(), domain, p.action());
		Long source = null;
		if (!ending) {
			if (!"OM1".equals(domain)) {
				access.operation(p.scope().warehouseCode(), mobile);
				store.warehouseLock(p.scope());
			}
			store.intent(e.requestId(), p.action(), p.scope().actorId());
			source = switch (domain) {
				case "OM1" -> mandates.apply(p, i);
				case "OE1" -> evidence.register(p, i, basis.inspect(p.scope(), true, null).basis(), upload);
				case "OA1" -> approvals.apply(p, i);
				default -> throw new IllegalArgumentException("外部命令域无效");
			};
			store.intent("", "", "");
		}
		store.save(p, i.actorCode(), source);
		return store.find(p);
	}
}
