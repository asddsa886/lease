package com.atguigu.lease.web.app.controller.region;

import com.atguigu.lease.common.cache.HotDataCacheHelper;
import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.model.entity.CityInfo;
import com.atguigu.lease.model.entity.DistrictInfo;
import com.atguigu.lease.model.entity.ProvinceInfo;
import com.atguigu.lease.web.app.service.CityInfoService;
import com.atguigu.lease.web.app.service.DistrictInfoService;
import com.atguigu.lease.web.app.service.ProvinceInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "地区信息")
@RestController
@RequestMapping("/app/region")
@Validated
public class RegionController {

    @Autowired
    private ProvinceInfoService provinceInfoService;

    @Autowired
    private DistrictInfoService districtInfoService;

    @Autowired
    private CityInfoService cityInfoService;

    @Autowired
    private HotDataCacheHelper hotDataCacheHelper;

    @Operation(summary = "查询省份信息列表")
    @GetMapping("province/list")
    public Result<List<ProvinceInfo>> listProvince() {
        String key = RedisConstant.APP_REGION_PROVINCE_LIST_KEY;
        List<ProvinceInfo> list = hotDataCacheHelper.getOrLoadWithLock(
                key,
                new TypeReference<List<ProvinceInfo>>() {
                },
                provinceInfoService::list
        );
        return Result.ok(list);
    }

    @Operation(summary = "根据省份id查询城市信息列表")
    @GetMapping("city/listByProvinceId")
    public Result<List<CityInfo>> listCityInfoByProvinceId(@RequestParam @NotNull(message = "id不能为空")
                                                           @Min(value = 1, message = "id必须>=1") Long id) {
        String key = RedisConstant.APP_REGION_CITY_LIST_BY_PROVINCE_KEY_PREFIX + id;
        List<CityInfo> list = hotDataCacheHelper.getOrLoadWithLock(
                key,
                new TypeReference<List<CityInfo>>() {
                },
                () -> {
                    LambdaQueryWrapper<CityInfo> queryWrapper = new LambdaQueryWrapper<>();
                    queryWrapper.eq(CityInfo::getProvinceId, id);
                    return cityInfoService.list(queryWrapper);
                }
        );
        return Result.ok(list);
    }

    @GetMapping("district/listByCityId")
    @Operation(summary = "根据城市id查询区县信息")
    public Result<List<DistrictInfo>> listDistrictInfoByCityId(@RequestParam @NotNull(message = "id不能为空")
                                                               @Min(value = 1, message = "id必须>=1") Long id) {
        String key = RedisConstant.APP_REGION_DISTRICT_LIST_BY_CITY_KEY_PREFIX + id;
        List<DistrictInfo> list = hotDataCacheHelper.getOrLoadWithLock(
                key,
                new TypeReference<List<DistrictInfo>>() {
                },
                () -> {
                    LambdaQueryWrapper<DistrictInfo> queryWrapper = new LambdaQueryWrapper<>();
                    queryWrapper.eq(DistrictInfo::getCityId, id);
                    return districtInfoService.list(queryWrapper);
                }
        );
        return Result.ok(list);
    }
}
