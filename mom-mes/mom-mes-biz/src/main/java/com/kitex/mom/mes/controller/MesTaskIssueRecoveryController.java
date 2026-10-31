package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesTaskIssueReceiptDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.service.impl.MesTaskIssueRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仅内部实名PLAN调用；业务服务重新检查实际工厂写授权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/internal")
public class MesTaskIssueRecoveryController {
    private final MesTaskIssueRecoveryService recoveries;

    @Inner
    @PostMapping("/receive-replay")
    public R<MesTaskIssueReceiptDTO> recover(@RequestBody List<MesTaskReceiveDTO> candidates) {
        return R.ok(recoveries.recover(candidates));
    }
}
