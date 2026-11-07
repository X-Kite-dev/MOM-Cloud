package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteMesEngineeringDecisionService;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO;
import com.kitex.mom.mes.support.MesPlanEngineeringDecisionCodec;
import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO;
import com.kitex.mom.plan.dto.PlnEngineeringResolutionDTO.Certificate;
import com.kitex.mom.plan.entity.PlnEngineeringResolutionEntity;
import com.kitex.mom.plan.mapper.PlnEngineeringPreparationMapper;
import com.kitex.mom.plan.mapper.PlnEngineeringResolutionMapper;
import com.kitex.mom.plan.support.PlnEngineeringPreparationProofCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/**
 * 用MES真实永久终态结束PLAN原来源保护。外部回读先完成，随后才进入PLAN独立事务；
 * 空回执、失败或身份漂移不释放，没有自动期限或跨域成功猜测。
 */
@Service
@RequiredArgsConstructor
public class PlnEngineeringResolutionService {

	private static final ObjectMapper JSON = new ObjectMapper();
	private final PlnEngineeringPreparationService preparations;
	private final PlnEngineeringPreparationMapper sources;
	private final PlnEngineeringResolutionMapper resolutions;
	private final RemoteMesEngineeringDecisionService mes;
	private final PlatformTransactionManager transactions;

	/**
	 * 已受理原号先竞争MES永久取消；采用已先提交时返回真实APPLIED，不能补造取消。
	 * 未受理号仍由原申请人沿原命令永久封存，不伪造一次MES采用或撤销。
	 */
	public PlnEngineeringPreparationDTO.Receipt cancel(PlnEngineeringPreparationDTO.CancelCommand command) {
		noTransaction();
		var scope = preparations.scope("write");
		require(command != null && command.original() != null && text(command.cancelReason()), "请保留完整原撤销命令");
		var current = preparations.request(command.original().requestId());
		if (current == null || current.preparedAt() == null) {
			return preparations.cancel(command);
		}
		require(PlnEngineeringPreparationProofCodec.commandCanonical(command.original())
						.equals(PlnEngineeringPreparationProofCodec.commandCanonical(current.original())),
				"撤销原准备正文与原回执不一致");
		if ("CANCELLED".equals(current.status()) && current.resolution() == null && !current.adoptionSupported()) {
			return current;
		}
		var certificate = preparations.certificate(command.original().requestId());
		var response = mes.cancel(new MesPlanEngineeringDecisionDTO.Cancel(command.original().requestId(),
				certificate.authorityFingerprint(), command.cancelReason()));
		var actual = data(response);
		return complete(scope, certificate, actual);
	}

	/**
	 * 按原PEP从MES取实际终态，不接受客户端提供的回执。MES未知时保留PLAN保护。
	 */
	public PlnEngineeringPreparationDTO.Receipt finish(String requestId) {
		noTransaction();
		var scope = preparations.scope("write");
		var current = preparations.request(requestId);
		if (current != null && "CANCELLED".equals(current.status()) && current.resolution() == null &&
				!current.adoptionSupported()) {
			return current;
		}
		var certificate = preparations.certificate(requestId);
		var actual = data(mes.request(requestId));
		return complete(scope, certificate, actual);
	}

	/**
	 * 本地确认只写不可变终结索引。锁后检查同一原准备和全量scope，不改原计划工程或排程字段。
	 */
	private PlnEngineeringPreparationDTO.Receipt complete(PlnEngineeringPreparationService.Scope scope,
	                                                      Certificate certificate,
	                                                      MesPlanEngineeringDecisionDTO.Receipt actual) {
		verify(certificate, actual);
		var template = new TransactionTemplate(transactions);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.executeWithoutResult(transaction -> {
			require(Objects.equals(resolutions.ready(), 1), "PLAN永久终态保护不完整，原来源保持保护");
			String requestId = certificate.preparation().original().requestId();
			require(Objects.equals(sources.lockRequest(scope.tenant(), scope.factory(), requestId), 1),
					"未获取原准备事务锁");
			var parent = sources.request(scope.tenant(), scope.factory(), requestId);
			require(parent != null && certificate.preparation().id().equals(parent.getId().toString())
							&& parent.getPreparedAt() != null && "PREPARED".equals(parent.getStatus())
							&& certificate.taskId().equals(parent.getTaskId().toString())
							&& parent.getObservationSnapshotHash().equals(certificate.preparation().observationSnapshotHash())
							&& PlnEngineeringPreparationProofCodec.hash(parent.getObservationSnapshot())
							.equals(parent.getObservationSnapshotHash())
							&& parent.getRequestFingerprint().equals(certificate.preparation().requestFingerprint()),
					"PLAN终结期间原准备身份或原件不一致，不能释放");
			var previous = resolutions.byPreparation(scope.tenant(), scope.factory(), parent.getId());
			if (previous != null) {
				require(previous.getAuthorityFingerprint().equals(certificate.authorityFingerprint())
								&& previous.getMesDecisionId().toString().equals(actual.id())
								&& previous.getMesDecisionFingerprint().equals(actual.receiptFingerprint())
								&& previous.getMesReceiptSnapshot().equals(MesPlanEngineeringDecisionCodec.canonical(actual)),
						"原准备已经按不同MES事实终结，请保留原号核对");
				return;
			}
			var keys = sourceKeys(parent.getObservationSnapshot());
			require(keys.size() == certificate.preparation().scopeCount()
							&& sources.countScopes(scope.tenant(), scope.factory(), parent.getId()) == keys.size()
							&& Objects.equals(sources.lockSources(scope.tenant(), scope.factory(), keys), keys.size()),
					"原准备完整来源范围不能核验，保持保护");
			var ending = new PlnEngineeringResolutionEntity();
			ending.setId(IdWorker.getId());
			ending.setTenantId(scope.tenant());
			ending.setFactoryCode(scope.factory());
			ending.setPreparationId(parent.getId());
			ending.setRequestId(requestId);
			ending.setAuthorityFingerprint(certificate.authorityFingerprint());
			ending.setDecision(actual.decision());
			ending.setMesDecisionId(Long.valueOf(actual.id()));
			ending.setMesDecisionFingerprint(actual.receiptFingerprint());
			ending.setMesReceiptSnapshot(MesPlanEngineeringDecisionCodec.canonical(actual));
			ending.setActorId(Long.valueOf(actual.actorId()));
			ending.setDecidedAt(LocalDateTime.parse(actual.decidedAt()));
			ending.setReason(actual.reason());
			ending.setCreateBy(scope.actorName());
			ending.setUpdateBy(scope.actorName());
			ending.setCreateTime(LocalDateTime.now());
			ending.setUpdateTime(ending.getCreateTime());
			ending.setDelFlag("0");
			require(resolutions.insert(ending) == 1, "PLAN未保存原MES终态，保持来源保护");
		});
		// 此时本地提交已经完成，回读不能在事务内再次通过MDM/其他服务校验造成连接池互等。
		return preparations.request(certificate.preparation().original().requestId());
	}

	private void verify(Certificate certificate, MesPlanEngineeringDecisionDTO.Receipt actual) {
		PlnEngineeringPreparationProofCodec.validate(certificate);
		MesPlanEngineeringDecisionCodec.validate(actual);
		var original = certificate.preparation();
		require(original.id().equals(actual.preparationId()) && original.tenantId().equals(actual.tenantId())
						&& original.factoryCode().equals(actual.factoryCode()) &&
						original.original().requestId().equals(actual.requestId())
						&& certificate.authorityFingerprint().equals(actual.authorityFingerprint()) &&
						certificate.taskId().equals(actual.taskId())
						&& original.original().taskCode().equals(actual.taskCode()) &&
						certificate.productCode().equals(actual.productCode())
						&& original.sourceReleaseId().equals(actual.sourceReleaseId()) &&
						original.sourceContentHash().equals(actual.sourceContentHash())
						&& original.original().targetReleaseId().equals(actual.targetReleaseId())
						&& original.original().targetContentHash().equals(actual.targetContentHash()),
				"MES实际终态与原PLAN权威凭据不同，不能释放来源");
	}

	private java.util.List<PlnEngineeringPreparationDTO.SourceScope> sourceKeys(String raw) {
		try {
			var keys = new ArrayList<PlnEngineeringPreparationDTO.SourceScope>();
			var seen = new HashSet<String>();
			JsonNode facts = JSON.readTree(raw).path("facts");
			require(facts.isArray() && facts.size() <= 30000, "原准备完整范围缺失或超限");
			for (JsonNode fact : facts) {
				String kind = fact.path("kind").asText();
				if (!java.util.Set.of("ISSUE", "BATCH", "TASK").contains(kind)) {
					continue;
				}
				String id = fact.path("id").asText();
				require(id.matches("[1-9][0-9]{0,18}") && seen.add(kind + ":" + id), "原准备来源身份重复或未知");
				keys.add(new PlnEngineeringPreparationDTO.SourceScope(kind, Long.valueOf(id)));
			}
			require(!keys.isEmpty() && keys.size() <= 15000, "原准备来源范围缺失或超限");
			return keys;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原准备来源范围无法完整解析", failure);
		}
	}

	private MesPlanEngineeringDecisionDTO.Receipt data(R<MesPlanEngineeringDecisionDTO.Receipt> response) {
		require(response != null && response.isOk() && response.getData() != null,
				"MES原号结果未知，PLAN来源继续保护，请沿原号核对");
		return response.getData();
	}

	private void noTransaction() {
		require(!TransactionSynchronizationManager.isActualTransactionActive(), "跨域永久决策不能嵌入PLAN已有事务");
	}

	private boolean text(String value) {
		return value != null && !value.isBlank() && value.length() <= 1000;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
