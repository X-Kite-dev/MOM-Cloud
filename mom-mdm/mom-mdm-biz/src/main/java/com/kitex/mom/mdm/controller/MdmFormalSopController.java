package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopBindingEntity;
import com.kitex.mom.mdm.entity.MdmSopOperationEntity;
import com.kitex.mom.mdm.entity.MdmSopOriginEntity;
import com.kitex.mom.mdm.entity.MdmSopVersionEntity;
import com.kitex.mom.mdm.service.MdmFormalSopService;
import com.kitex.mom.mdm.sop.MdmFormalSopBindingService;
import com.kitex.mom.mdm.sop.MdmFormalSopSourceService;
import com.kitex.mom.mdm.sop.MdmSopJournal;
import com.kitex.mom.mdm.sop.MdmSopWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 正式SOP产品工作台。每个写动作仍由业务服务复核实际工厂WRITE授权。
 */
@RestController
@RequestMapping("/mdmSop")
@RequiredArgsConstructor
public class MdmFormalSopController {
    private final MdmFormalSopService versions;
    private final MdmFormalSopBindingService bindings;
    private final MdmFormalSopSourceService sources;
    private final MdmSopJournal journal;
    private final MdmSopWorkspace workspace;

    /**
     * 固定附件响应，拒绝浏览器将上传内容解释成页面脚本。
     */
    public static ResponseEntity<byte[]> download(MdmFormalSopSourceService.OriginalContent original) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(original.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(original.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(original.bytes());
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<Page<MdmSopVersionEntity>> page(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String nodeId,
                                             @RequestParam(defaultValue = "AUTHORED") String mode,
                                             @RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size) {
        return R.ok(nodeId == null || nodeId.isBlank() ? versions.catalog(keyword, status, current, size)
                : workspace.page(nodeId, mode, keyword, status, current, size));
    }

    /**
     * 工序工作区只由服务端解析节点身份，不信任前端传来的路线归属。
     */
    @GetMapping("/nodes/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<MdmSopWorkspace.NodeContext> node(@PathVariable String id) {
        return R.ok(workspace.context(id));
    }

    @GetMapping("/nodes/{id}/legacy")
    @PreAuthorize("@pms.hasPermission('mdm_sop_edit')")
    public R<Page<MdmSopWorkspace.LegacyItem>> legacy(@PathVariable String id,
                                                      @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long size) {
        return R.ok(workspace.legacy(id, current, size));
    }

    @GetMapping("/versions/{id}/origins")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<Page<MdmSopOriginEntity>> origins(@PathVariable String id,
                                               @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long size) {
        versions.detail(id);
        return R.ok(workspace.origins(id, current, size));
    }

    @GetMapping("/versions/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<MdmSopVersionEntity> detail(@PathVariable String id) {
        return R.ok(versions.detail(id));
    }

    @GetMapping("/versions/{id}/operations")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<Page<MdmSopOperationEntity>> operations(@PathVariable String id,
                                                     @RequestParam(defaultValue = "1") long current,
                                                     @RequestParam(defaultValue = "10") long size) {
        return R.ok(journal.page(versions.detail(id).getId(), current, size));
    }

    @PostMapping(value = "/originals", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SysLog("上传正式SOP不可变原件")
    @PreAuthorize("@pms.hasPermission('mdm_sop_edit')")
    public R<MdmFormalSopDTO.Receipt> upload(@RequestParam String requestId, @RequestPart MultipartFile file) {
        return R.ok(versions.upload(requestId, file));
    }

    @GetMapping("/originals/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public ResponseEntity<byte[]> original(@PathVariable String id, @RequestParam(required = false) String versionId,
                                           @RequestParam(required = false) String contentHash) {
        return download(sources.download(id, versionId, contentHash));
    }

    @PostMapping("/commands")
    @SysLog("编制或提交正式SOP")
    @PreAuthorize("@pms.hasPermission('mdm_sop_edit')")
    public R<MdmFormalSopDTO.Receipt> edit(@RequestBody MdmFormalSopDTO.Command command) {
        return R.ok(versions.edit(command));
    }

    @PostMapping("/review")
    @SysLog("独立审核或撤回正式SOP")
    @PreAuthorize("@pms.hasPermission('mdm_sop_review')")
    public R<MdmFormalSopDTO.Receipt> review(@RequestBody MdmFormalSopDTO.Command command) {
        return R.ok(versions.review(command));
    }

    @GetMapping("/bindings/page")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<Page<MdmSopBindingEntity>> bindings(@RequestParam(required = false) String routeCode,
                                                 @RequestParam(required = false) String routeNodeCode,
                                                 @RequestParam(required = false) String versionId,
                                                 @RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size) {
        return R.ok(bindings.page(routeCode, routeNodeCode, versionId, current, size));
    }

    @PostMapping("/bindings")
    @SysLog("受控变更路线节点正式SOP绑定")
    @PreAuthorize("@pms.hasPermission('mdm_sop_bind')")
    public R<MdmFormalSopDTO.Receipt> bind(@RequestBody MdmFormalSopDTO.BindCommand command) {
        return R.ok(bindings.execute(command));
    }

    @GetMapping("/requests/{requestId}")
    @PreAuthorize("@pms.hasPermission('mdm_sop_view','mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<MdmFormalSopDTO.Receipt> receipt(@PathVariable String requestId) {
        return R.ok(journal.receipt(requestId));
    }

    @PostMapping("/requests/{requestId}/cancel-unexecuted")
    @SysLog("取消尚未执行的原SOP请求")
    @PreAuthorize("@pms.hasPermission('mdm_sop_edit','mdm_sop_review','mdm_sop_bind')")
    public R<MdmFormalSopDTO.Receipt> cancel(@PathVariable String requestId) {
        return R.ok(journal.cancel(requestId));
    }
}
