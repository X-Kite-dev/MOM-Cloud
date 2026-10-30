package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesIdentifierBindingPreviewDTO;
import com.kitex.mom.mes.entity.MesIdentifierBindingEntity;
import com.kitex.mom.mes.entity.MesIdentifierBindingOperationEntity;
import com.kitex.mom.mes.service.MesIdentifierBindingOperationService;
import com.kitex.mom.mes.service.MesIdentifierBindingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态换码只有只读查询，现场写入复用正式报工权限和完整事务，不另开CRUD捷径。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesIdentifierBinding")
@Tag(name = "动态外码", description = "真实占用实例与节点操作证据")
public class MesIdentifierBindingController {

    private final MesIdentifierBindingService bindings;
    private final MesIdentifierBindingOperationService operations;

    @GetMapping("/preview")
    @Operation(summary = "明确派工后预览单码/双码", description = "不占码、不写报工、不生成弱网许可")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show','mesTaskReport:report','mesStationPass:report','mesTaskReport:integration','mesAutoReport:event')")
    public R<MesIdentifierBindingPreviewDTO> preview(@RequestParam String arrangeCode, @RequestParam String identifier,
                                                     @RequestParam(required = false) String externalIdentifier) {
        return R.ok(bindings.preview(arrangeCode, identifier, externalIdentifier));
    }

    @GetMapping("/evidence")
    @Operation(summary = "查看精确占用实例，包括已释放历史")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show')")
    public R<MesIdentifierBindingEntity> evidence(@RequestParam Long bindingId) {
        return R.ok(bindings.evidence(bindingId));
    }

    @GetMapping("/operations")
    @Operation(summary = "查看精确实例的完整节点操作链")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show')")
    public R<List<MesIdentifierBindingOperationEntity>> operations(@RequestParam Long bindingId) {
        return R.ok(operations.history(bindingId));
    }
}
