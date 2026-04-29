package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmEmployeeEntity;
import com.kitex.mom.mdm.entity.MdmTeamEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remoteTeamService", value = Constants.Name)
public interface RemoteTeamService {

    /**
     * 根据类型查询班组及班组下的用户
     *
     * @param type 类型
     * @return R
     */
    @GetMapping("/mdmTeam/type")
    R<List<MdmTeamEntity>> getUserByTeamType(@RequestParam("type") String type);

    /**
     * 根据班组编码查询班组下的用户
     *
     * @param code 班组编码
     * @return R
     */
    @GetMapping("/mdmTeam/code")
    R<List<MdmEmployeeEntity>> getUserByTeamCode(@RequestParam("code") String code);
}
