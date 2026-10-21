package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmDevicePauseCommand;
import com.kitex.mom.mdm.entity.MdmDevicePauseReceipt;
import com.kitex.mom.mdm.service.impl.MdmDevicePauseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmDevice/controlled-pause")
public class MdmDevicePauseController {
    private final MdmDevicePauseService service;

    // 与原TPM暂停调用兼容：实名及实际工厂write在服务中强制，不接受匿名机器冒充人员。
    @PostMapping
    public R<MdmDevicePauseReceipt> pause(@RequestBody MdmDevicePauseCommand command) {
        return R.ok(service.pause(command));
    }

    @PostMapping("/close")
    public R<MdmDevicePauseReceipt> close(@RequestBody MdmDevicePauseCommand command) {
        return R.ok(service.close(command));
    }

    @GetMapping
    public R<?> page(@RequestParam String deviceCode, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.page(deviceCode, page));
    }
}
