package com.dayworks_ltd.loyalty_engine.inventory.models;


import com.dayworks_ltd.loyalty_engine.common.Frequency;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "recurring_expenses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String merchantId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;   // DAILY, WEEKLY, MONTHLY

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;     // Optional

    private LocalDate nextExecutionDate;

    private LocalDate  expenseDate;

    private boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public void updateNextExecutionDate() {
        if (!isActive || nextExecutionDate == null) return;

        LocalDate next = switch (frequency) {
            case DAILY -> nextExecutionDate.plusDays(1);
            case WEEKLY -> nextExecutionDate.plusWeeks(1);
            case MONTHLY -> nextExecutionDate.plusMonths(1);
        };

        // Respect endDate
        if (endDate != null && next.isAfter(endDate)) {
            this.isActive = false;
        } else {
            this.nextExecutionDate = next;
        }
    }
}