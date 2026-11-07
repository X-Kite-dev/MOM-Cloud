package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mes.api.feign.RemoteMesManufacturingPieceCohortService;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO.Query;
import com.kitex.mom.qms.dto.QmsInspectionFirstPassCohortDTO;
import com.kitex.mom.qms.dto.QmsInspectionFirstPassCohortDTO.*;
import com.kitex.mom.qms.dto.QmsInspectionPieceFirstPassDTO;
import com.kitex.mom.qms.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.*;
import java.time.Instant;
import java.util.*;

/**
 * 从MES服务端权威总体核对QMS原FULL样本，返回有限覆盖指标和可下钻证据。
 */
@Service
@RequiredArgsConstructor
public class QmsInspectionFirstPassCohortService {
	private final RemoteMesManufacturingPieceCohortService mes;
	private final QmsInspectionHistorySupport access;
	private final PermissionService permissions;
	private final QmsInspectionFirstPassCohortMapper rows;
	private final QmsInspectionPieceMapper pieces;
	private final QmsInspectionPieceFirstPassService firstPass;
	private final QmsInspectionPieceService samples;

	/**
	 * 同时核对既有工序检验读取许可与当前实际工厂授权，失败时拒绝读取。
	 */
	private void authorize() {
		if (!permissions.hasPermission("procedureTask:show")) {
			throw new AccessDeniedException("没有查看工序检验的权限");
		}
		access.requireRead();
	}

	/**
	 * 核对MES上下文的真实组织、精确原工单、冻结工程、单位与稳定节点唯一性；不一致时拒绝。
	 */
	private static void contextValid(Context c, String code) {
		require(
				c != null
						&& MesManufacturingPieceCohortDTO.SCHEMA.equals(c.schemaVersion())
						&& Objects.equals(
						c.tenantId(), TenantContextHolder.getTenantId().toString())
						&& Objects.equals(c.factoryCode(), FactoryContextHolder.getFactoryCode())
						&& Objects.equals(c.orderCode(), code)
						&& c.orderId() != null
						&& c.orderId().matches("[1-9][0-9]{0,18}")
						&& c.engineeringHash() != null
						&& c.engineeringHash().matches("[0-9a-f]{64}")
						&& c.engineeringReleaseId() != null
						&& c.quantityUnit() != null
						&& !c.quantityUnit().isBlank()
						&& c.nodes() != null
						&& !c.nodes().isEmpty()
						&& c.nodes().size() <= 2000,
				"MES冻结工单、实际范围或原单位不一致");
		var nodes = new HashSet<String>();
		for (var n : c.nodes()) {
			require(
					n.routeNodeCode() != null
							&& n.routeNodeCode().matches("RN-[1-9][0-9]{0,18}")
							&& nodes.add(n.routeNodeCode())
							&& n.processCode() != null
							&& !n.processCode().isBlank(),
					"MES冻结稳定节点不唯一或不完整");
		}
	}

	/**
	 * 读取当前实际工厂精确工单的原已下达工程节点与产品单位；无权限、不唯一或冻结来源损坏时拒绝。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Context context(String orderCode) {
		authorize();
		var r = mes.context(orderCode);
		require(r != null && r.isOk() && r.getData() != null, "MES工单节点暂时无法读取");
		var c =
				QmsInspectionBasisJson.read(
						QmsInspectionBasisJson.write(r.getData()), Context.class);
		contextValid(c, orderCode);
		return c;
	}

	/**
	 * 核对当前实际工厂的原工单与冻结工程，或读取并验证MES完整总体；组织、指纹、范围和原单位不一致时拒绝。
	 */
	private Manufacturing source(Query q) {
		var r = mes.inspect(q);
		require(r != null && r.isOk() && r.getData() != null, "MES登记身份全集暂时无法核验，不能按空总体计算");
		var raw = r.getData();
		require(
				raw.collection() != null
						&& Objects.equals(
						raw.collectionHash(),
						QmsInspectionBasisJson.sha(
								QmsInspectionBasisJson.write(raw.collection()))),
				"MES登记总体原件指纹不符");
		var m = QmsInspectionBasisJson.read(QmsInspectionBasisJson.write(raw), Manufacturing.class);
		var c = m.collection();
		contextValid(c.context(), q.orderCode());
		require(
				MesManufacturingPieceCohortDTO.SCHEMA.equals(c.schemaVersion())
						&& Objects.equals(
						c.query(),
						new Scope(
								q.orderCode(),
								q.inspectionNodeCode(),
								q.registeredFrom(),
								q.registeredTo()))
						&& c.context().nodes().stream()
						.anyMatch(n -> n.routeNodeCode().equals(q.inspectionNodeCode()))
						&& c.members().size() <= 1000,
				"MES登记总体与精确检验节点、UTC周期或容量不一致");
		Instant.parse(m.observedAt());
		long from = Instant.parse(q.registeredFrom()).toEpochMilli(),
				to = Instant.parse(q.registeredTo()).toEpochMilli();
		var identifiers = new HashSet<String>();
		for (var member : c.members()) {
			require(
					member.identifier() != null
							&& member.identifier().length() <= 100
							&& member.identifier().matches("[A-Za-z0-9][A-Za-z0-9._-]*")
							&& identifiers.add(member.identifier())
							&& member.manufacturingFingerprint() != null
							&& member.manufacturingFingerprint().matches("[0-9a-f]{64}")
							&& member.registeredAt() != null
							&& member.registeredAt().matches("[1-9][0-9]{0,18}")
							&& Long.parseLong(member.registeredAt()) >= from
							&& Long.parseLong(member.registeredAt()) < to
							&& c.context().nodes().stream()
							.anyMatch(
									n ->
											n.initial()
													&& n.routeNodeCode()
													.equals(
															member
																	.birthInitialNodeCode())),
					"MES总体含重复、越界或未证明初始出生的身份");
		}
		return m;
	}

	/**
	 * 从完整服务端成员集合取得同组织样本候选；未安装保留未知，部分安装或超过10000条拒绝，不截断统计。
	 */
	private List<QmsInspectionPieceMapper.Row> candidates(Manufacturing m) {
		if (m.collection().members().isEmpty()) {
			return List.of();
		}
		int installed = pieces.installedTables();
		if (installed == 0) {
			return null;
		}
		require(
				installed == 3 && Objects.equals(pieces.schemaVersion(), 1),
				"QMS逐件来源结构未完整就绪，不能计算局部总体");
		var ids = m.collection().members().stream().map(Member::identifier).toList();
		var found =
				rows.candidates(
						TenantContextHolder.getTenantId(),
						FactoryContextHolder.getFactoryCode(),
						ids);
		require(found.size() <= 10000, "关联逐件检验超过10000条，请缩小登记周期，禁止截断统计");
		var keys = new HashSet<Long>();
		for (var row : found) {
			require(
					Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
							&& Objects.equals(
							row.getFactoryCode(), FactoryContextHolder.getFactoryCode())
							&& ids.contains(row.getIdentifier())
							&& row.getId() != null
							&& row.getTaskId() != null
							&& keys.add(row.getId()),
					"逐件检验候选与实际工厂、登记总体或唯一身份不一致");
		}
		return found;
	}

	/**
	 * 本域核实过程中的原来源投影，不接收客户端任意回写。
	 *
	 * @param row   当前实际组织的原持久化记录。
	 * @param view  经过实际组织、原FULL样本和原首轮来源核对的资格投影；未核实为null。
	 * @param issue 核实失败原因代码，可空。
	 */
	private record Assessed(
			QmsInspectionPieceMapper.Row row,
			QmsInspectionPieceFirstPassDTO.View view,
			String issue) {
	}

	/**
	 * 按既有授权入口核对一条原FULL样本及首轮来源；无法核实的原件返回未知原因，不改成合格。
	 */
	private Assessed assess(QmsInspectionPieceMapper.Row row) {
		try {
			return new Assessed(
					row,
					firstPass.inspectAuthorized(row.getTaskId().toString(), row.getId().toString()),
					null);
		} catch (RuntimeException failure) {
			return new Assessed(row, null, "ORIGINAL_SAMPLE_EVIDENCE_UNVERIFIED");
		}
	}

	/**
	 * 核对单件资格与MES总体成员的制造指纹、服务端登记时间、原工程和原单位是否完全一致。
	 */
	private boolean matching(
			QmsInspectionPieceFirstPassDTO.View v, Member member, Manufacturing source) {
		var e = v.originalInspection();
		var c = source.collection().context();
		return v.firstPassEligible()
				&& e != null
				&& Objects.equals(e.manufacturingFingerprint(), member.manufacturingFingerprint())
				&& Objects.equals(e.manufacturingRegisteredAt(), member.registeredAt())
				&& Objects.equals(e.engineeringHash(), c.engineeringHash())
				&& Objects.equals(v.quantityUnit(), c.quantityUnit())
				&& "1".equals(v.eligibleQuantity())
				&& Objects.equals(
				v.firstPassQualifiedQuantity(), v.measuredQualified() ? "1" : "0");
	}

	/**
	 * 以MES服务端完整总体核对实际QMS原FULL样本；总体损坏或超限拒绝，单条来源无法核实则保留未知，不并入已核实首轮分母。
	 *
	 * @param input 精确工单、稳定检验节点与UTC半开登记周期
	 * @return 互斥状态、覆盖率和已核实首轮合格率，零分母保持null
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public View inspect(Query input) {
		authorize();
		var q = MesManufacturingPieceCohortDTO.normalize(input);
		var source = source(q);
		var candidates = candidates(source);
		var grouped = new HashMap<String, List<Assessed>>();
		if (candidates != null) {
			for (var row : candidates) {
				grouped.computeIfAbsent(row.getIdentifier(), ignored -> new ArrayList<>())
						.add(assess(row));
			}
		}
		var items = new ArrayList<Item>();
		long pass = 0, fail = 0, pending = 0, unknown = 0, excluded = 0, excludedSamples = 0;
		for (var member : source.collection().members()) {
			var inspections = new ArrayList<Inspection>();
			var gaps = new ArrayList<String>();
			var good = new ArrayList<QmsInspectionPieceFirstPassDTO.View>();
			boolean uncertain = candidates == null;
			long reinspections = 0;
			for (var a : grouped.getOrDefault(member.identifier(), List.of())) {
				var v = a.view();
				String issue = a.issue();
				String qualification = v == null ? "UNVERIFIED" : v.qualification();
				boolean relevant =
						v == null
								|| v.routeNodeCode() == null
								|| q.inspectionNodeCode().equals(v.routeNodeCode());
				if (v != null && !relevant) {
					issue = "OTHER_INSPECTION_NODE";
				} else {
					if (v == null || v.routeNodeCode() == null) {
						uncertain = true;
						if (issue == null) {
							issue = "INSPECTION_NODE_UNVERIFIED";
						}
					} else {
						if (v.firstPassEligible() && matching(v, member, source)) {
							good.add(v);
						} else {
							if (v != null && "REINSPECTION".equals(qualification)) {
								reinspections++;
								excludedSamples++;
							} else {
								uncertain = true;
								if (issue == null) {
									issue = "ORIGINAL_ASSIGNMENT_UNVERIFIED";
								}
							}
						}
					}
				}
				inspections.add(
						new Inspection(
								a.row().getTaskId().toString(),
								a.row().getId().toString(),
								qualification,
								v == null ? null : v.routeNodeCode(),
								issue));
			}
			String state;
			if (good.size() == 1) {
				if (good.get(0).measuredQualified()) {
					state = "PASS";
					pass++;
				} else {
					state = "FAIL";
					fail++;
				}
				if (uncertain) {
					gaps.add("ADDITIONAL_UNVERIFIED_INSPECTION");
				}
			} else {
				if (uncertain || good.size() > 1) {
					state = "UNKNOWN";
					unknown++;
					gaps.add(
							candidates == null
									? "QMS_PIECE_SOURCE_UNAVAILABLE"
									: good.size() > 1
									? "DUPLICATE_ORIGINAL_ASSIGNMENT"
									: "ORIGINAL_SAMPLE_EVIDENCE_UNVERIFIED");
				} else {
					if (reinspections > 0) {
						state = "EXCLUDED_REINSPECTION";
						excluded++;
					} else {
						state = "PENDING";
						pending++;
					}
				}
			}
			items.add(new Item(member, state, inspections, gaps));
		}
		long registered = source.collection().members().size(), complete = pass + fail;
		require(complete + pending + unknown + excluded == registered, "登记总体统计分类不守恒");
		var gaps = new ArrayList<String>();
		gaps.add("INDEPENDENT_MES_QMS_OBSERVATIONS");
		if (complete < registered) {
			gaps.add("INCOMPLETE_VERIFIED_COVERAGE");
		}
		return new View(
				QmsInspectionFirstPassCohortDTO.SCHEMA,
				source,
				Instant.now().toString(),
				false,
				new Counts(
						Long.toString(registered),
						Long.toString(complete),
						Long.toString(pass),
						Long.toString(fail),
						Long.toString(pending),
						Long.toString(unknown),
						Long.toString(excluded),
						Long.toString(excludedSamples),
						ratio(complete, registered),
						ratio(pass, complete)),
				items,
				gaps);
	}

	/**
	 * 重新取得MES完整总体并比对集合指纹，只下钻其中成员的原FULL样本；集合变化、身份越界或证据超限时拒绝，不截断来源。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Detail detail(Query input, String hash, String identifier) {
		authorize();
		var q = MesManufacturingPieceCohortDTO.normalize(input);
		var source = source(q);
		require(Objects.equals(source.collectionHash(), hash), "MES登记总体已变化，请刷新统计后下钻");
		var member =
				source.collection().members().stream()
						.filter(m -> Objects.equals(identifier, m.identifier()))
						.findFirst()
						.orElseThrow(() -> new IllegalArgumentException("指定身份不在服务端登记总体内"));
		var candidates = candidates(source);
		var proofs = new ArrayList<PieceProof>();
		require(
				candidates == null
						|| candidates.stream()
						.filter(r -> identifier.equals(r.getIdentifier()))
						.count()
						<= 200,
				"单件关联检验超过200条，请从原检验任务逐项核对，不能截断证据");
		if (candidates != null) {
			for (var row : candidates) {
				if (identifier.equals(row.getIdentifier())) {
					var a = assess(row);
					var actual =
							a.view() == null
									? null
									: samples.checked(row.getTaskId(), row.getId()).piece();
					proofs.add(
							new PieceProof(
									row.getTaskId().toString(),
									row.getId().toString(),
									a.view(),
									actual,
									a.issue()));
				}
			}
		}
		return new Detail(
				QmsInspectionFirstPassCohortDTO.SCHEMA,
				hash,
				member,
				q.inspectionNodeCode(),
				source.observedAt(),
				Instant.now().toString(),
				proofs,
				candidates == null ? List.of("QMS_PIECE_SOURCE_UNAVAILABLE") : List.of());
	}

	/**
	 * 按已核实登记身份计数计算十进制比例，保留十位小数并去尾零；分母0返回null。
	 */
	private static String ratio(long numerator, long denominator) {
		return denominator == 0
				? null
				: BigDecimal.valueOf(numerator)
				.divide(BigDecimal.valueOf(denominator), 10, RoundingMode.HALF_UP)
				.stripTrailingZeros()
				.toPlainString();
	}
}
