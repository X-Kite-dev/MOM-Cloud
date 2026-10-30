package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteWarehouseService", value = Constants.Name)
public interface RemoteWarehouseService {

    /**
     * 分页查询仓库信息
     *
     * @param current      当前页码
     * @param size         每页记录数
     * @param mdmWarehouse 仓库实体
     * @return R
     */
    @GetMapping("/mdmWarehouse/page")
    R<Page<MdmWarehouseEntity>> getMdmWarehousePage(@RequestParam("current") Integer current,
                                                    @RequestParam("size") Integer size, @SpringQueryMap MdmWarehouseEntity mdmWarehouse);

    /**
     * 获取仓库列表
     *
     * @return R
     */
    @GetMapping("/mdmWarehouse/list")
    R<List<MdmWarehouseEntity>> getList(@SpringQueryMap MdmWarehouseEntity mdmWarehouse);

    @NoToken
    @GetMapping("/mdmWarehouse/productEnabled")
    R<List<MdmWarehouseEntity>> getEnabledProductWarehouses();

    /**
     * 菜单生命周期使用全部未删除仓库，包含停用状态，必须限定实际租户/工厂。
     */
    @InternalCall
    @GetMapping("/mdmWarehouse/menuConfigurationWarehouses")
    R<List<MdmWarehouseEntity>> getMenuConfigurationWarehouses();

    /**
     * 根据编码获取仓库
     *
     * @return R
     */
    @GetMapping("/mdmWarehouse/getByCode")
    R<MdmWarehouseEntity> getByCode(@RequestParam("warehouseCode") String warehouseCode);

    /**
     * 新增仓库信息
     *
     * @param mdmWarehouse 仓库实体
     * @return R
     */
    @PostMapping("/mdmWarehouse")
    R<Boolean> save(@RequestBody MdmWarehouseEntity mdmWarehouse);

    /**
     * 更新仓库信息
     *
     * @param mdmWarehouse 仓库实体
     * @return R
     */
    @PutMapping("/mdmWarehouse")
    R<Boolean> updateById(@RequestBody MdmWarehouseEntity mdmWarehouse);

    /**
     * 删除仓库信息
     *
     * @param ids 仓库 ID数组
     * @return R
     */
    @DeleteMapping("/mdmWarehouse")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 查询仓库详情
     *
     * @param id 仓库 ID
     * @return R
     */
    @GetMapping("/mdmWarehouse/{id}")
    R<MdmWarehouseEntity> getDetails(@PathVariable Long id);

}
