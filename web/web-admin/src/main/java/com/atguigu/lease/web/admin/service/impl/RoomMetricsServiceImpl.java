package com.atguigu.lease.web.admin.service.impl;

import com.atguigu.lease.web.admin.mapper.RoomMetricsMapper;
import com.atguigu.lease.web.admin.service.RoomMetricsService;
import com.atguigu.lease.web.admin.vo.metrics.RoomFunnelVo;
import com.atguigu.lease.web.admin.vo.metrics.RoomQualityScoreVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class RoomMetricsServiceImpl implements RoomMetricsService {

    private final RoomMetricsMapper roomMetricsMapper;

    public RoomMetricsServiceImpl(RoomMetricsMapper roomMetricsMapper) {
        this.roomMetricsMapper = roomMetricsMapper;
    }

    @Override
    public IPage<RoomQualityScoreVo> pageQualityScore(Page<RoomQualityScoreVo> page, Long apartmentId) {
        IPage<RoomQualityScoreVo> result = roomMetricsMapper.pageQualityScore(page, apartmentId);
        result.getRecords().forEach(this::score);
        return result;
    }

    @Override
    public RoomFunnelVo getFunnel(LocalDate startDate, LocalDate endDate, Long apartmentId) {
        RoomFunnelVo funnel = roomMetricsMapper.selectFunnel(startDate, endDate, apartmentId);
        if (funnel == null) {
            funnel = new RoomFunnelVo();
        }
        normalizeCounts(funnel);
        funnel.setFavoriteRate(rate(funnel.getFavoriteCount(), funnel.getBrowseCount()));
        funnel.setAppointmentRate(rate(funnel.getAppointmentCount(), funnel.getBrowseCount()));
        funnel.setOrderRate(rate(funnel.getOrderCount(), funnel.getBrowseCount()));
        return funnel;
    }

    private void score(RoomQualityScoreVo item) {
        normalizeCounts(item);
        int contentScore = 0;
        contentScore += capped(item.getGraphCount(), 3, 15);
        contentScore += capped(item.getAttrCount(), 2, 10);
        contentScore += capped(item.getFacilityCount(), 5, 15);
        contentScore += capped(item.getLabelCount(), 2, 8);
        contentScore += item.getPaymentTypeCount() > 0 ? 6 : 0;
        contentScore += item.getLeaseTermCount() > 0 ? 6 : 0;

        int conversionScore = 0;
        conversionScore += capped(item.getBrowseCount(), 50, 15);
        conversionScore += capped(item.getFavoriteCount(), 10, 10);
        conversionScore += capped(item.getAppointmentCount(), 5, 8);
        conversionScore += capped(item.getOrderCount(), 3, 7);

        item.setContentScore(contentScore);
        item.setConversionScore(conversionScore);
        item.setQualityScore(Math.min(100, contentScore + conversionScore));
    }

    private int capped(Long value, long target, int maxScore) {
        long count = value == null ? 0L : Math.max(0L, value);
        if (target <= 0 || count <= 0) {
            return 0;
        }
        return (int) Math.min(maxScore, Math.round((double) count / target * maxScore));
    }

    private BigDecimal rate(Long numerator, Long denominator) {
        long den = denominator == null ? 0L : denominator;
        if (den <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        long num = numerator == null ? 0L : numerator;
        return BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 4, RoundingMode.HALF_UP);
    }

    private void normalizeCounts(RoomQualityScoreVo item) {
        item.setGraphCount(nonNull(item.getGraphCount()));
        item.setAttrCount(nonNull(item.getAttrCount()));
        item.setFacilityCount(nonNull(item.getFacilityCount()));
        item.setLabelCount(nonNull(item.getLabelCount()));
        item.setPaymentTypeCount(nonNull(item.getPaymentTypeCount()));
        item.setLeaseTermCount(nonNull(item.getLeaseTermCount()));
        item.setBrowseCount(nonNull(item.getBrowseCount()));
        item.setFavoriteCount(nonNull(item.getFavoriteCount()));
        item.setAppointmentCount(nonNull(item.getAppointmentCount()));
        item.setOrderCount(nonNull(item.getOrderCount()));
    }

    private void normalizeCounts(RoomFunnelVo item) {
        item.setBrowseCount(nonNull(item.getBrowseCount()));
        item.setFavoriteCount(nonNull(item.getFavoriteCount()));
        item.setAppointmentCount(nonNull(item.getAppointmentCount()));
        item.setOrderCount(nonNull(item.getOrderCount()));
    }

    private Long nonNull(Long value) {
        return value == null ? 0L : value;
    }
}
