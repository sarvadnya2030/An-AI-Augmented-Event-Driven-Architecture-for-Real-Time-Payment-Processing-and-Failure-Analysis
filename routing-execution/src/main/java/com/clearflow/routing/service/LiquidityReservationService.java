package com.clearflow.routing.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class LiquidityReservationService {

    private final JdbcTemplate jdbcTemplate;

    public LiquidityReservationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE, timeout = 5)
    public ReservationResult reserve(String currency, BigDecimal amount, String paymentId, String rail) {
        var rows = jdbcTemplate.queryForList(
                "SELECT account_id, available_balance, version FROM nostro_accounts WHERE currency = ? AND available_balance >= ? FETCH FIRST 1 ROWS ONLY FOR UPDATE",
                currency, amount
        );
        if (rows.isEmpty()) {
            throw new InsufficientLiquidityException(currency, amount);
        }

        Map<String, Object> account = rows.get(0);
        String accountId = String.valueOf(account.get("ACCOUNT_ID"));
        BigDecimal available = new BigDecimal(String.valueOf(account.get("AVAILABLE_BALANCE")));
        long version = Long.parseLong(String.valueOf(account.get("VERSION")));

        int updated = jdbcTemplate.update(
                "UPDATE nostro_accounts SET available_balance = available_balance - ?, reserved_balance = reserved_balance + ?, version = version + 1, last_updated = SYSTIMESTAMP WHERE account_id = ? AND version = ?",
                amount, amount, accountId, version
        );
        if (updated == 0) {
            throw new IllegalStateException("Optimistic update failed for account " + accountId);
        }

        String reservationId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO liquidity_reservations (reservation_id, payment_id, account_id, amount, currency, reserved_at, status) VALUES (?, ?, ?, ?, ?, SYSTIMESTAMP, 'RESERVED')",
                reservationId, paymentId, accountId, amount, currency
        );

        return new ReservationResult(accountId, reservationId, available, available.subtract(amount));
    }

    @Transactional
    public void release(String paymentId) {
        var rows = jdbcTemplate.queryForList("SELECT account_id, amount FROM liquidity_reservations WHERE payment_id = ? AND status = 'RESERVED'", paymentId);
        for (Map<String, Object> row : rows) {
            String accountId = String.valueOf(row.get("ACCOUNT_ID"));
            BigDecimal amount = new BigDecimal(String.valueOf(row.get("AMOUNT")));
            jdbcTemplate.update("UPDATE nostro_accounts SET available_balance = available_balance + ?, reserved_balance = reserved_balance - ? WHERE account_id = ?", amount, amount, accountId);
            jdbcTemplate.update("UPDATE liquidity_reservations SET status = 'RELEASED', released_at = SYSTIMESTAMP WHERE payment_id = ?", paymentId);
        }
    }

    public record ReservationResult(String accountId, String reservationId, BigDecimal nostroBalance, BigDecimal availableAfter) {}
}
