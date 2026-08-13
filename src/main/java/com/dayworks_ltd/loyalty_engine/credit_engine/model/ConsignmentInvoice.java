package com.dayworks_ltd.loyalty_engine.credit_engine.model;



import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "consignment_invoice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsignmentInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Sent to Bill Manager as externalReference — must equal Order.orderCode. */
    @Column(name = "external_reference", nullable = false, unique = true, length = 100)
    private String externalReference;


    @Column(name = "account_reference", nullable = false, length = 100)
    private String accountReference;

    @Column(name = "invoice_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal invoiceAmount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "bill_manager_status", nullable = false, length = 20)
    @Builder.Default
    private String billManagerStatus = "SENT"; // SENT, PAID, PARTIAL, OVERDUE, CANCELLED

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Full, unaltered Safaricom reconciliation payload — audit trail. */
    @Column(name = "raw_ack_payload", columnDefinition = "jsonb")
    private String rawAckPayload;
}
