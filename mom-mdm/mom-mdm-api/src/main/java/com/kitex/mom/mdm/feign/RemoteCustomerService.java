package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteCustomerService", value = Constants.Name)
public interface RemoteCustomerService {

    /**
     * 查询客户信息
     *
     * @return R
     */
    @GetMapping("/mdmCustomer/list")
    R<List<MdmCustomerEntity>> getList();

    /**
     * 查询客户信息（订单模块使用）
     *
     * @return R
     */
    @NoToken
    @GetMapping("/mdmCustomer/oms/list")
    R<List<MdmCustomerEntity>> getListByOms();

    /**
     * 分页查询客户信息
     *
     * @param current     当前页
     * @param size        每页大小
     * @param mdmCustomer 客户表
     * @return R
     */
    @GetMapping("/mdmCustomer/page")
    R<Page<MdmCustomerEntity>> getMdmCustomerPage(@RequestParam("current") Integer current,
                                                  @RequestParam("size") Integer size, @SpringQueryMap MdmCustomerEntity mdmCustomer);

    /**
     * 新增客户信息
     *
     * @param mdmCustomer 客户信息
     * @return R
     */
    @PostMapping("/mdmCustomer")
    R<Boolean> save(@RequestBody MdmCustomerEntity mdmCustomer);

    /**
     * 更新客户信息
     *
     * @param mdmCustomer 客户信息
     * @return R
     */
    @PutMapping("/mdmCustomer")
    R<Boolean> updateById(@RequestBody MdmCustomerEntity mdmCustomer);

    /**
     * 删除客户信息
     *
     * @param ids 客户ID数组
     * @return R
     */
    @DeleteMapping("/mdmCustomer")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 根据条件查询客户详情
     *
     * @param query 查询条件
     * @return R
     */
    @GetMapping("/mdmCustomer/details")
    R<MdmCustomerEntity> getDetails(@RequestParam MdmCustomerEntity query);

}
