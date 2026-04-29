package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteSupplierService", value = Constants.Name)
public interface RemoteSupplierService {

    /**
     * 分页查询供应商信息
     *
     * @param current     当前页码
     * @param size        每页大小
     * @param mdmSupplier 供应商实体
     * @return R
     */
    @GetMapping("/mdmSupplier/page")
    R<Page<MdmSupplierEntity>> getMdmSupplierPage(@RequestParam("current") Long current,
                                                  @RequestParam("size") Long size, @SpringQueryMap MdmSupplierEntity mdmSupplier);

    /**
     * 获取供应商列表
     *
     * @return R
     */
    @GetMapping("/mdmSupplier/list")
    R<List<MdmSupplierEntity>> getList(@SpringQueryMap MdmSupplierEntity query);

    /**
     * 获取供应商详情
     *
     * @return R
     */
    @GetMapping("/mdmSupplier/details")
    R<MdmSupplierEntity> getDetails(@SpringQueryMap MdmSupplierEntity query);

    /**
     * 新增供应商信息
     *
     * @param mdmSupplier 供应商实体
     * @return R
     */
    @PostMapping("/mdmSupplier")
    R<Long> save(@RequestBody MdmSupplierEntity mdmSupplier);

    /**
     * 更新供应商信息
     *
     * @param mdmSupplier 供应商实体
     * @return R
     */
    @PutMapping("/mdmSupplier")
    R<Boolean> updateById(@RequestBody MdmSupplierEntity mdmSupplier);

    /**
     * 删除供应商信息
     *
     * @param ids 供应商 ID数组
     * @return R
     */
    @DeleteMapping("/mdmSupplier")
    R<Boolean> removeById(@RequestBody Long[] ids);

}
