package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesManufacturingPieceService.*;
import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * 按实际租户工厂、精确工单和UTC半开周期读取完整制造登记总体，超过上限拒绝而非截断。
 */
@Service
@RequiredArgsConstructor
public class MesManufacturingPieceCohortService {
	private final MesManufacturingPieceCohortMapper rows;
	private final MesManufacturingPieceMapper births;
	private final MesActualFactoryAccessService access;
	private final MesEngineeringPackageService engineering;

	/**
	 * 本域核实过程中的原来源投影，不接收客户端任意回写。
	 *
	 * @param task    当前实际工厂原工单。
	 * @param context 冻结的编码上下文或工单工程范围，按所属记录解释。
	 */
	private record Source(MesTaskEntity task, Context context) {
	}

	/**
	 * 核对当前实际工厂的原工单与冻结工程，或读取并验证MES完整总体；组织、指纹、范围和原单位不一致时拒绝。
	 */
	private Source source(String code) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		require(
				code != null
						&& !code.isBlank()
						&& code.length() <= 100
						&& code.equals(code.strip()),
				"请填写精确工单编码");
		var orders = rows.order(tenant, factory, code);
		require(orders.size() == 1, "工单未唯一归属当前实际租户工厂");
		var task = orders.get(0);
		require(
				Objects.equals(task.getTenantId(), tenant)
						&& factory.equals(task.getFactoryCode())
						&& code.equals(task.getCode())
						&& task.getId() != null
						&& task.getId() > 0
						&& "0".equals(task.getDelFlag()),
				"工单实际身份不一致");
		var body = tree(engineering.readIssuedOriginal(task).source().getBundleSnapshot());
		var graph = body.path("graph").path("procedureList");
		require(
				graph.isArray()
						&& !graph.isEmpty()
						&& graph.size() <= 2000
						&& Objects.equals(
						task.getMatCode(), body.path("bom").path("productCode").asText()),
				"原冻结工艺节点或BOM产品不完整");
		var nodes = new ArrayList<Node>();
		var codes = new HashSet<String>();
		for (var n : graph) {
			String node = n.path("routeNodeCode").asText();
			require(
					node.matches("RN-[1-9][0-9]{0,18}")
							&& codes.add(node)
							&& !n.path("processCode").asText().isBlank()
							&& n.path("preRouteNodeCodes").isArray(),
					"原冻结稳定节点不唯一或缺前序依据");
			nodes.add(
					new Node(
							node,
							n.path("processCode").asText(),
							n.path("processName").isTextual()
									? n.path("processName").asText()
									: null,
							n.path("preRouteNodeCodes").isEmpty()));
		}
		String unit = body.path("bom").path("unit").asText();
		require(
				!unit.isBlank()
						&& task.getEngineeringReleaseId() != null
						&& task.getEngineeringContentHash() != null
						&& task.getEngineeringContentHash().matches("[0-9a-f]{64}"),
				"原工程身份或原数量单位不完整");
		return new Source(
				task,
				new Context(
						MesManufacturingPieceCohortDTO.SCHEMA,
						tenant.toString(),
						factory,
						task.getId().toString(),
						code,
						task.getMatCode(),
						task.getMatName(),
						task.getEngineeringReleaseId().toString(),
						task.getEngineeringContentHash(),
						unit,
						nodes));
	}

	/**
	 * 读取当前实际工厂精确工单的原已下达工程节点与产品单位；无权限、不唯一或冻结来源损坏时拒绝。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Context context(String orderCode) {
		return source(orderCode).context();
	}

	/**
	 * 校验实际组织、原工程和UTC半开周期后读取完整出生集合；超过1000件拒绝，逐件核对原件及指纹。
	 *
	 * @param input 精确工单、稳定检验节点与UTC登记周期
	 * @return 全部登记成员、集合指纹和MES观察时刻
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View inspect(Query input) {
		Query q = MesManufacturingPieceCohortDTO.normalize(input);
		var source = source(q.orderCode());
		var c = source.context();
		require(
				c.nodes().stream().anyMatch(n -> n.routeNodeCode().equals(q.inspectionNodeCode())),
				"检验节点不属于该工单原冻结工程");
		require(ready(births), "制造身份依据尚未就绪，不能将未知当空总体");
		long from = Instant.parse(q.registeredFrom()).toEpochMilli(),
				to = Instant.parse(q.registeredTo()).toEpochMilli();
		var originals =
				rows.members(
						Long.valueOf(c.tenantId()),
						c.factoryCode(),
						source.task().getId(),
						from,
						to);
		require(originals.size() <= 1000, "登记身份超过1000件，请缩小登记周期；不会截断总体计算");
		var members = new ArrayList<Member>();
		var identifiers = new HashSet<String>();
		for (var row : originals) {
			var receipt = read(row);
			var b = receipt.birth();
			long captured = Long.parseLong(b.registeredAt());
			require(
					b.tenantId().equals(c.tenantId())
							&& b.factoryCode().equals(c.factoryCode())
							&& b.orderId().equals(c.orderId())
							&& b.orderCode().equals(c.orderCode())
							&& b.engineeringHash().equals(c.engineeringHash())
							&& b.engineeringReleaseId().equals(c.engineeringReleaseId())
							&& b.quantityUnit().equals(c.quantityUnit())
							&& b.generation().context().material().equals(c.productCode())
							&& captured >= from
							&& captured < to
							&& identifiers.add(b.identifier())
							&& c.nodes().stream()
							.anyMatch(
									n ->
											n.initial()
													&& n.routeNodeCode()
													.equals(b.routeNodeCode())),
					"制造原件与精确工单、UTC周期、初始节点或工程单位不一致");
			members.add(
					new Member(
							b.identifier(),
							receipt.fingerprint(),
							b.registeredAt(),
							b.routeNodeCode(),
							b.command().requestId(),
							b.command().arrangeId()));
		}
		members.sort(Comparator.comparing(Member::identifier));
		var collection =
				new MesManufacturingPieceCohortDTO.Collection(
						MesManufacturingPieceCohortDTO.SCHEMA, c, q, members);
		return new View(collection, sha(write(collection)), Instant.now().toString());
	}
}
