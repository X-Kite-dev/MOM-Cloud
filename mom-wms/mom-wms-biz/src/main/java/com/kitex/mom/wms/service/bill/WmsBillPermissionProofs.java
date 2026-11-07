package com.kitex.mom.wms.service.bill;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kitex.mom.wms.service.bill.WmsBillPermissionProofClient.Item;
import com.kitex.mom.wms.service.bill.WmsBillPermissionProofClient.Proof;

/**
 * 单次HTTP请求的菜单来源证明；先批量预热，再按实际身份读取，不建立跨请求或跨账号缓存。
 * Admin不可用、身份不一致或回包缺项均拒绝当前操作，不伪装成无权限或许可。
 */
@Component
@RequiredArgsConstructor
public class WmsBillPermissionProofs {

	private static final String ATTRIBUTE = WmsBillPermissionProofs.class.getName() + ".proofs";
	private static final int MAX_BATCH = 1000;
	private final WmsBillPermissionProofClient client;

	/**
	 * 单次请求中的人员、组织及证明结果；请求结束由Web容器清理。
	 */
	private record Cache(Long tenant, Long actor, String factory, Map<Item, Boolean> values) {
	}

	/**
	 * 其他单据入口复用的证明候选，不允许调用方传入人员、租户或工厂身份。
	 *
	 * @param family        固定warehouse/sales/produce/other菜单族
	 * @param action        固定单据动作词
	 * @param warehouseCode 实际原仓或待建目标仓
	 * @param permission    当前人员持有的精确按钮码
	 */
	public record Check(String family, String action, String warehouseCode, String permission) {
	}

	/**
	 * 一次预热调用方合并的候选，当前工厂从真实上下文取得，并复用本次请求已有证明。
	 */
	public void prefetch(Collection<Check> checks) {
		Cache cache = cache();
		preload(checks.stream().map(check -> new Item(check.family(), check.action(), check.warehouseCode(),
				cache.factory(), check.permission())).toList());
	}

	/**
	 * 从本次请求预热结果读取来源证明；缺项直接失败，不以逐项远程请求掩盖预热遗漏。
	 */
	public boolean hasProof(String family, String action, String warehouseCode, String permission) {
		return allowed(new Item(family, action, warehouseCode, cache().factory(), permission));
	}

	/**
	 * 对当前请求尚未取得的候选去重并分批证明，避免逐单/逐按钮远程调用。
	 *
	 * @param supplied 仅包含当前人员持有的精确权限候选
	 */
	public void preload(Collection<Item> supplied) {
		if (supplied.isEmpty()) {
			return;
		}
		Cache cache = cache();
		List<Item> pending = supplied.stream().distinct().filter(item -> !cache.values().containsKey(item))
				.toList();
		for (int offset = 0; offset < pending.size(); offset += MAX_BATCH) {
			List<Item> batch = new ArrayList<>(
					pending.subList(offset, Math.min(offset + MAX_BATCH, pending.size())));
			if (batch.stream().anyMatch(item -> !Objects.equals(item.factoryCode(), cache.factory()))) {
				throw failure();
			}
			R<Proof> response;
			try {
				response = client.prove(batch);
			} catch (RuntimeException exception) {
				throw failure();
			}
			Proof proof = response == null ? null : response.getData();
			if (response == null || !response.isOk() || proof == null
					|| !Objects.equals(proof.tenantId(), cache.tenant())
					|| !Objects.equals(proof.userId(), cache.actor()) || proof.allowed() == null
					|| proof.allowed().size() != batch.size() ||
					proof.allowed().stream().anyMatch(Objects::isNull)) {
				throw failure();
			}
			for (int index = 0; index < batch.size(); index++) {
				cache.values().put(batch.get(index), proof.allowed().get(index));
			}
		}
	}

	/**
	 * 只读取已经批量预热的证明，不在逐行核权时偷偷发起N+1请求。
	 */
	public boolean allowed(Item item) {
		Boolean result = cache().values().get(item);
		if (result == null) {
			throw failure();
		}
		return result;
	}

	private Cache cache() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		var actor = SecurityUtils.getUser();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		if (request == null || actor == null || actor.getId() == null || actor.getId() <= 0
				|| tenant == null || tenant <= 0 || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				|| !Objects.equals(tenant, actor.getTenantId()) || factory == null || factory.isBlank()) {
			throw failure();
		}
		Object existing = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (existing instanceof Cache value && Objects.equals(value.tenant(), tenant)
				&& Objects.equals(value.actor(), actor.getId()) && Objects.equals(value.factory(), factory)) {
			return value;
		}
		Cache created = new Cache(tenant, actor.getId(), factory, new LinkedHashMap<>());
		request.setAttribute(ATTRIBUTE, created, RequestAttributes.SCOPE_REQUEST);
		return created;
	}

	private CheckedException failure() {
		return new CheckedException("出库按钮授权来源核验失败，请稍后重试");
	}
}
