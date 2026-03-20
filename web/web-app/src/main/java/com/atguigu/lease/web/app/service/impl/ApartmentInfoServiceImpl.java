package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.FacilityInfo;
import com.atguigu.lease.model.entity.LabelInfo;
import com.atguigu.lease.model.enums.ItemType;
import com.atguigu.lease.web.app.mapper.*;
import com.atguigu.lease.web.app.service.ApartmentInfoService;
import com.atguigu.lease.web.app.vo.apartment.ApartmentDetailVo;
import com.atguigu.lease.web.app.vo.apartment.ApartmentItemVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author liubo
 * @description 针对表【apartment_info(公寓信息表)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
public class ApartmentInfoServiceImpl extends ServiceImpl<ApartmentInfoMapper, ApartmentInfo>
        implements ApartmentInfoService {

    @Autowired
    private ApartmentInfoMapper apartmentInfoMapper;

    @Autowired
    private GraphInfoMapper graphInfoMapper;

    @Autowired
    private LabelInfoMapper labelInfoMapper;

    @Autowired
    private FacilityInfoMapper facilityInfoMapper;

    @Autowired
    private RoomInfoMapper roomInfoMapper;


    @Override
    public ApartmentDetailVo getDetailById(Long id) {
        if (id == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        ApartmentInfo apartmentInfo = apartmentInfoMapper.selectById(id);
        if (apartmentInfo == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }

        List<GraphVo> graphVos = graphInfoMapper.selectListByItemTypeAndId(ItemType.APARTMENT, id);
        List<LabelInfo> labelInfos = labelInfoMapper.selectListByApartmentId(id);
        List<FacilityInfo> facilityInfos = facilityInfoMapper.selectListByApartmentId(id);
        BigDecimal min = roomInfoMapper.selectMinRentByApartmentId(id);

        ApartmentDetailVo apartmentDetailVo = new ApartmentDetailVo();
        BeanUtils.copyProperties(apartmentInfo, apartmentDetailVo);
        apartmentDetailVo.setFacilityInfoList(facilityInfos);
        apartmentDetailVo.setLabelInfoList(labelInfos);
        apartmentDetailVo.setGraphVoList(graphVos);
        apartmentDetailVo.setMinRent(min);

        return apartmentDetailVo;
    }

    @Override
    public ApartmentItemVo getItemById(Long id) {
        if (id == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        ApartmentInfo apartmentInfo = apartmentInfoMapper.selectById(id);
        if (apartmentInfo == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }

        List<GraphVo> graphVos = graphInfoMapper.selectListByItemTypeAndId(ItemType.APARTMENT, id);
        List<LabelInfo> labelInfos = labelInfoMapper.selectListByApartmentId(id);
        List<FacilityInfo> facilityInfos = facilityInfoMapper.selectListByApartmentId(id);
        BigDecimal min = roomInfoMapper.selectMinRentByApartmentId(id);

        ApartmentItemVo apartmentItemVo = new ApartmentItemVo();
        BeanUtils.copyProperties(apartmentInfo, apartmentItemVo);
        apartmentItemVo.setLabelInfoList(labelInfos);
        apartmentItemVo.setGraphVoList(graphVos);
        apartmentItemVo.setMinRent(min);

        return apartmentItemVo;
    }
}




