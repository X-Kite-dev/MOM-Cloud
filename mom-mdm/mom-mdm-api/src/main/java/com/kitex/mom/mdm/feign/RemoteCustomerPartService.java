package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remoteCustomerPartService", value = Constants.Name)
public interface RemoteCustomerPartService {

    /**
     * 查询客户件号信息
     *
     * @return R
     */
    @GetMapping("/mdmCustomerPart/list")
    R<List<MdmCustomerPartEntity>> getList();


    /**
     * 查询客户件号信息
     *
     * @return R
     */
    @GetMapping("/mdmCustomerPart/getPartCodeByMatCode")
    R<List<MdmCustomerPartEntity>> getPartCodeByMatCode(@RequestParam String materialCode);


}
