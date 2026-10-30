package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.entity.MdmRouteEngineeringReleaseEntity;
import com.kitex.mom.mdm.service.MdmRouteEngineeringReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工程联合发布工作台正式入口，工程批准不代表生产消费接入。
 */
@RestController
@RequestMapping("/mdmRouteEngineeringRelease")
@RequiredArgsConstructor
public class MdmRouteEngineeringReleaseController {
    private final MdmRouteEngineeringReleaseService service;

    /**
     * 当前组织路线、稳定图及正式BOM候选。
     */
    @GetMapping("/context")
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public R<MdmRouteEngineeringDTO.Context> context(@RequestParam("routeId") Long routeId) {
        return R.ok(service.context(routeId));
    }

    /**
     * 完整权威采集预检，客户端不能提交自编工程JSON。
     */
    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public R<MdmRouteEngineeringDTO.Preview> preview(@RequestBody MdmRouteEngineeringDTO.PreviewQuery query) {
        return R.ok(service.preview(query));
    }

    /**
     * 根据动作核独立菜单权限，再执行原号原载荷永久命令。
     */
    @PostMapping("/command")
    @PreAuthorize("(#command.action == 'SUBMIT' and @pms.hasPermission('mdm_routeEngineering_submit'))"
            + " or ((#command.action == 'APPROVE' or #command.action == 'REJECT') and @pms.hasPermission('mdm_routeEngineering_approve'))"
            + " or ((#command.action == 'RETIRE' or #command.action == 'WITHDRAW') and @pms.hasPermission('mdm_routeEngineering_withdraw'))")
    public R<MdmRouteEngineeringDTO.Receipt> command(@RequestBody MdmRouteEngineeringDTO.Command command) {
        // 一次失败/回滚不能证明这个永久号从未成功；所有失败均保留原号查账。
        return R.ok(service.command(command));
    }

    /**
     * 显式核对并封存未执行原号；已成功返回原成功账，绝不覆盖为失败。
     */
    @PostMapping("/seal")
    @PreAuthorize("(#command.action == 'SUBMIT' and @pms.hasPermission('mdm_routeEngineering_submit'))"
            + " or ((#command.action == 'APPROVE' or #command.action == 'REJECT') and @pms.hasPermission('mdm_routeEngineering_approve'))"
            + " or ((#command.action == 'RETIRE' or #command.action == 'WITHDRAW') and @pms.hasPermission('mdm_routeEngineering_withdraw'))")
    public R<MdmRouteEngineeringDTO.Receipt> seal(@RequestBody MdmRouteEngineeringDTO.Command command) {
        return R.ok(service.seal(command));
    }

    /**
     * 当前真实路线的全部有界工程版本。
     */
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public R<List<MdmRouteEngineeringReleaseEntity>> list(@RequestParam("routeId") Long routeId) {
        return R.ok(service.releases(routeId));
    }

    /**
     * 精确原封存正文、生命周期修订和审计。
     */
    @GetMapping("/detail/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public R<MdmRouteEngineeringReleaseService.Detail> detail(@PathVariable("id") Long id) {
        return R.ok(service.detail(id));
    }

    /**
     * 原实名用户恢复未知结果，不把未找到当作未执行证明。
     */
    @GetMapping("/receipt/{requestId}")
    @PreAuthorize("@pms.hasPermission('mdm_routeEngineering_view')")
    public R<MdmRouteEngineeringDTO.Receipt> receipt(@PathVariable("requestId") String requestId) {
        return R.ok(service.receipt(requestId));
    }

    /**
     * PLAN/APS在当前实名组织内选择已发布工程版本，不返回大体积冻结正文。
     */
    @Inner
    @GetMapping("/internal/production-candidates")
    public R<List<MdmRouteEngineeringDTO.ProductionCandidate>> productionCandidates(
            @RequestParam("productCode") String productCode) {
        return R.ok(service.productionCandidates(productCode));
    }

    /**
     * PLAN/MES保存或下达前精确取包；服务端重新核当前来源，客户端不能提交替代正文。
     */
    @Inner
    @PostMapping("/internal/resolve-for-production")
    public R<MdmRouteEngineeringDTO.ProductionPackage> resolveForProduction(
            @RequestBody MdmRouteEngineeringDTO.ProductionQuery query) {
        return R.ok(service.resolveForProduction(query));
    }

    /**
     * MES返工方案按原生产工程版本和稳定节点选择已批准覆盖版。
     */
    @Inner
    @GetMapping("/internal/rework-override-candidates")
    public R<List<MdmRouteEngineeringDTO.ReworkOverrideCandidate>> reworkOverrideCandidates(
            @RequestParam("baseReleaseId") Long baseReleaseId,
            @RequestParam("baseContentHash") String baseContentHash,
            @RequestParam("targetRouteNodeCode") String targetRouteNodeCode) {
        return R.ok(service.reworkOverrideCandidates(baseReleaseId, baseContentHash, targetRouteNodeCode));
    }

    /**
     * MES保存返工方案前精确取覆盖包并重采正式SOP/参数来源。
     */
    @Inner
    @PostMapping("/internal/resolve-for-rework-override")
    public R<MdmRouteEngineeringDTO.ReworkOverridePackage> resolveForReworkOverride(
            @RequestBody MdmRouteEngineeringDTO.ReworkOverrideQuery query) {
        return R.ok(service.resolveForReworkOverride(query));
    }
}
