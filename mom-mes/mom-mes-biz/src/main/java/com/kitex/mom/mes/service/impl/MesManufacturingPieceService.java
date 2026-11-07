package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesManufacturingPieceDTO;
import com.kitex.mom.mes.dto.MesManufacturingPieceDTO.*;
import com.kitex.mom.mes.dto.MesWorkpieceTimeDTO;
import com.kitex.mom.mes.mapper.MesManufacturingPieceMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Mints a new recorded identity; it never adopts a previously observed code.
 */
@Service
@RequiredArgsConstructor
public class MesManufacturingPieceService {
	static final ObjectMapper JSON =
			new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
	private final MesManufacturingPieceMapper rows;
	private final MesActualFactoryAccessService access;
	private final MesExecutionTaskLock locks;
	private final MesEngineeringPackageService engineering;
	private final MesWorkpieceTimeService time;
	private final com.kitex.mom.mes.mapper.MesWorkpieceTimeMapper timeRows;
	private final com.kitex.mom.mes.service.MesIdentifierMappingService mappings;
	private final com.kitex.mom.mdm.feign.RemoteMaterialService materials;
	private final com.kitex.mom.mdm.feign.RemoteCustomerPartService parts;

	/**
	 * 在实际人员、工厂与原工单锁内校验声明、初始节点和永久容量，按产品模板铸码并同事务执行原START；历史来源、规则冲突或任一步失败均拒绝并回滚。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Receipt create(Command command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		Long actor = SecurityUtils.getUser().getId();
		require(
				command != null
						&& command.requestId() != null
						&& command.requestId().matches("[A-Za-z0-9_-]{16,80}")
						&& command.arrangeId() != null
						&& command.arrangeId().matches("[1-9][0-9]{0,18}")
						&& command.newPhysicalPieceDeclared(),
				"请明确声明新制造实物并保留合法原请求");
		require(ready(rows), "制造出生结构尚未安装");
		rows.lock("MANUFACTURING_PIECE:" + tenant + ":" + factory + ":" + command.requestId());
		var old = rows.request(tenant, factory, command.requestId());
		if (old != null) {
			var r = read(old);
			require(
					r.birth().command().equals(command)
							&& r.birth().actorId().equals(actor.toString()),
					"制造原号属于其他声明或操作者");
			return r;
		}
		String startRequest =
				"MP_START:"
						+ sha(tenant + ":" + factory + ":" + actor + ":" + command.requestId())
						.substring(0, 32);
		// Match the original request -> order lock sequence before looking at live dispatch/product
		// configuration.
		timeRows.lockRequest(tenant + ":" + factory + ":" + startRequest);
		var arrange = locks.arrange(Long.valueOf(command.arrangeId()));
		require(
				Objects.equals(arrange.getTenantId(), tenant)
						&& factory.equals(arrange.getFactoryCode())
						&& "PRODUCT".equals(arrange.getStatus())
						&& "ONE_CODE".equals(arrange.getReportMode())
						&& "NODE_EXECUTION_V1".equals(arrange.getRouteExecutionSchemaVersion())
						&& "".equals(arrange.getPredecessorNodeCodes()),
				"新制造实物只允许原单件模式、真实开工的冻结起始节点");
		var tasks = rows.tasks(tenant, factory, arrange.getTaskCode());
		require(tasks.size() == 1, "制造工单归属不唯一");
		var task = tasks.get(0);
		require(
				!"REWORK".equals(task.getResourceType())
						&& task.getReworkRequestId() == null
						&& task.getReworkMaterialPlanId() == null,
				"返工来源不能声明原始制造出生");
		var frozen = engineering.readIssuedOriginal(task);
		var body = tree(frozen.source().getBundleSnapshot());
		var nodes = body.path("graph").path("procedureList");
		int found = 0;
		for (var node : nodes) {
			if (Objects.equals(
					arrange.getSourceRouteNodeCode(), node.path("routeNodeCode").asText())) {
				found++;
				require(
						Objects.equals(arrange.getRouteCode(), node.path("processCode").asText()),
						"起始节点主工序与原工程冲突");
				require(
						node.path("preRouteNodeCodes").isArray()
								&& node.path("preRouteNodeCodes").isEmpty(),
						"原工程节点存在前序，禁止重建制造出生");
			}
		}
		require(
				found == 1
						&& task.getMatCode().equals(body.path("bom").path("productCode").asText()),
				"原工程起点或BOM产品不一致");
		String unit = body.path("bom").path("unit").asText();
		require(!unit.isBlank() && unit.length() <= 40, "新制造出生必须留存原数量单位");
		long orderBorn = rows.orderCount(tenant, factory, task.getId()),
				dispatchBorn = rows.arrangeCount(tenant, factory, arrange.getId());
		require(
				rows.unregisteredHistory(tenant, factory, task.getCode()) == 0,
				"原工单存在未登记制造身份的计时或报工历史，禁止猜测重复额度");
		require(
				(orderBorn > 0 || zero(task.getCompleteNum()))
						&& (dispatchBorn > 0 || zero(arrange.getCompleteNum())),
				"旧聚合完成量缺少逐件来源，禁止接管制造额度");
		require(
				capacity(task.getNoticeNum(), orderBorn)
						&& capacity(arrange.getNoticeNum(), dispatchBorn),
				"新制造实物超过原工单或派工容量，禁止重复出生");
		var materialResponse = materials.getDetails(task.getMatCode());
		require(
				materialResponse != null
						&& materialResponse.isOk()
						&& materialResponse.getData() != null,
				"物料制造编码模板未取得明确结果");
		var material = materialResponse.getData();
		require(
				Objects.equals(material.getTenantId(), tenant)
						&& factory.equals(material.getFactoryCode())
						&& task.getMatCode().equals(material.getMaterialCode())
						&& Boolean.TRUE.equals(material.getStatus()),
				"制造编码模板不属于实际工厂有效产品");
		var generation =
				new Generation(
						material.getSerialCodeTemplate(),
						new com.kitex.mom.common.identifier.IdentifierCodeTemplate.Context(
								tenant.toString(),
								factory,
								task.getMatCode(),
								material.getMaterialType(),
								material.getMaterialAttribute(),
								material.getGroupCode()),
						Long.toString(rows.nextSequence()),
						java.time.LocalDateTime.now().toString());
		String identifier = render(generation);
		require(
				rows.piece(tenant, factory, identifier) == null
						&& rows.existingSessions(tenant, factory, identifier) == 0
						&& rows.existingReports(tenant, factory, identifier) == 0,
				"生成的制造标识已有原件或历史会话，禁止接管");
		var report = new com.kitex.mom.mes.dto.MesTaskReportVO();
		report.setTransferCode(identifier);
		mappings.resolve(arrange, task, com.kitex.mom.mes.enums.ReportModeEnum.ONE_CODE, report);
		require(
				identifier.equals(report.getTransferCode())
						&& report.getIdentifierMappingId() == null
						&& "CANONICAL".equals(report.getIdentifierType()),
				"制造标识未通过原规范码规则，禁止用别名重解释");
		var ruleResponse = parts.getPartCodeByMatCode(task.getMatCode());
		require(
				ruleResponse != null
						&& ruleResponse.isOk()
						&& ruleResponse.getData() != null
						&& ruleResponse.getData().stream()
						.anyMatch(
								r ->
										r.getRegular() != null
												&& !r.getRegular().isBlank()
												&& identifier.matches(r.getRegular())),
				"生成标识不符合当前产品条码规则，请核对物料单件模板和原过站规则");
		var start =
				time.execute(
						new MesWorkpieceTimeDTO.Command()
								.setRequestId(startRequest)
								.setArrangeId(command.arrangeId())
								.setAction("START")
								.setIdentifierType("SN")
								.setIdentifier(identifier));
		var birth =
				new Birth(
						MesManufacturingPieceDTO.SCHEMA,
						tenant.toString(),
						factory,
						actor.toString(),
						command,
						identifier,
						task.getId().toString(),
						task.getCode(),
						arrange.getProduceTaskCode(),
						arrange.getSourceRouteNodeCode(),
						task.getEngineeringReleaseId().toString(),
						task.getEngineeringContentHash(),
						unit,
						"OPERATOR_DECLARED_NEW_PIECE",
						generation,
						Long.toString(System.currentTimeMillis()),
						new Start(
								start.sessionId(),
								start.requestId(),
								start.capturedAt().toString()));
		var row = new MesManufacturingPieceMapper.Row();
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setActorId(actor);
		row.setArrangeId(arrange.getId());
		row.setTaskId(task.getId());
		row.setRequestId(command.requestId());
		row.setIdentifier(identifier);
		row.setPayload(write(birth));
		row.setFingerprint(sha(row.getPayload()));
		row.setSequenceNo(Long.valueOf(generation.sequence()));
		require(rows.insertBirth(row) == 1, "制造出生原件保存失败，原START全部回滚");
		return read(row);
	}

	/**
	 * 按实际组织和原请求锁读取永久制造声明，只允许原操作者和原派工核对回执；未查到保持ABSENT。
	 *
	 * @param requestId 原制造声明请求号
	 * @param arrangeId 原派工ID字符串
	 * @return ABSENT或带不可变原件的APPLIED结果
	 */
	@Transactional(readOnly = true)
	public Resolution inspect(String requestId, String arrangeId) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		require(
				requestId != null
						&& requestId.matches("[A-Za-z0-9_-]{16,80}")
						&& arrangeId != null
						&& arrangeId.matches("[1-9][0-9]{0,18}"),
				"请提供原制造请求和派工");
		require(ready(rows), "制造出生结构尚未安装");
		rows.lock("MANUFACTURING_PIECE:" + tenant + ":" + factory + ":" + requestId);
		var row = rows.request(tenant, factory, requestId);
		if (row == null) {
			return new Resolution("ABSENT", null);
		}
		var r = read(row);
		require(
				Objects.equals(r.birth().actorId(), SecurityUtils.getUser().getId().toString())
						&& Objects.equals(r.birth().command().arrangeId(), arrangeId),
				"当前实际身份不存在此原制造声明");
		return new Resolution("APPLIED", r);
	}

	/**
	 * 只有明确原数量为0才返回true，null不推定为0。
	 */
	private boolean zero(BigDecimal value) {
		return value != null && value.signum() == 0;
	}

	/**
	 * 校验原下达额度减永久登记身份后至少还有一件；完成和重放不重复扣减额度。
	 */
	private boolean capacity(BigDecimal issued, long reserved) {
		return issued != null
				&& issued.subtract(BigDecimal.valueOf(reserved)).compareTo(BigDecimal.ONE) >= 0;
	}

	/**
	 * 仅按原模板、冻结编码上下文与保留序号重放生成规范码；模板或序号不兼容时拒绝。
	 */
	static String render(Generation generation) {
		require(
				generation != null
						&& generation.sequence() != null
						&& generation.sequence().matches("[1-9][0-9]{0,18}"),
				"制造原件缺少编码生成依据");
		return com.kitex.mom.common.identifier.IdentifierCodeTemplate.render(
				generation.template(),
				generation.context(),
				java.time.LocalDateTime.parse(generation.generatedAt()),
				Long.parseLong(generation.sequence()));
	}

	/**
	 * 核对制造出生及首轮归属结构是否完整就绪；全未安装返回false，部分安装或守卫不就绪时拒绝。
	 */
	static boolean ready(MesManufacturingPieceMapper rows) {
		int count = rows.installed();
		if (count == 0) {
			return false;
		}
		require(count == 2 && Boolean.TRUE.equals(rows.ready()), "制造出生和首次检验结构未完整就绪");
		return true;
	}

	/**
	 * 读取并核对原件规范内容；制造原件还必须与组织、索引、模板和SHA-256一致，损坏时拒绝。
	 */
	static Receipt read(MesManufacturingPieceMapper.Row row) {
		require(
				row != null
						&& row.getPayload() != null
						&& row.getPayload().length() <= 65536
						&& Objects.equals(sha(row.getPayload()), row.getFingerprint()),
				"制造出生原文指纹损坏");
		var birth = read(row.getPayload(), Birth.class);
		require(
				write(birth).equals(row.getPayload())
						&& MesManufacturingPieceDTO.SCHEMA.equals(birth.schemaVersion())
						&& Objects.equals(birth.tenantId(), String.valueOf(row.getTenantId()))
						&& Objects.equals(birth.factoryCode(), row.getFactoryCode())
						&& Objects.equals(birth.identifier(), row.getIdentifier())
						&& Objects.equals(birth.actorId(), String.valueOf(row.getActorId()))
						&& Objects.equals(birth.orderId(), String.valueOf(row.getTaskId()))
						&& Objects.equals(
						birth.command().arrangeId(), String.valueOf(row.getArrangeId()))
						&& Objects.equals(birth.command().requestId(), row.getRequestId())
						&& birth.command().newPhysicalPieceDeclared()
						&& birth.registeredAt() != null
						&& birth.registeredAt().matches("[1-9][0-9]{0,18}")
						&& Long.parseLong(birth.registeredAt()) > 0
						&& Objects.equals(render(birth.generation()), birth.identifier())
						&& Objects.equals(
						row.getSequenceNo(), Long.valueOf(birth.generation().sequence()))
						&& Objects.equals(birth.generation().context().tenant(), birth.tenantId())
						&& Objects.equals(
						birth.generation().context().factory(), birth.factoryCode())
						&& "OPERATOR_DECLARED_NEW_PIECE".equals(birth.identityBasis()),
				"制造出生原文、实际组织或索引不一致");
		return new Receipt(birth, row.getFingerprint());
	}

	/**
	 * 按原ObjectMapper规范序列化原件，编码失败抛出异常，不改写协议版本。
	 */
	static String write(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalArgumentException("制造原件无法编码", e);
		}
	}

	/**
	 * 按既有ObjectMapper解析原文并拒绝重复JSON键；本方法只负责反序列化，业务指纹与索引由调用方核对。
	 */
	static <T> T read(String raw, Class<T> type) {
		try {
			return JSON.readValue(raw, type);
		} catch (Exception e) {
			throw new IllegalArgumentException("制造原件无法解析", e);
		}
	}

	/**
	 * 将原冻结工程原文解析为JSON树，格式损坏时拒绝。
	 */
	static JsonNode tree(String raw) {
		return read(raw, JsonNode.class);
	}

	/**
	 * 按UTF-8计算原文SHA-256，算法不可用时抛出异常。
	 */
	static String sha(String raw) {
		try {
			return HexFormat.of()
					.formatHex(
							MessageDigest.getInstance("SHA-256")
									.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
