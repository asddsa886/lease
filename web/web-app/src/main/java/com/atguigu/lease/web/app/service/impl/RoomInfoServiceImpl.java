package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.cache.HotDataCacheHelper;
import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.FacilityInfo;
import com.atguigu.lease.model.entity.LabelInfo;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.model.enums.ItemType;
import com.atguigu.lease.web.app.mapper.ApartmentInfoMapper;
import com.atguigu.lease.web.app.mapper.AttrValueMapper;
import com.atguigu.lease.web.app.mapper.FacilityInfoMapper;
import com.atguigu.lease.web.app.mapper.GraphInfoMapper;
import com.atguigu.lease.web.app.mapper.LabelInfoMapper;
import com.atguigu.lease.web.app.mapper.LeaseTermMapper;
import com.atguigu.lease.web.app.mapper.PaymentTypeMapper;
import com.atguigu.lease.web.app.mapper.RoomInfoMapper;
import com.atguigu.lease.web.app.service.BrowsingHistoryService;
import com.atguigu.lease.web.app.service.RoomFavoriteService;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.vo.apartment.ApartmentItemVo;
import com.atguigu.lease.web.app.vo.attr.AttrValueVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.atguigu.lease.web.app.vo.room.RoomDetailVo;
import com.atguigu.lease.web.app.vo.room.RoomItemVo;
import com.atguigu.lease.web.app.vo.room.RoomQueryVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @author liubo
 * @description 针对表【room_info(房间信息表)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
@Slf4j
public class RoomInfoServiceImpl extends ServiceImpl<RoomInfoMapper, RoomInfo>
        implements RoomInfoService {

    @Autowired
    private RoomInfoMapper roomInfoMapper;

    @Autowired
    private ApartmentInfoMapper apartmentInfoMapper;

    @Autowired
    private GraphInfoMapper graphInfoMapper;

    @Autowired
    private AttrValueMapper attrValueMapper;

    @Autowired
    private FacilityInfoMapper facilityInfoMapper;

    @Autowired
    private LabelInfoMapper labelInfoMapper;

    @Autowired
    private PaymentTypeMapper paymentTypeMapper;

    @Autowired
    private LeaseTermMapper leaseTermMapper;

    @Autowired
    private BrowsingHistoryService browsingHistoryService;

    @Autowired
    private RoomFavoriteService roomFavoriteService;

    @Autowired
    private HotDataCacheHelper hotDataCacheHelper;

    @Override
    public IPage<RoomItemVo> pageItem(Page<RoomItemVo> page, RoomQueryVo queryVo) {
        IPage<RoomItemVo> result = roomInfoMapper.pageItem(page, queryVo);
        fillFavoriteStatus(result.getRecords());
        return result;
    }

    @Override
    public RoomDetailVo getDetailById(Long id) {
        if (id == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        String cacheKey = RedisConstant.APP_ROOM_DETAIL_KEY_PREFIX + id;
        RoomDetailVo roomDetailVo = hotDataCacheHelper.getOrLoadWithLock(
                cacheKey,
                new TypeReference<RoomDetailVo>() {
                },
                () -> loadRoomDetailOrNull(id)
        );
        if (roomDetailVo == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }

        RoomDetailVo responseVo = copyRoomDetail(roomDetailVo);
        fillFavoriteStatus(responseVo);

        // User-side behavior like browsing history should stay outside the cached payload.
        if (LoginUserHolder.get() != null) {
            browsingHistoryService.saveHistory(LoginUserHolder.get().getId(), id);
        }
        return responseVo;
    }

    private RoomDetailVo loadRoomDetailOrNull(Long id) {
        RoomInfo roomInfo = roomInfoMapper.selectById(id);
        if (roomInfo == null) {
            return null;
        }

        // Keep the aggregate consistent: if the parent apartment is gone, treat the detail as missing.
        ApartmentInfo apartmentInfo = apartmentInfoMapper.selectById(roomInfo.getApartmentId());
        if (apartmentInfo == null) {
            return null;
        }

        List<GraphVo> graphVoA = graphInfoMapper.selectListByItemTypeAndId(ItemType.APARTMENT, roomInfo.getApartmentId());
        List<LabelInfo> labelInfoA = labelInfoMapper.selectListByApartmentId(roomInfo.getApartmentId());
        BigDecimal minA = roomInfoMapper.selectMinRentByApartmentId(roomInfo.getApartmentId());

        ApartmentItemVo apartmentItemVo = new ApartmentItemVo();
        BeanUtils.copyProperties(apartmentInfo, apartmentItemVo);
        apartmentItemVo.setLabelInfoList(labelInfoA);
        apartmentItemVo.setGraphVoList(graphVoA);
        apartmentItemVo.setMinRent(minA);
        log.debug("Assembled apartmentItemVo for roomDetail, roomId={}, apartmentId={}", id, roomInfo.getApartmentId());

        List<GraphVo> graphVoList = graphInfoMapper.selectListByItemTypeAndId(ItemType.ROOM, id);
        List<AttrValueVo> attrValueVos = attrValueMapper.selectListByRoomId(id);
        List<FacilityInfo> facilityInfos = facilityInfoMapper.selectListByRoomId(id);
        List<LabelInfo> labelInfoList = labelInfoMapper.selectListByRoomId(id);
        List<PaymentType> paymentTypeList = paymentTypeMapper.selectListByRoomId(id);
        List<LeaseTerm> leaseTermList = leaseTermMapper.selectListByRoomId(id);

        RoomDetailVo roomDetailVo = new RoomDetailVo();
        BeanUtils.copyProperties(roomInfo, roomDetailVo);
        roomDetailVo.setApartmentItemVo(apartmentItemVo);
        roomDetailVo.setGraphVoList(graphVoList);
        roomDetailVo.setAttrValueVoList(attrValueVos);
        roomDetailVo.setFacilityInfoList(facilityInfos);
        roomDetailVo.setLabelInfoList(labelInfoList);
        roomDetailVo.setPaymentTypeList(paymentTypeList);
        roomDetailVo.setLeaseTermList(leaseTermList);
        return roomDetailVo;
    }

    @Override
    public IPage<RoomItemVo> pageItemByApartmentId(Page<RoomItemVo> page, Long id) {
        IPage<RoomItemVo> result = roomInfoMapper.pageItemByApartmentId(page, id);
        fillFavoriteStatus(result.getRecords());
        return result;
    }

    private void fillFavoriteStatus(List<RoomItemVo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        LoginUser currentUser = LoginUserHolder.get();
        if (currentUser == null || currentUser.getId() == null) {
            records.forEach(record -> record.setIsFavorite(false));
            return;
        }

        List<Long> roomIds = records.stream()
                .map(RoomItemVo::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<Long> favoriteRoomIds = roomFavoriteService.favoriteRoomIds(currentUser.getId(), roomIds);
        records.forEach(record -> record.setIsFavorite(favoriteRoomIds.contains(record.getId())));
    }

    private void fillFavoriteStatus(RoomDetailVo roomDetailVo) {
        LoginUser currentUser = LoginUserHolder.get();
        boolean favorite = currentUser != null
                && currentUser.getId() != null
                && roomFavoriteService.isFavorite(currentUser.getId(), roomDetailVo.getId());
        roomDetailVo.setIsFavorite(favorite);
    }

    private RoomDetailVo copyRoomDetail(RoomDetailVo source) {
        RoomDetailVo copy = new RoomDetailVo();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }
}
