package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.agent.dto.MdmParticipantModels;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

/** 原实名令牌参与人投影；不得添加@NoToken绕过租户与工厂核验。 */
@FeignClient(contextId = "remoteMdmParticipantService", value = Constants.Name)
public interface RemoteMdmParticipantService {
    @InternalCall(userTokenRequired = true)
    @PostMapping("/agent/participants/search")
    R<MdmParticipantModels.ParticipantPage> search(@RequestBody MdmParticipantModels.Search query);
    @InternalCall(userTokenRequired = true)
    @PostMapping("/agent/participants/verify")
    R<List<MdmParticipantModels.Participant>> verify(@RequestBody MdmParticipantModels.Verify query);
}
