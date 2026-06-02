package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.FacilityInfo;
import com.atguigu.lease.model.entity.LabelInfo;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.web.app.service.RoomCompareService;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.vo.apartment.ApartmentItemVo;
import com.atguigu.lease.web.app.vo.compare.RoomCompareItemVo;
import com.atguigu.lease.web.app.vo.compare.RoomCompareVo;
import com.atguigu.lease.web.app.vo.room.RoomDetailVo;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

@Service
public class RoomCompareServiceImpl implements RoomCompareService {

    private static final int MAX_COMPARE_ROOMS = 6;

    private final RoomInfoService roomInfoService;

    public RoomCompareServiceImpl(RoomInfoService roomInfoService) {
        this.roomInfoService = roomInfoService;
    }

    @Override
    public RoomCompareVo compareRooms(List<Long> roomIds) {
        List<Long> distinctRoomIds = normalizeRoomIds(roomIds);
        List<RoomCompareItemVo> items = distinctRoomIds.stream()
                .map(roomInfoService::getDetailById)
                .map(this::toCompareItem)
                .toList();

        RoomCompareVo result = new RoomCompareVo();
        result.setItems(items);
        result.setDifferenceFields(resolveDifferenceFields(items));
        return result;
    }

    private List<Long> normalizeRoomIds(List<Long> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        List<Long> distinctRoomIds = roomIds.stream()
                .filter(Objects::nonNull)
                .collect(LinkedHashSet<Long>::new, Set::add, Set::addAll)
                .stream()
                .toList();
        if (distinctRoomIds.isEmpty() || distinctRoomIds.size() > MAX_COMPARE_ROOMS) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        return distinctRoomIds;
    }

    private RoomCompareItemVo toCompareItem(RoomDetailVo detailVo) {
        ApartmentItemVo apartment = detailVo.getApartmentItemVo();
        RoomCompareItemVo item = new RoomCompareItemVo();
        item.setRoomId(detailVo.getId());
        item.setRoomNumber(detailVo.getRoomNumber());
        item.setRent(detailVo.getRent());
        item.setIsFavorite(Boolean.TRUE.equals(detailVo.getIsFavorite()));
        if (apartment != null) {
            item.setApartmentId(apartment.getId());
            item.setApartmentName(apartment.getName());
            item.setProvinceName(apartment.getProvinceName());
            item.setCityName(apartment.getCityName());
            item.setDistrictName(apartment.getDistrictName());
            item.setAddressDetail(apartment.getAddressDetail());
        }
        item.setGraphVoList(detailVo.getGraphVoList());
        item.setAttrValueVoList(detailVo.getAttrValueVoList());
        item.setFacilityInfoList(detailVo.getFacilityInfoList());
        item.setLabelInfoList(detailVo.getLabelInfoList());
        item.setPaymentTypeList(detailVo.getPaymentTypeList());
        item.setLeaseTermList(detailVo.getLeaseTermList());
        return item;
    }

    private List<String> resolveDifferenceFields(List<RoomCompareItemVo> items) {
        return Stream.of(
                        difference("roomNumber", items, RoomCompareItemVo::getRoomNumber),
                        difference("rent", items, item -> item.getRent() == null ? null : item.getRent().stripTrailingZeros().toPlainString()),
                        difference("apartment", items, RoomCompareItemVo::getApartmentId),
                        difference("labels", items, item -> values(item.getLabelInfoList(), LabelInfo::getName)),
                        difference("facilities", items, item -> values(item.getFacilityInfoList(), FacilityInfo::getName)),
                        difference("paymentTypes", items, item -> values(item.getPaymentTypeList(), PaymentType::getName)),
                        difference("leaseTerms", items, item -> values(item.getLeaseTermList(), LeaseTerm::getMonthCount))
                )
                .filter(Objects::nonNull)
                .toList();
    }

    private <T> String difference(String field, List<RoomCompareItemVo> items, Function<RoomCompareItemVo, T> extractor) {
        long distinct = items.stream()
                .map(extractor)
                .distinct()
                .count();
        return distinct > 1 ? field : null;
    }

    private <T, R> List<R> values(List<T> values, Function<T, R> extractor) {
        return values == null ? List.of() : values.stream().map(extractor).toList();
    }
}
