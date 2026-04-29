package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.entity.MdmBomEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(contextId = "remoteBomService", value = Constants.Name)
public interface RemoteBomService {

    /**
     * 分页查询BOM信息
     *
     * @param current 当前页
     * @param size    每页大小
     * @param mdmBom  BOM实体
     * @return R
     */
    @GetMapping("/mdmBom/page")
    R<Page<MdmBomEntity>> getMdmBomPage(@RequestParam("current") Integer current, @RequestParam("size") Integer size,
                                        @SpringQueryMap MdmBomEntity mdmBom);

    /**
     * 新增BOM信息
     *
     * @param mdmBom BOM实体
     * @return R
     */
    @PostMapping("/mdmBom")
    R<Boolean> save(@RequestBody MdmBomEntity mdmBom);

    /**
     * 更新BOM信息
     *
     * @param mdmBom BOM实体
     * @return R
     */
    @PutMapping("/mdmBom")
    R<Boolean> updateById(@RequestBody MdmBomEntity mdmBom);

    /**
     * 删除BOM信息
     *
     * @param ids BOM ID数组
     * @return R
     */
    @DeleteMapping("/mdmBom")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 查询BOM详情
     *
     * @param id BOM ID
     * @return R
     */
    @GetMapping("/mdmBom/{id}")
    R<MdmBomEntity> getDetails(@PathVariable Long id);

    /**
     * 根据产品编码获取该产品所有子物料bom信息
     *
     * @param productCode 产品编码
     * @return 子物料集合, 按层次区分
     */
    @GetMapping("/mdmBom/childMaterials")
    R<List<MdmBomChildDTO>> getChildMaterials(@RequestParam("productCode") String productCode,
                                              @RequestParam("bomId") Long bomId,
                                              @RequestParam(value = "version", required = false) String version);

    /**
     * 根据产品编码列表获取该产品所有子物料bom信息包括自己
     *
     * @param productCodes 产品编码列表
     * @return 子物料集合, 按层次区分
     */
    @GetMapping("/mdmBom/allMaterials")
    R<Map<String, MdmBomChildDTO>> getAllChildMaterials(@RequestParam("productCodes") List<String> productCodes);

}
