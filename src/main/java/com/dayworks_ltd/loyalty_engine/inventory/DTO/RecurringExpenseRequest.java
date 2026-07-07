package com.dayworks_ltd.loyalty_engine.inventory.DTO;
import com.dayworks_ltd.loyalty_engine.common.Frequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecurringExpenseRequest {

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String narration;

    @NotNull
    private Frequency frequency;

    @NotNull
    private LocalDate startDate;

    private LocalDate endDate;
}