package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(contextId = "remoteClassesService", value = Constants.Name)
public interface RemoteClassesService {

    /**
     * 分页查询班次信息
     *
     * @param current    当前页
     * @param size       每页大小
     * @param mdmClasses 班次实体
     * @return R
     */
    @GetMapping("/mdmClasses/page")
    R<Page<MdmClassesEntity>> getMdmClassesPage(@RequestParam("current") Integer current,
                                                @RequestParam("size") Integer size, @SpringQueryMap MdmClassesEntity mdmClasses);

    /**
     * 新增班次信息
     *
     * @param mdmClasses 班次实体
     * @return R
     */
    @PostMapping("/mdmClasses")
    R<Boolean> save(@RequestBody MdmClassesEntity mdmClasses);

    /**
     * 更新班次信息
     *
     * @param mdmClasses 班次实体
     * @return R
     */
    @PutMapping("/mdmClasses")
    R<Boolean> updateById(@RequestBody MdmClassesEntity mdmClasses);

    /**
     * 删除班次信息
     *
     * @param ids 班次 ID数组
     * @return R
     */
    @DeleteMapping("/mdmClasses")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 查询班次详情
     *
     * @param id 班次 ID
     * @return R
     */
    @GetMapping("/mdmClasses/{id}")
    R<MdmClassesEntity> getDetails(@PathVariable Long id);

    /**
     * 获取当前班次
     *
     * @return R
     */
    @GetMapping("/mdmClasses/getBanci")
    R<MdmClassesEntity> getBanci();

    /**
     * 获取带时间的班次
     *
     * @return R
     */
    @GetMapping("/mdmClasses/rule")
    R<String> getRuleBanci(@RequestParam("dateTime") LocalDateTime dateTime);


    /**
     * 获取传入时间对应的班次
     *
     * @return R
     */
    @GetMapping("/mdmClasses/getClassesBydate")
    R<List<MdmClassesEntity>> getClassesBydate(@RequestParam(value = "dateTime", required = false)
                                               @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
                                               @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss") String dateTime,
                                               @RequestParam(value = "number", required = false, defaultValue = "1") Integer number);

    /**
     * 获取传入时间对应的班次
     *
     * @return R
     */
    @GetMapping("/mdmClasses/getClassesByCode")
    R<List<MdmClassesEntity>> getClassesByCode(@RequestParam("classCode") String classCode,
                                               @RequestParam(value = "number", required = false, defaultValue = "1") Integer number);

    /**
     * 查询所有启用班次列表
     */
    @GetMapping("/mdmClasses/getUsableClasses")
    R<List<MdmClassesEntity>> listActive();

}
