package com.clearflow.fraud.service;

import com.clearflow.fraud.domain.FraudRequest;
import com.clearflow.fraud.domain.FraudResponse;
import com.clearflow.fraud.domain.RiskBand;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

@Service
public class HeuristicScoringService {

    private final CountryRiskMatrix countryRiskMatrix;
    private final VelocityCheckService velocityCheckService;

    public HeuristicScoringService(CountryRiskMatrix countryRiskMatrix, VelocityCheckService velocityCheckService) {
        this.countryRiskMatrix = countryRiskMatrix;
        this.velocityCheckService = velocityCheckService;
    }

    public FraudResponse heuristicScore(FraudRequest request, long startedAt) {
        double score = 0.0d;
        boolean crossBorder = !request.debtorCountry().equalsIgnoreCase(request.creditorCountry());
        long velocity1h = velocityCheckService.countLast1h(request.debtorIban());
        boolean firstPair = velocityCheckService.isFirstTimePair(request.debtorIban(), request.creditorIban());

        if (request.amount().compareTo(BigDecimal.valueOf(500000)) > 0) score += 0.2d;
        if (request.amount().compareTo(BigDecimal.valueOf(1000000)) > 0) score += 0.1d;
        if (countryRiskMatrix.getRisk(request.creditorCountry()) >= 8) score += 0.3d;
        if (countryRiskMatrix.getRisk(request.debtorCountry()) >= 8) score += 0.2d;
        if (velocity1h > 5) score += 0.15d;
        if (velocity1h > 10) score += 0.1d;
        if (firstPair && request.amount().compareTo(BigDecimal.valueOf(10000)) > 0) score += 0.1d;
        if ("SWIFT_GPI".equalsIgnoreCase(request.channel()) && crossBorder) score += 0.05d;

        double bounded = Math.min(score, 0.99d);
        BigDecimal finalScore = BigDecimal.valueOf(bounded).setScale(4, RoundingMode.HALF_UP);

        return new FraudResponse(
                request.paymentId(),
                finalScore,
                toRiskBand(finalScore),
                Map.of("heuristic", 1.0d),
                "heuristic-v1",
                Instant.now(),
                System.currentTimeMillis() - startedAt,
                true
        );
    }

    public RiskBand toRiskBand(BigDecimal score) {
        if (score.compareTo(BigDecimal.valueOf(0.3d)) < 0) return RiskBand.LOW;
        if (score.compareTo(BigDecimal.valueOf(0.6d)) < 0) return RiskBand.MEDIUM;
        if (score.compareTo(BigDecimal.valueOf(0.85d)) < 0) return RiskBand.HIGH;
        return RiskBand.CRITICAL;
    }
}
