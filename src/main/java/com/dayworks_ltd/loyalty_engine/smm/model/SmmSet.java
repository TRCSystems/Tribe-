package com.dayworks_ltd.loyalty_engine.smm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Items sold together at one price: a full outfit, a 3-in-1 suitcase set. */
@Entity
@Table(name = "smm_set", indexes = @Index(name = "idx_smm_set_merchant", columnList = "merchant_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmmSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 150)
    private String name;

    /** Catalogue item codes in the set, comma separated. */
    @Column(name = "item_codes", nullable = false, length = 1000)
    private String itemCodes;

    @Column(name = "set_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal setPrice;

    /** Items a buyer may add or leave out, comma separated. */
    @Column(name = "optional_item_codes", length = 1000)
    private String optionalItemCodes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public List<String> items() {
        return split(itemCodes);
    }

    public List<String> optionalItems() {
        return split(optionalItemCodes);
    }

    private static List<String> split(String codes) {
        return codes == null || codes.isBlank() ? List.of()
                : Arrays.stream(codes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
