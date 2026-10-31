package com.kitex.mom.qms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.dto.QmsCapaExternalCreateCommand;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.QmsCapaMesRetestReopenCommand;
import com.kitex.mom.qms.dto.req.*;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.service.QmsCapaMesRetestReopenService;
import com.kitex.mom.qms.service.QmsCapaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(description = "capa", name = "CAPA/8D闭环")
@RestController
@RequestMapping("/capa")
@RequiredArgsConstructor
public class QmsCapaController {
    private final QmsCapaService service;
    private final QmsCapaMesRetestReopenService retestReopenService;

    @GetMapping("/{id}/mes-retest-reopen-receipts")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    @Operation(summary = "查看MES复测未达标的旧关闭快照与复开回执")
    public R<?> retestReopenReceipts(@PathVariable Long id) {
        return R.ok(retestReopenService.receipts(id));
    }

    @Inner
    @PostMapping("/internal/mes-retest/reopen")
    @PreAuthorize("@pms.hasPermission('capa:verify') and @pms.hasPermission('mesChangePointHandle:verify')")
    @Operation(summary = "反查MES独立复核事实并按永久请求号复开CAPA")
    public R<QmsCapaLinkDTO> reopenFromMesRetest(@RequestBody QmsCapaMesRetestReopenCommand command) {
        return R.ok(retestReopenService.reopen(command));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<IPage<QmsCapaEntity>> page(Page<QmsCapaEntity> page, QmsCapaEntity query) {
        return R.ok(service.queryPage(page, query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<QmsCapaEntity> detail(@PathVariable Long id) {
        return R.ok(service.getDetail(id));
    }

    @GetMapping("/{id}/actions")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<?> actions(@PathVariable Long id) {
        return R.ok(service.actions(id));
    }

    @GetMapping("/{id}/causes")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<?> causes(@PathVariable Long id) {
        return R.ok(service.causes(id));
    }

    /** 沿原CAPA查看权限，按精确案件读取措施；不承接Agent自动执行动作。 */
    @GetMapping("/{id}/actions/page")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<?> actionPage(@PathVariable Long id, @RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Integer cycleNo,
                          @RequestParam(required = false) String status, @RequestParam(required = false) Long actionId) {
        return R.ok(service.actionPage(id, current, size, cycleNo, status, actionId));
    }

    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<?> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }

    @Operation(summary = "从NCR发起CAPA")
    @PostMapping("/create")
    @PreAuthorize("@pms.hasPermission('capa:add')")
    public R<Long> create(@RequestBody QmsCapaCreateReq req) {
        return R.ok(service.create(req));
    }

    @Inner
    @Operation(summary = "MOM业务模块按权威来源发起CAPA")
    @PostMapping("/internal/external")
    public R<QmsCapaLinkDTO> createExternal(@RequestBody QmsCapaExternalCreateCommand command) {
        return R.ok(service.createExternal(command));
    }

    @Inner
    @Operation(summary = "MOM业务模块读取已链接CAPA状态")
    @GetMapping("/internal/link/{id}")
    public R<QmsCapaLinkDTO> getExternalLink(@PathVariable Long id) {
        return R.ok(service.getExternalLink(id));
    }

    @Operation(summary = "提交CAPA并进入围堵阶段")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('capa:coordinate')")
    public R<Void> submit(@RequestBody QmsCapaStageReq req) {
        service.submit(req);
        return R.ok();
    }

    @PostMapping("/action/add")
    @PreAuthorize("@pms.hasPermission('capa:coordinate')")
    public R<Long> addAction(@RequestBody QmsCapaActionAddReq req) {
        return R.ok(service.addAction(req));
    }

    @PostMapping("/action/accept")
    @PreAuthorize("@pms.hasPermission('capa:execute')")
    public R<Void> acceptAction(@RequestBody QmsCapaActionAcceptReq req) {
        service.acceptAction(req);
        return R.ok();
    }

    @PostMapping("/action/cancel")
    @PreAuthorize("@pms.hasPermission('capa:coordinate')")
    public R<Void> cancelAction(@RequestBody QmsCapaActionCancelReq req) {
        service.cancelAction(req);
        return R.ok();
    }

    @PostMapping("/action/complete")
    @PreAuthorize("@pms.hasPermission('capa:execute')")
    public R<Void> completeAction(@RequestBody QmsCapaActionCompleteReq req) {
        service.completeAction(req);
        return R.ok();
    }

    @PostMapping("/cause/add")
    @PreAuthorize("@pms.hasPermission('capa:analyze')")
    public R<Long> addCause(@RequestBody QmsCapaCauseAddReq req) {
        return R.ok(service.addCause(req));
    }

    @PostMapping("/cause/verify")
    @PreAuthorize("@pms.hasPermission('capa:verify')")
    public R<Void> verifyCause(@RequestBody QmsCapaCauseVerifyReq req) {
        service.verifyCause(req);
        return R.ok();
    }

    @PostMapping("/advance")
    @PreAuthorize("@pms.hasPermission('capa:coordinate')")
    public R<Void> advance(@RequestBody QmsCapaStageReq req) {
        service.advance(req);
        return R.ok();
    }

    @PostMapping("/effectiveness/verify")
    @PreAuthorize("@pms.hasPermission('capa:verify')")
    public R<Void> verifyEffectiveness(@RequestBody QmsCapaEffectivenessReq req) {
        service.verifyEffectiveness(req);
        return R.ok();
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('capa:cancel')")
    public R<Void> cancel(@RequestBody QmsCapaStageReq req) {
        service.cancel(req);
        return R.ok();
    }
}
