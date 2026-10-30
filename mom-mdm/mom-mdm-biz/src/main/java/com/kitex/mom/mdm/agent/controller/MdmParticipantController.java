package com.kitex.mom.mdm.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels.Participant;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels.ParticipantPage;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels.Search;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels.Verify;
import com.kitex.mom.mdm.agent.service.MdmParticipantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 固定内部选人接口；内部标记、原实名菜单权限及实际工厂授权缺一不可。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/agent/participants")
public class MdmParticipantController {
    private final MdmParticipantService service;

    @Inner
    @PostMapping("/search")
    @PreAuthorize("@pms.hasPermission('agent:operations:assign') or @pms.hasPermission('mesShiftHandover:submit')")
    public R<ParticipantPage> search(@RequestBody Search query) {
        return R.ok(service.search(query));
    }

    @Inner
    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('agent:operations:assign') or @pms.hasPermission('agent:operations:accept') or "
            + "@pms.hasPermission('agent:operations:handle') or @pms.hasPermission('agent:operations:verify') or "
            + "@pms.hasPermission('mesShiftHandover:submit') or @pms.hasPermission('mesShiftHandover:receive')")
    public R<List<Participant>> verify(@RequestBody Verify query) {
        return R.ok(service.verify(query));
    }
}
