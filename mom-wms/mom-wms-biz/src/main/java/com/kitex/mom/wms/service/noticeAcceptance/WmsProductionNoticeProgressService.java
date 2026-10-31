package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.Receipt;
import com.kitex.mom.wms.dto.WmsProductionNoticeProgressDTO;
import com.kitex.mom.wms.repository.WmsProductionNoticeAcceptanceRepository;
import com.kitex.mom.wms.repository.WmsProductionNoticeProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeCanonical.*;

/**
 * 受理凭据、当前分配和出库单状态分别呈现，不据此推断已扣库存或MES收料。
 */
@Service
@RequiredArgsConstructor
public class WmsProductionNoticeProgressService {
    private final WmsProductionNoticeAccess access;
    private final PermissionService permissions;
    private final WmsProductionNoticeProgressRepository repository;
    private final WmsProductionNoticeAcceptanceRepository acceptances;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsProductionNoticeProgressDTO progress(String warehouse, Long noticeId) {
        require(noticeId != null && noticeId > 0, "请选择需要查看的领料通知单");
        var scope = access.scope(warehouse, false, false);
        var notice = repository.notice(scope, warehouse, noticeId);
        require(notice != null, "当前仓库未找到该领料通知单，请刷新列表");
        require(Set.of("0", "1", "2", "3").contains(text(notice.state())), "通知状态异常，请核对原单据");
        var details = repository.details(scope, warehouse, noticeId);
        require(!details.isEmpty() && details.size() <= 1000, "通知明细为空或超过单次核对范围，请联系管理员核对原单据");
        var seen = new HashSet<Long>();
        for (var line : details) {
            require(line.id() != null && line.id() > 0 && seen.add(line.id()) && !text(line.material()).isEmpty() &&
                            !text(line.unit()).isEmpty() && line.planned() != null && line.planned().signum() > 0 &&
                            line.allocated() != null && line.allocated().signum() >= 0 && line.remaining() != null &&
                            line.remaining().signum() >= 0 &&
                            line.planned().compareTo(line.allocated().add(line.remaining())) == 0,
                    "通知明细身份或分配数量不一致，请核对原单据");
        }
        boolean source = "mes_picking".equals(notice.documentTable());
        require(source || text(notice.documentTable()).isEmpty() && notice.documentId() == null,
                "该通知的业务来源需要核对，请查看原单据");
        Integer revision = source ? (notice.revision() == null ? 1 : notice.revision()) : null;
        Receipt proof = null;
        String acceptanceStatus = source ? "LEGACY_REVIEW_REQUIRED" : "WAREHOUSE_CREATED";
        var sourceLines = new HashMap<Long, Long>();
        if (source) {
            require(notice.documentId() != null && notice.documentId() > 0 && revision > 0,
                    "通知来源身份不完整，请核对原单据");
            var accepted = acceptances.acceptance(scope, notice.documentId(), revision, warehouse);
            if (accepted != null) {
                proof = read(accepted.receiptPayload(), Receipt.class);
                require(proof != null && proof.contractVersion() == 1 && "ACCEPT".equals(proof.action()) &&
                                "ACCEPTED".equals(proof.status()) && Objects.equals(proof.tenantId(), scope.tenant()) &&
                                scope.factory().equals(proof.factoryCode()) && warehouse.equals(proof.warehouseCode()) &&
                                "mes_picking".equals(proof.documentTable()) &&
                                Objects.equals(proof.documentId(), notice.documentId()) &&
                                proof.sourceRevision() == revision && noticeId.equals(proof.noticeId()) &&
                                noticeId.equals(accepted.noticeId()) && proof.lines() != null &&
                                proof.lines().size() == details.size() &&
                                !text(proof.originalRequestId()).isEmpty() && proof.acceptedTime() != null &&
                                !text(proof.acceptedBy()).isEmpty(),
                        "受理凭据与当前通知身份不一致，请保留原记录核对");
                var originals = new HashMap<Long, com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.Line>();
                for (var line : proof.lines()) {
                    require(line != null && originals.put(line.noticeDetailId(), line) == null,
                            "受理凭据明细重复，请核对原记录");
                }
                for (var detail : details) {
                    var original = originals.get(detail.id());
                    require(original != null && detail.material().equals(original.materialCode()) &&
                                    detail.unit().equals(original.unit()) && original.quantity() != null &&
                                    detail.planned().compareTo(original.quantity()) == 0,
                            "受理凭据与当前通知明细不一致，请核对原记录");
                    sourceLines.put(detail.id(), original.sourceDocumentDetailId());
                }
                acceptanceStatus = "ACCEPTED";
            }
        }
        var sourceLinksSummary = sourceLinks(source, proof, sourceLines, details.size());
        boolean canViewBills = permissions.hasPermission("wms_bill_view_out", "wms_bill_view_out_" + warehouse);
        var bills = canViewBills ? repository.bills(scope, warehouse,
                noticeId) : List.<WmsProductionNoticeProgressDTO.Bill>of();
        require(bills.size() <= 1000 && bills.stream().allMatch(b -> b.state() != null &&
                        Set.of("0", "1", "2", "11", "12").contains(b.state())),
                "关联出库单数量或状态异常，请核对原单据");
        var lines = details.stream().map(d -> new WmsProductionNoticeProgressDTO.Line(d.id().toString(),
                sourceLines.get(d.id()) == null ? "" : sourceLines.get(d.id()).toString(), d.material(), text(d.name()),
                d.unit(), d.planned().toPlainString(), d.allocated().toPlainString(),
                d.remaining().toPlainString())).toList();
        var receipt = proof == null ? null : new WmsProductionNoticeProgressDTO.Receipt(proof.originalRequestId(),
                proof.acceptedTime().toString(), proof.acceptedBy());
        return new WmsProductionNoticeProgressDTO(1, scope.tenant().toString(), scope.factory(), scope.operator(),
                warehouse, text(notice.warehouseName()), noticeId.toString(), notice.state(),
                source ? notice.documentId().toString() : "", revision, acceptanceStatus, receipt, sourceLinksSummary,
                lines, canViewBills,
                bills);
    }

    /**
     * null兼容旧接口缺项；无效或重复的来源关联不能以缺项状态掩盖。
     */
    private WmsProductionNoticeProgressDTO.SourceLinks sourceLinks(boolean source, Receipt proof,
                                                                   Map<Long, Long> links, int totalLines) {
        if (!source) return new WmsProductionNoticeProgressDTO.SourceLinks("NOT_APPLICABLE", 0, totalLines);
        if (proof == null) return new WmsProductionNoticeProgressDTO.SourceLinks("UNRECORDED", 0, totalLines);
        var ids = new HashSet<Long>();
        for (var id : links.values()) {
            if (id == null) continue;
            require(id > 0 && ids.add(id), "来源明细关联异常，请核对原受理记录");
        }
        return new WmsProductionNoticeProgressDTO.SourceLinks(ids.size() == totalLines ? "RECORDED" : "INCOMPLETE",
                ids.size(), totalLines);
    }
}
