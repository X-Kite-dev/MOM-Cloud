package com.kitex.mom.wms.service.noticeAcceptance;

import cn.hutool.core.util.IdUtil;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO.*;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.repository.WmsProductionNoticeAcceptanceRepository;
import com.kitex.mom.wms.repository.WmsProductionNoticeAcceptanceRepository.*;
import com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeAccess.Scope;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeCanonical.*;

/**
 * 受理与WMS通知在同一个本地事务；不把MES本域回滚当成远端回滚。
 */
@Service
@RequiredArgsConstructor
public class WmsProductionNoticeAcceptanceService {
    private final WmsProductionNoticeAcceptanceRepository repo;
    private final WmsProductionNoticeAccess access;
    private final DefaultWmsOutNoticeService writer;

    @Transactional(rollbackFor = Exception.class)
    public Receipt accept(Command command) {
        var scope = access.scope(warehouse(command), true, false);
        var prepared = prepare(command, scope, false);
        lock(scope, command.getRequestId(), command.getNotice().getDocumentId());
        return apply(command, scope, prepared);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Receipt> acceptBatch(List<Command> commands) {
        require(commands != null && !commands.isEmpty() && commands.size() <= 20, "一次受理须为1至20个仓库");
        var scope = access.scope(warehouse(commands.get(0)), true, false);
        var first = commands.get(0);
        var prepared = new ArrayList<Prepared>();
        var requests = new TreeSet<String>();
        var warehouses = new HashSet<String>();
        int lines = 0;
        for (var command : commands) {
            var item = prepare(command, scope, false);
            require(Objects.equals(command.getNotice().getDocumentId(), first.getNotice().getDocumentId()) &&
                            Objects.equals(command.getSourceRevision(), first.getSourceRevision()) &&
                            Objects.equals(command.getExpectedPreviousRevision(), first.getExpectedPreviousRevision()),
                    "批量受理须属于相同来源和修订");
            require(requests.add(command.getRequestId()) && warehouses.add(item.notice().getWarehouseCode()),
                    "批量受理仓库或请求号重复");
            lines += item.notice().getDetailList().size();
            prepared.add(item);
        }
        require(lines <= 500, "一次批量受理明细不能超过500行");
        // 批量先获取全部请求锁，再获取源锁，防止单仓与多仓之间反向等待。
        for (String request : requests) repo.lock(scope.tenant(), "REQUEST:" + request);
        repo.lock(scope.tenant(), sourceKey(scope, first.getNotice().getDocumentId()));
        var result = new ArrayList<Receipt>();
        for (int i = 0; i < commands.size(); i++) result.add(apply(commands.get(i), scope, prepared.get(i)));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean acceptLegacy(WmsOutNoticeEntity notice) {
        require(notice != null, "缺少领料来源");
        var scope = access.scope(notice.getWarehouseCode(), true, false);
        require(notice.getSourceAcceptanceRevision() == null || notice.getSourceAcceptanceRevision() == 1,
                "该来源已使用新版修订，请按原修订协议提交");
        var command = new Command();
        command.setRequestId(legacyKey(scope, notice.getDocumentId(), text(notice.getWarehouseCode()), "ACCEPT"));
        command.setTenantId(scope.tenant());
        command.setFactoryCode(scope.factory());
        command.setSourceRevision(1);
        command.setNotice(notice);
        var prepared = prepare(command, scope, true);
        lock(scope, command.getRequestId(), notice.getDocumentId());
        var existing = repo.acceptance(scope, notice.getDocumentId(), 1, prepared.notice().getWarehouseCode());
        if (existing != null)
            live(scope, notice.getDocumentId(), 1, prepared.notice().getWarehouseCode(), existing.noticeId(), true);
        var receipt = apply(command, scope, prepared);
        notice.setId(receipt.noticeId());
        return true;
    }

    private Receipt apply(Command command, Scope scope, Prepared prepared) {
        var original = repo.operation(scope.tenant(), command.getRequestId());
        if (original != null) {
            require(scope.factory().equals(original.factory()) && prepared.commandPayload().equals(original.payload()),
                    "原请求号已用于其他来源或业务内容");
            return receipt(original.result(), scope, command.getRequestId());
        }
        var notice = prepared.notice();
        Long document = notice.getDocumentId();
        int revision = command.getSourceRevision();
        String warehouse = notice.getWarehouseCode();
        advance(scope, document, revision, command.getExpectedPreviousRevision());
        var accepted = repo.acceptance(scope, document, revision, warehouse);
        Receipt result;
        if (accepted != null) {
            require(prepared.noticePayload().equals(accepted.noticePayload()),
                    "该来源仓库已按不同内容受理，请先核对原通知");
            live(scope, document, revision, warehouse, accepted.noticeId(), true);
            var first = receipt(accepted.receiptPayload(), scope, null);
            result = new Receipt(1, "ACCEPT", "ACCEPTED", command.getRequestId(), first.originalRequestId(),
                    scope.tenant(), scope.factory(), SOURCE, document, revision, warehouse, first.noticeId(),
                    first.lines(), prepared.fingerprint(), prepared.commandPayload(), first.acceptedTime(),
                    first.acceptedBy(), List.of(), "");
        } else {
            var originLines = notice.getDetailList().stream().map(line -> line.getSourceDocumentDetailId()).filter(
                    Objects::nonNull).toList();
            require(!repo.sourceLineUsed(scope, document, revision, originLines),
                    "同一原领料明细已在其他仓受理，不能再次生成通知");
            require(repo.notices(scope, document, revision, warehouse, true).isEmpty(),
                    "已有历史来源通知但缺少受理凭据，请先核对，禁止重复建单");
            require(Boolean.TRUE.equals(writer.persistAcceptedSourceNotice(notice)) && notice.getId() != null &&
                    notice.getId() > 0, "领料通知写入未确认");
            var stored = live(scope, document, revision, warehouse, notice.getId(), false);
            var details = repo.details(scope, notice.getId());
            require(details.size() == notice.getDetailList().size(), "受理明细未完整持久化");
            var byId = new HashMap<Long, Detail>();
            details.forEach(line -> byId.put(line.id(), line));
            var links = new ArrayList<Line>();
            BigDecimal total = BigDecimal.ZERO;
            for (var line : notice.getDetailList()) {
                var actual = byId.get(line.getId());
                BigDecimal quantity = BigDecimal.valueOf(line.getNoticeOutNum());
                require(actual != null && Objects.equals(actual.documentId(), document) &&
                                SOURCE.equals(actual.documentTable()) && warehouse.equals(actual.warehouse()) &&
                                line.getMatCode().equals(actual.material()) && line.getMatUnit().equals(actual.unit()) &&
                                actual.quantity() != null && quantity.compareTo(actual.quantity()) == 0,
                        "通知明细的来源、单位或实际保存数量不一致");
                require(sameDecimal(line.getRequirementMatNum(), actual.requirementQuantity())
                                && sameDecimal(line.getSubstituteRatio(), actual.substituteRatio()),
                        "BOM需求当量或替代比例的实际保存值不一致，不能签发受理凭据");
                links.add(new Line(line.getSourceDocumentDetailId(), actual.id(), actual.material(), actual.unit(),
                        actual.quantity()));
                total = total.add(quantity);
            }
            require(stored.quantity() != null && stored.quantity().compareTo(total) == 0,
                    "通知主单保存数量发生舍入，不能签发受理凭据");
            var now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            result = new Receipt(1, "ACCEPT", "ACCEPTED", command.getRequestId(), command.getRequestId(),
                    scope.tenant(), scope.factory(), SOURCE, document, revision, warehouse, notice.getId(), links,
                    prepared.fingerprint(), prepared.commandPayload(), now, scope.operator(), List.of(), "");
            Long id = IdUtil.getSnowflakeNextId();
            require(repo.insertAcceptance(scope, id, document, revision, warehouse, notice.getId(),
                            command.getRequestId(), prepared.noticePayload(), prepared.fingerprint(), write(result), now) == 1,
                    "领料永久受理凭据保存失败");
            for (int i = 0; i < links.size(); i++)
                require(repo.insertLine(scope, document, revision, id, i, links.get(i)) == 1, "领料原明细关联保存失败");
        }
        require(repo.insertOperation(scope, command.getRequestId(), document, revision, warehouse, "ACCEPT",
                prepared.commandPayload(), write(result)) == 1, "领料原请求回执保存失败");
        return result;
    }

    private void advance(Scope scope, Long document, int revision, Integer previous) {
        var head = repo.head(scope, document, true);
        if (head == null) {
            require(revision == 1, "来源首次受理必须使用修订1");
            require(repo.latestNoticeRevision(scope, document) == 0,
                    "已有历史来源通知，须先核对完整来源，不能将其他仓当成新来源");
            require(repo.insertHead(scope, document, 1, "ACTIVE") == 1, "来源受理登记失败");
            return;
        }
        if (head.revision() == revision) {
            require("ACTIVE".equals(head.state()), "该修订已撤回，不能用旧请求重新建通知；新一轮请显式建立新修订");
            return;
        }
        require(revision == head.revision() + 1 && Objects.equals(previous, head.revision()),
                "来源修订已变化，请核对原受理结果");
        require(repo.latestNoticeRevision(scope, document) <= head.revision(), "存在未登记的历史修订，请先核对来源");
        var notices = repo.notices(scope, document, head.revision(), null, true);
        require(notices.size() <= 1000 && notices.stream().allMatch(
                        n -> "3".equals(n.state()) && "0".equals(n.deleted()) && n.out() != null && n.out().signum() == 0),
                "前一修订仍有未撤回或已执行通知");
        require(!repo.hasOutBills(scope, notices.stream().map(Notice::id).toList()),
                "前一修订仍有关联出库单，不能重新申请");
        require("CANCELLED".equals(head.state()) || !notices.isEmpty(), "前一修订尚未形成可核对的撤回记录");
        require(repo.updateHead(scope, document, head.revision(), revision, "ACTIVE") == 1, "来源修订推进失败");
    }

    @Transactional(rollbackFor = Exception.class)
    public Receipt cancel(Cancel command) {
        var scope = access.scope("", true, true);
        String payload = cancelPayload(command, scope);
        lock(scope, command.getRequestId(), command.getDocumentId());
        return cancelLocked(command, scope, payload);
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean cancelLegacy(Long document, String table) {
        require(SOURCE.equals(table) && document != null && document > 0, "MES领料撤回来源无效");
        var scope = access.scope("", true, true);
        var command = new Cancel();
        command.setTenantId(scope.tenant());
        command.setFactoryCode(scope.factory());
        command.setDocumentId(document);
        command.setSourceRevision(1);
        command.setRequestId(legacyKey(scope, document, "", "CANCEL"));
        command.setReason("来源业务撤回");
        lock(scope, command.getRequestId(), document);
        var head = repo.head(scope, document, true);
        require(head == null || head.revision() == 1, "来源已使用新版修订，请通过原修订协议撤回");
        cancelLocked(command, scope, cancelPayload(command, scope));
        return true;
    }

    private Receipt cancelLocked(Cancel command, Scope scope, String payload) {
        var operation = repo.operation(scope.tenant(), command.getRequestId());
        if (operation != null) {
            require(scope.factory().equals(operation.factory()) && payload.equals(operation.payload()),
                    "原撤回请求号已用于不同内容");
            return receipt(operation.result(), scope, command.getRequestId());
        }
        Long document = command.getDocumentId();
        int revision = command.getSourceRevision();
        var head = repo.head(scope, document, true);
        require(head == null ?
                        revision == 1 && repo.latestNoticeRevision(scope, document) <= 1 : revision <= head.revision(),
                "撤回来源修订不存在或历史修订尚未登记");
        var notices = repo.notices(scope, document, revision, null, true);
        require(notices.size() <= 1000, "来源仓库超过安全处理范围，请逐源核对");
        require(notices.stream().allMatch(
                n -> "0".equals(n.deleted()) && n.state() != null && Set.of("0", "3").contains(n.state()) &&
                        n.out() != null && n.out().signum() == 0), "来源通知已开始出库或记录不完整，不可撤回");
        var ids = notices.stream().map(Notice::id).toList();
        require(!repo.hasOutBills(scope, ids), "来源通知已关联出库单，请先处理出库单；已出库须走退料流程");
        for (var notice : notices) if (!"3".equals(notice.state())) writer.voidObjs(notice.id());
        require(repo.notices(scope, document, revision, null, false).stream().allMatch(n -> "3".equals(n.state())),
                "来源通知撤回结果未完整确认");
        if (head == null) require(repo.insertHead(scope, document, revision, "CANCELLED") == 1, "来源取消标记保存失败");
        else if (head.revision() == revision)
            require(repo.updateHead(scope, document, revision, revision, "CANCELLED") == 1, "来源撤回标记保存失败");
        var result = new Receipt(1, "CANCEL", "CANCELLED", command.getRequestId(), command.getRequestId(),
                scope.tenant(), scope.factory(), SOURCE, document, revision, "", null, List.of(), hash(payload),
                payload, LocalDateTime.now(), scope.operator(), ids, text(command.getReason()));
        require(repo.insertOperation(scope, command.getRequestId(), document, revision, "", "CANCEL", payload,
                write(result)) == 1, "来源撤回回执保存失败");
        return result;
    }

    public Lookup lookup(String warehouse, String request) {
        request(request);
        var scope = access.scope(text(warehouse), false, text(warehouse).isEmpty());
        var operation = repo.operation(scope.tenant(), request);
        if (operation == null) return new Lookup(1, scope.tenant(), scope.factory(), request, "NOT_FOUND", null);
        require(scope.factory().equals(operation.factory()) && text(warehouse).equals(operation.warehouse()),
                "原请求不属于当前工厂或仓库");
        return new Lookup(1, scope.tenant(), scope.factory(), request, "FOUND",
                receipt(operation.result(), scope, request));
    }

    public Source source(String warehouse, Long document, int revision) {
        require(document != null && document > 0 && revision > 0, "来源及修订无效");
        var scope = access.scope(warehouse, false, false);
        var head = repo.head(scope, document, false);
        var accepted = repo.acceptance(scope, document, revision, warehouse);
        var notices = repo.notices(scope, document, revision, warehouse, false);
        String status = accepted == null ? (notices.isEmpty() ? "NOT_FOUND" : "LEGACY_REVIEW_REQUIRED") : "ACCEPTED";
        String state = "";
        Receipt proof = null;
        if (accepted != null) {
            proof = receipt(accepted.receiptPayload(), scope, null);
            var live = repo.notice(scope, document, revision, warehouse, accepted.noticeId(), false);
            require(live != null && "0".equals(live.deleted()), "已受理通知缺失或来源变化，请保留原凭据核对");
            state = live.state();
        }
        if (accepted == null && notices.isEmpty()) head = null;
        return new Source(1, scope.tenant(), scope.factory(), document, revision, head == null ? null : head.revision(),
                head == null ? "NOT_FOUND" : head.state(), warehouse, status, proof, state,
                accepted == null ? notices.stream().map(Notice::id).toList() : List.of());
    }

    private boolean sameDecimal(BigDecimal expected, BigDecimal actual) {
        return expected == null ? actual == null : actual != null && expected.compareTo(actual) == 0;
    }

    private Notice live(Scope scope, Long document, int revision, String warehouse, Long id, boolean reusable) {
        var row = repo.notice(scope, document, revision, warehouse, id, true);
        require(row != null && "0".equals(row.deleted()) && row.state() != null &&
                Set.of("0", "1", "2", "3").contains(row.state()), "原通知缺失、范围变化或状态无效");
        if (reusable) require(!"3".equals(row.state()), "原通知已作废，不能将历史受理当成当前有效通知");
        return row;
    }

    private Receipt receipt(String payload, Scope scope, String request) {
        var result = read(payload, Receipt.class);
        require(result.contractVersion() == 1 && Objects.equals(result.tenantId(), scope.tenant()) &&
                        scope.factory().equals(result.factoryCode()) && (request == null || request.equals(result.requestId())),
                "永久回执与当前实际作用域不一致");
        return result;
    }

    private void lock(Scope scope, String request, Long document) {
        repo.lock(scope.tenant(), "REQUEST:" + request);
        repo.lock(scope.tenant(), sourceKey(scope, document));
    }

    private String sourceKey(Scope scope, Long document) {
        return "SOURCE:" + scope.factory() + ":" + document;
    }

    private String legacyKey(Scope scope, Long document, String warehouse, String action) {
        return "PN-LEGACY-" +
                hash(scope.tenant() + ":" + scope.factory() + ":" + document + ":" + warehouse + ":" + action +
                        ":1").substring(0, 50);
    }

    private String warehouse(Command command) {
        require(command != null && command.getNotice() != null, "缺少领料通知请求");
        return command.getNotice().getWarehouseCode();
    }
}
