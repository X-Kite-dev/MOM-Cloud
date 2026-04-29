package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmMaterialGroupEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteMaterialGroupService", value = Constants.Name)
public interface RemoteMaterialGroupService {

    /**
     * 分页查询物料分组信息
     *
     * @param page             分页对象
     * @param mdmMaterialGroup 物料分组表
     * @return R
     */
    @GetMapping("/mdmMaterialGroup/page")
    R<Page<MdmMaterialGroupEntity>> getMdmMaterialGroupPage(@RequestParam("current") Integer current,
                                                            @RequestParam("size") Integer size, @SpringQueryMap MdmMaterialGroupEntity mdmMaterialGroup);

    /**
     * 获取物料分组树形数据
     *
     * @param parentId  父节点ID
     * @param groupCode 分组编码
     * @param groupName 分组名称
     * @param enabled   是否启用
     * @return R
     */
    @GetMapping("/mdmMaterialGroup/tree")
    R<List<MdmMaterialGroupEntity>> getMdmMaterialGroupTree(@RequestParam Long parentId, @RequestParam String groupCode,
                                                            @RequestParam String groupName, @RequestParam Boolean enabled);

    /**
     * 通过ID查询物料分组
     *
     * @param id ID
     * @return R
     */
    @GetMapping("/mdmMaterialGroup/{id}")
    R<MdmMaterialGroupEntity> getById(@PathVariable("id") Long id);

    /**
     * 根据物料分组编码获取详情
     *
     * @param groupCode 物料分组编码
     * @return R
     */
    @GetMapping("/mdmMaterialGroup/details")
    R<MdmMaterialGroupEntity> details(@RequestParam String groupCode);

    /**
     * 新增物料分组
     *
     * @param mdmMaterialGroup 物料分组表
     * @return R
     */
    @PostMapping("/mdmMaterialGroup")
    R<Boolean> save(@RequestBody MdmMaterialGroupEntity mdmMaterialGroup);

    /**
     * 修改物料分组
     *
     * @param mdmMaterialGroup 物料分组表
     * @return R
     */
    @PutMapping("/mdmMaterialGroup")
    R<Boolean> updateById(@RequestBody MdmMaterialGroupEntity mdmMaterialGroup);

    /**
     * 通过ID删除物料分组
     *
     * @param ids ID数组
     * @return R
     */
    @DeleteMapping("/mdmMaterialGroup")
    R<Boolean> removeById(@RequestBody Long[] ids);

}
