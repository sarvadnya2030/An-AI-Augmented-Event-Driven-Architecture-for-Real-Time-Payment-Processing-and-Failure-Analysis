package com.clearflow.settlement.service;

import com.clearflow.common.security.MaskedIbanSerializer;
import com.clearflow.settlement.domain.EntryType;
import com.clearflow.settlement.domain.LedgerEntry;
import com.clearflow.settlement.domain.SettlementRecord;
import com.clearflow.settlement.repository.LedgerRepository;
import com.clearflow.settlement.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.clearflow.settlement.analytics.ClickHouseAnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final LedgerRepository ledgerRepository;
    private final SettlementRepository settlementRepository;

    @Autowired(required = false)
    private ClickHouseAnalyticsService clickHouseAnalyticsService;

    public SettlementService(LedgerRepository ledgerRepository, SettlementRepository settlementRepository) {
        this.ledgerRepository = ledgerRepository;
        this.settlementRepository = settlementRepository;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public SettlementRecord settlePayment(Map<String, Object> event) {
        long start = System.currentTimeMillis();
        String paymentId = String.valueOf(event.get("paymentId"));
        SettlementRecord existing = settlementRepository.findByPaymentId(paymentId).orElse(null);
        if (existing != null) {
            return existing;
        }

        BigDecimal amount = new BigDecimal(String.valueOf(event.getOrDefault("amount", "0")));
        String currency = String.valueOf(event.getOrDefault("currency", "EUR"));
        String correlationId = String.valueOf(event.getOrDefault("correlationId", ""));

        LedgerEntry debit = new LedgerEntry();
        debit.setPaymentId(paymentId);
        debit.setAccountId(String.valueOf(event.getOrDefault("debtorAccountId", "DEBTOR-ACCOUNT")));
        debit.setEntryType(EntryType.DEBIT);
        debit.setAmount(amount);
        debit.setCurrency(currency);
        debit.setValueDate(LocalDate.now());
        debit.setReference(String.valueOf(event.getOrDefault("endToEndId", paymentId)));
        debit.setCorrelationId(correlationId);
        ledgerRepository.save(debit);

        LedgerEntry credit = new LedgerEntry();
        credit.setPaymentId(paymentId);
        credit.setAccountId(String.valueOf(event.getOrDefault("creditorAccountId", "CREDITOR-ACCOUNT")));
        credit.setEntryType(EntryType.CREDIT);
        credit.setAmount(amount);
        credit.setCurrency(currency);
        credit.setValueDate(LocalDate.now());
        credit.setReference(String.valueOf(event.getOrDefault("endToEndId", paymentId)));
        credit.setCorrelationId(correlationId);
        ledgerRepository.save(credit);

        BigDecimal totalDebits = ledgerRepository.sumByPaymentIdAndType(paymentId, EntryType.DEBIT);
        BigDecimal totalCredits = ledgerRepository.sumByPaymentIdAndType(paymentId, EntryType.CREDIT);
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new AccountingImbalanceException(paymentId, totalDebits, totalCredits);
        }

        SettlementRecord record = new SettlementRecord();
        record.setPaymentId(paymentId);
        record.setRail(String.valueOf(event.getOrDefault("railSelected", "SWIFT_MT103")));
        record.setStatus("SETTLED");
        record.setSettledAt(Instant.now());
        record.setDebtorIbanMasked(MaskedIbanSerializer.mask(String.valueOf(event.getOrDefault("debtorIban", ""))));
        record.setCreditorIbanMasked(MaskedIbanSerializer.mask(String.valueOf(event.getOrDefault("creditorIban", ""))));
        record.setAmount(amount);
        record.setCurrency(currency);
        record.setCorrelationId(correlationId);
        SettlementRecord saved = settlementRepository.save(record);
        if (clickHouseAnalyticsService != null) {
            clickHouseAnalyticsService.record(saved);
        }

        long durationMs = System.currentTimeMillis() - start;
        MDC.put("paymentId", paymentId);
        MDC.put("rail", record.getRail());
        MDC.put("currency", currency);
        MDC.put("durationMs", String.valueOf(durationMs));
        try {
            log.info("SETTLEMENT_COMPLETE paymentId={} rail={} amount={} currency={} settlementRef={} durationMs={}",
                    paymentId, record.getRail(), amount, currency, saved.getId(), durationMs);
        } finally {
            MDC.remove("rail");
            MDC.remove("currency");
            MDC.remove("durationMs");
        }
        return saved;
    }

    public List<LedgerEntry> ledgerEntries(String paymentId) {
        return ledgerRepository.findByPaymentId(paymentId);
    }
}
