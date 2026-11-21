package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@FeignClient(contextId = "remoteClassesService", value = Constants.Name)
public interface RemoteClassesService {

    DateTimeFormatter RULE_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

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
     * 按指定时刻获取带日期的班次。服务端的查询格式要求保留秒。
     *
     * @param dateTime 业务发生时间
     * @return 对应日期的班次编码
     */
    default R<String> getRuleBanci(LocalDateTime dateTime) {
        return getRuleBanciAt(dateTime == null ? null : RULE_DATE_TIME_FORMAT.format(dateTime));
    }

    /**
     * 按 MDM 班次接口接受的年月日、时分秒格式传输查询时间。
     *
     * @param dateTime 已格式化的业务发生时间
     * @return 对应日期的班次编码
     */
    @GetMapping("/mdmClasses/rule")
    R<String> getRuleBanciAt(@RequestParam("dateTime") String dateTime);


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

    /** 后台中检按原班次查询，使用调用服务的实际工厂上下文。 */
    @InternalCall
    @GetMapping("/mdmClasses/internal/patrol-sequence")
    R<List<MdmClassesEntity>> getPatrolSequence(@RequestParam("classCode") String classCode,
                                               @RequestParam("number") int number);

    /** 后台生成按班次编码的检验批次时，查询工厂对应时刻的班次。 */
    @InternalCall
    @GetMapping("/mdmClasses/internal/patrol-shift")
    R<String> getPatrolShift(@RequestParam("dateTime") String dateTime);

    /**
     * 查询所有启用班次列表
     */
    @GetMapping("/mdmClasses/getUsableClasses")
    R<List<MdmClassesEntity>> listActive();

}
