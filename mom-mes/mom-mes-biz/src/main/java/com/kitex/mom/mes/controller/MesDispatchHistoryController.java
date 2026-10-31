package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.Detail;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.NoteCommand;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.PrintCommand;
import com.kitex.mom.mes.dto.MesDispatchHistoryDTO.Query;
import com.kitex.mom.mes.service.impl.MesDispatchHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesDispatchHistory")
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
public class MesDispatchHistoryController {
    private final MesDispatchHistoryService service;

    @GetMapping("/page")
    public R<Map<String, Object>> page(Query query) {
        return R.ok(service.page(query));
    }

    @GetMapping("/{arrangeId}")
    public R<Detail> detail(@PathVariable String arrangeId) {
        return R.ok(service.detail(arrangeId));
    }

    @GetMapping("/{arrangeId}/notes")
    public R<Map<String, Object>> notes(@PathVariable String arrangeId,
                                        @RequestParam(defaultValue = "1") int current, @RequestParam(defaultValue = "20") int size) {
        return R.ok(service.notes(arrangeId, current, size));
    }

    @PostMapping("/notes")
    public R<Map<String, Object>> note(@RequestBody NoteCommand command) {
        return R.ok(service.note(command));
    }

    @GetMapping("/note-requests/{requestId}")
    public R<Map<String, Object>> noteReceipt(@PathVariable String requestId) {
        return R.ok(service.noteReceipt(requestId));
    }

    @PostMapping("/notes/cancel-original")
    public R<Map<String, Object>> cancelNote(@RequestBody NoteCommand command) {
        return R.ok(service.cancelNote(command));
    }

    @GetMapping("/print-template/{templateId}")
    public R<Map<String, Object>> printTemplate(@PathVariable String templateId) {
        return R.ok(service.printTemplate(templateId));
    }

    @PostMapping("/print-preview")
    public R<Map<String, Object>> printPreview(@RequestBody PrintCommand command) {
        return R.ok(service.printPreview(command));
    }
}
