package com.dayworks_ltd.loyalty_engine.inventory.services;

import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.SaleTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Sales and margin over a period (month to date on the reconciliation page) and the end-of-month
 * summary SMS to the owner: how much was sold, how much was made, and the stock the month closed with.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesSummaryService {

    /** Sales and margin over a date range. Margin covers the lines with a recorded cost. */
    public record Summary(LocalDate from, LocalDate to, int sales, BigDecimal revenue,
                          BigDecimal margin, BigDecimal marginPct, int linesWithoutCost) {}

    private final SaleTransactionRepository saleTransactionRepository;
    private final StockCountQueueService queueService;
    private final ReportRecipientService recipients;

    public Summary summary(String merchantId, LocalDate from, LocalDate to) {
        Object[] r = saleTransactionRepository.salesSummary(merchantId, from, to).stream().findFirst()
                .orElse(new Object[]{0, 0, 0, 0, 0});
        BigDecimal revenue = big(r[1]);
        BigDecimal costedRevenue = big(r[2]);
        BigDecimal margin = big(r[3]);
        BigDecimal pct = costedRevenue.signum() == 0 ? null
                : margin.multiply(BigDecimal.valueOf(100)).divide(costedRevenue, 1, RoundingMode.HALF_UP);
        return new Summary(from, to, ((Number) r[0]).intValue(), revenue.setScale(2, RoundingMode.HALF_UP),
                margin.setScale(2, RoundingMode.HALF_UP), pct, ((Number) r[4]).intValue());
    }

    /** From the 1st of the month up to (and including) the given day. */
    public Summary monthToDate(String merchantId, LocalDate upTo) {
        return summary(merchantId, upTo.withDayOfMonth(1), upTo);
    }

    /** Current stock value at cost (what the month closes with when run at month end). */
    public BigDecimal closingStockValue(String merchantId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Inventory i : queueService.currentStockByItemCode(merchantId).values()) {
            if (Boolean.FALSE.equals(i.getIsActive())) continue;
            int qty = i.getAvailableStock() == null ? 0 : Math.max(0, i.getAvailableStock());
            BigDecimal cost = i.getUnitCost() == null ? BigDecimal.ZERO : i.getUnitCost();
            total = total.add(cost.multiply(BigDecimal.valueOf(qty)));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Builds and sends the month summary SMS for one merchant. Returns the message sent. */
    public String sendMonthlySummary(String merchantId, YearMonth month) {
        Summary s = summary(merchantId, month.atDay(1), month.atEndOfMonth());
        Summary prev = summary(merchantId, month.minusMonths(1).atDay(1), month.minusMonths(1).atEndOfMonth());
        String msg = monthlyMessage(recipients.shopName(merchantId), month, s, prev, closingStockValue(merchantId));
        recipients.send(merchantId, ReportRecipientService.ReportType.SUMMARY, msg);
        return msg;
    }

    static String monthlyMessage(String shop, YearMonth month, Summary s, Summary prev, BigDecimal closingStock) {
        String monthName = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
        StringBuilder sb = new StringBuilder("TRIBE ").append(clean(shop)).append(": ").append(monthName).append(" summary. ");
        sb.append("Sold KES ").append(kes(s.revenue())).append(" (").append(s.sales()).append(" sales");
        if (prev != null && prev.revenue().signum() > 0) {
            int change = s.revenue().subtract(prev.revenue()).multiply(BigDecimal.valueOf(100))
                    .divide(prev.revenue(), 0, RoundingMode.HALF_UP).intValue();
            sb.append(", ").append(change >= 0 ? "+" : "").append(change).append("% vs last month");
        }
        sb.append("). Made KES ").append(kes(s.margin()));
        if (s.marginPct() != null) sb.append(" (").append(s.marginPct()).append("% margin)");
        if (s.linesWithoutCost() > 0) sb.append(" excl. ").append(s.linesWithoutCost()).append(" items with no cost");
        sb.append(". Closing stock KES ").append(kes(closingStock)).append(" at cost.");
        return sb.toString();
    }

    private static String kes(BigDecimal v) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.ENGLISH);
        return f.format(v == null ? 0 : v.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\\\\"\\r\\n\\t]", " ").replaceAll(" {2,}", " ").trim();
    }

    private static BigDecimal big(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
