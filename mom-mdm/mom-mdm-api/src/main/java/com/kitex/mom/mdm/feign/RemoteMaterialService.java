package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteMaterialService", value = Constants.Name)
public interface RemoteMaterialService {

    /** 实名内部生产采集按实际工厂观察基本计量，精确时间使用文本，不调用匿名详情接口。 */
    @InternalCall(userTokenRequired = true)
    @GetMapping("/mdmMaterial/internal/production-measure")
    R<MdmMaterialMeasureDTO> productionMeasure(@RequestParam("materialCode") String materialCode);

    /**
     * 分页查询物料信息
     *
     * @param current     当前页码
     * @param size        每页大小
     * @param mdmMaterial 物料实体
     * @return R
     */
    @GetMapping("/mdmMaterial/page")
    R<Page<MdmMaterialEntity>> getMdmMaterialPage(@RequestParam("current") Integer current,
                                                  @RequestParam("size") Integer size, @SpringQueryMap MdmMaterialEntity mdmMaterial);

    /**
     * 获取物料列表
     *
     * @return R
     */
    @GetMapping("/mdmMaterial/list")
    R<List<MdmMaterialEntity>> getList();

    /**
     * 获取物料列表
     *
     * @return R
     */
    @NoToken
    @GetMapping("/mdmMaterial/allList")
    R<List<MdmMaterialEntity>> getAllList();

    /**
     * 根据物料编码列表查询物料列表
     *
     * @return R
     */
    @GetMapping("/mdmMaterial/listByCodes")
    R<List<MdmMaterialEntity>> getListByCodes(@RequestParam("codes") List<String> codes);

    /**
     * 新增物料信息
     *
     * @param mdmMaterial 物料实体
     * @return R
     */
    @PostMapping("/mdmMaterial")
    R<Boolean> save(@RequestBody MdmMaterialEntity mdmMaterial);

    /**
     * 更新物料信息
     *
     * @param mdmMaterial 物料实体
     * @return R
     */
    @PutMapping("/mdmMaterial")
    R<Boolean> updateById(@RequestBody MdmMaterialEntity mdmMaterial);

    /**
     * 删除物料信息
     *
     * @param ids 物料 ID数组
     * @return R
     */
    @DeleteMapping("/mdmMaterial")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 根据物料编码获取详情
     *
     * @param materialCode 物料编码
     * @return R
     */
    @GetMapping("/mdmMaterial/details")
    R<MdmMaterialEntity> getDetails(@RequestParam("materialCode") String materialCode);

    /**
     * 根据物料编码获取详情
     *
     * @param materialCode 物料编码
     * @return R
     */
    @NoToken
    @GetMapping("/mdmMaterial/detailsByNoToken")
    R<MdmMaterialEntity> detailsByNoToken(@RequestParam("materialCode") String materialCode);

    /**
     * 查询物料详情
     *
     * @param id 物料 ID
     * @return R
     */
    @GetMapping("/mdmMaterial/{id}")
    R<MdmMaterialEntity> getById(@PathVariable Long id);

}
