package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryAccess.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;
import static com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec.id;
import static com.kitex.mom.wms.service.impl.externalinventory.ExternalLossStore.str;

/**
 * 不可变企业原件与单次申请绑定。未绑定件只有本人可见，已绑定件限本人或实仓审核岗位。
 */
@Service
@RequiredArgsConstructor
public class ExternalLossEvidence {
	private final ExternalLossStore store;
	private final ExternalLossAccess access;
	private final ExternalLossBasis basis;
	private static final String META = """
			select e.id,e.request_id,e.tenant_id,e.factory_code,e.warehouse_code,e.plan_id,e.plan_detail_id,e.recount_id,e.basis_version,
			       e.group_key,e.mandate_id,e.mandate_version,e.owner_type,e.mdm_owner_id,e.owner_code,e.source_business_no,
			       e.mat_code,e.mat_unit,e.loss_quantity,e.confirmation_no,e.confirmation_date,e.file_name,e.mime,e.byte_length,e.sha256,
			       e.registered_by,e.registered_code,e.registered_at,b.approval_id
			from mom_wms.wms_external_loss_evidence e left join mom_wms.wms_external_loss_binding b on b.evidence_id=e.id and b.tenant_id=e.tenant_id
			where e.tenant_id=? and e.factory_code=? and e.warehouse_code=? and e.plan_id=? and e.plan_detail_id=?
			""";

	/**
	 * 按完整明细范围读取一份原件元数据和永久绑定，可在写事务内锁住该证件。
	 */
	Evidence load(DetailScope s, long key, boolean lock) {
		return row(store.one(META + " and e.id=?" + (lock ? " for share of e nowait" : ""), Long.valueOf(s.tenantId()),
				s.factoryCode(),
				s.warehouseCode(), id(s.planId()), id(s.planDetailId()), key), s);
	}

	/**
	 * 未绑定证件仅本人可见；已绑定件按实仓岗位可读，只有仍匹配当下依据的本人未绑定原件可选择。
	 */
	List<Evidence> list(Identity i, DetailScope s) {
		access.visible(i, s);
		boolean review = access.canHandle(s.warehouseCode());
		var rows = store.jdbc.queryForList(
				META + " and (e.registered_by=? or (? and b.approval_id is not null)) order by e.id desc",
				i.tenant(), i.factory(), s.warehouseCode(), id(s.planId()), id(s.planDetailId()), i.actor(), review);
		if (rows.stream().anyMatch(r -> r.get("approval_id") == null)) {
			// 旧证件可查但只能选择与明细当前复盘、授权版本匹配者；最终提交再次锁内核验。
			Basis now;
			try {
				now = basis.inspect(s, false, null).basis();
			} catch (CheckedException blocked) {
				now = null;
			}
			var current = now;
			return rows.stream().map(r -> row(r, s)).map(e -> {
				boolean selectable = e.selectable() && current != null;
				if (selectable) {
					try {
						check(current, e.registration());
					} catch (CheckedException stale) {
						selectable = false;
					}
				}
				return new Evidence(e.id(), e.requestId(), e.registration(), e.registeredBy(), e.registeredCode(),
						e.registeredAt(), e.approvalId(), selectable);
			}).toList();
		}
		return rows.stream().map(r -> row(r, s)).toList();
	}

	private Evidence row(Map<String, Object> r, DetailScope s) {
		var v = new Registration(str(r, "recount_id"), str(r, "basis_version"), str(r, "group_key"),
				str(r, "mandate_id"), str(r, "mandate_version"),
				str(r, "owner_type"), str(r, "mdm_owner_id"), str(r, "owner_code"), str(r, "source_business_no"),
				str(r, "mat_code"), str(r, "mat_unit"),
				ExternalLossBasis.decimal(new BigDecimal(str(r, "loss_quantity"))), str(r, "confirmation_no"),
				str(r, "confirmation_date"), str(r, "file_name"),
				str(r, "mime"), ((Number) r.get("byte_length")).intValue(), str(r, "sha256"));
		return new Evidence(str(r, "id"), str(r, "request_id"), v, str(r, "registered_by"), str(r, "registered_code"),
				str(r, "registered_at"), str(r, "approval_id"),
				r.get("approval_id") == null && s.actorId().equals(str(r, "registered_by")));
	}

	/**
	 * 核原件绑定的全部差额、仓内主体、授权版本与来源，禁止改用另一明细或新复盘。
	 */
	static void check(Basis b, Registration r) {
		require(b.recountId().equals(r.recountId()) && b.basisVersion().equals(r.basisVersion()) &&
				b.groupKey().equals(r.groupKey()) &&
				b.mandate().id().equals(r.mandateId()) && b.mandate().version().equals(r.mandateVersion()) &&
				b.owner().id().equals(r.mdmOwnerId()) &&
				b.owner().code().equals(r.ownerCode()) && b.owner().ownerType().equals(r.ownerType()) &&
				b.sourceBusinessNo().equals(r.sourceBusinessNo()) &&
				b.matCode().equals(r.matCode()) && b.matUnit().equals(r.matUnit()) &&
				b.lossQuantity().equals(r.lossQuantity()), "货主文件已偏离当前复盘、授权或差额");
	}

	/**
	 * 企业经办人首次登记须核当前复盘、文号和上传原始字节，回执引用真实新增主键。
	 */
	long register(ExternalLossCodec.Parsed p, Identity i, Basis b, MultipartFile file) {
		var r = ((EvidenceCommand) p.typed()).registration();
		check(b, r);
		require(file != null && file.getSize() == r.byteLength() && r.fileName().equals(file.getOriginalFilename()) &&
						r.mime().equals(file.getContentType()),
				"上传文件与首存原文的长度、文件名或媒体类型不符");
		byte[] bytes;
		try (var in = file.getInputStream()) {
			bytes = in.readNBytes(4194305);
		} catch (java.io.IOException ex) {
			throw new CheckedException("读取上传原件失败");
		}
		verify(bytes, r);
		var s = p.scope();
		return store.jdbc.queryForObject("""
						insert into mom_wms.wms_external_loss_evidence
						(tenant_id,factory_code,warehouse_code,plan_id,plan_detail_id,request_id,recount_id,basis_version,group_key,mandate_id,
						mandate_version,owner_type,mdm_owner_id,owner_code,source_business_no,mat_code,mat_unit,loss_quantity,confirmation_no,
						confirmation_date,file_name,mime,byte_length,sha256,file_bytes,registered_by,registered_code,registered_at)
						values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::date,?,?,?,?,?,?,?,clock_timestamp()) returning id
						""", Long.class, i.tenant(), i.factory(), s.warehouseCode(), id(s.planId()), id(s.planDetailId()),
				p.envelope().requestId(), id(r.recountId()),
				r.basisVersion(), r.groupKey(), id(r.mandateId()), Integer.valueOf(r.mandateVersion()), r.ownerType(),
				id(r.mdmOwnerId()), r.ownerCode(),
				r.sourceBusinessNo(), r.matCode(), r.matUnit(), new BigDecimal(r.lossQuantity()), r.confirmationNo(),
				r.confirmationDate(), r.fileName(), r.mime(),
				r.byteLength(), r.sha256(), bytes, i.actor(), i.actorCode());
	}

	ResponseEntity<byte[]> download(String evidenceId) {
		var i = access.identity();
		var origin = store.one(
				"select plan_detail_id from mom_wms.wms_external_loss_evidence where tenant_id=? and factory_code=? and id=?",
				i.tenant(), i.factory(), id(evidenceId));
		var s = access.scope(i, str(origin, "plan_detail_id"));
		access.visible(i, s);
		var evidence = load(s, id(evidenceId), false);
		require(i.actor().toString().equals(evidence.registeredBy()) ||
						(evidence.approvalId() != null && access.canHandle(s.warehouseCode())),
				"无权取得该货主原件");
		var bytes = store.jdbc.queryForObject(
				"select file_bytes from mom_wms.wms_external_loss_evidence where tenant_id=? and factory_code=? and warehouse_code=? and plan_detail_id=? and id=?",
				byte[].class, i.tenant(), i.factory(), s.warehouseCode(), id(s.planDetailId()), id(evidenceId));
		var r = evidence.registration();
		verify(bytes, r);
		require(r.fileName() != null && r.fileName().length() <= 200 && !r.fileName().isBlank() &&
				!r.fileName().contains("/") && !r.fileName().contains("\\")
				&& r.fileName().codePoints().noneMatch(Character::isISOControl), "存档文件名无效");
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.mime())).contentLength(bytes.length)
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(r.fileName(), StandardCharsets.UTF_8).build()
								.toString())
				.header("X-Content-Type-Options", "nosniff").header("X-Content-SHA256", r.sha256()).body(bytes);
	}

	/**
	 * 登记与读取共同校验原始文件字节及白名单文件头，任一不一致整体拒绝。
	 */
	private static void verify(byte[] bytes, Registration r) {
		require(bytes != null && bytes.length == r.byteLength() && bytes.length > 0 && bytes.length <= 4194304,
				"原件长度无效");
		byte[] signature = switch (r.mime()) {
			case "application/pdf" -> "%PDF-".getBytes(StandardCharsets.US_ASCII);
			case "image/jpeg" -> HexFormat.of().parseHex("ffd8ff");
			case "image/png" -> HexFormat.of().parseHex("89504e470d0a1a0a");
			default -> throw new CheckedException("原件格式无效");
		};
		require(bytes.length >= signature.length &&
				Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length), "原件文件头与声明格式不符");
		try {
			require(r.sha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
					"原件SHA256不符");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
