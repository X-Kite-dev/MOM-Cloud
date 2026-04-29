package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteMdmProcedureService", value = Constants.Name)
public interface RemoteMdmProcedureService {

    /**
     * 分页查询工序信息
     *
     * @param current             当前页码
     * @param size                每页大小
     * @param mdmWorkingProcedure 工序实体
     * @return R
     */
    @GetMapping("/mdmProcedure/page")
    R<Page<MdmProcedureEntity>> getMdmWorkingProcedurePage(@RequestParam("current") Integer current,
                                                           @RequestParam("size") Integer size, @SpringQueryMap MdmProcedureEntity mdmWorkingProcedure);

    /**
     * 获取工序列表
     *
     * @return R
     */
    @GetMapping("/mdmProcedure/list")
    R<List<MdmProcedureEntity>> getList();

    /**
     * 新增工序信息
     *
     * @param mdmWorkingProcedure 工序实体
     * @return R
     */
    @PostMapping("/mdmProcedure")
    R<Boolean> save(@RequestBody MdmProcedureEntity mdmWorkingProcedure);

    /**
     * 更新工序信息
     *
     * @param mdmWorkingProcedure 工序实体
     * @return R
     */
    @PutMapping("/mdmProcedure")
    R<Boolean> updateById(@RequestBody MdmProcedureEntity mdmWorkingProcedure);

    /**
     * 删除工序信息
     *
     * @param ids 工序 ID数组
     * @return R
     */
    @DeleteMapping("/mdmProcedure")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 查询工序详情
     *
     * @param id 工序 ID
     * @return R
     */
    @GetMapping("/mdmProcedure/{id}")
    R<MdmProcedureEntity> getDetails(@PathVariable Long id);

}