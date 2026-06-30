package com.dayworks_ltd.loyalty_engine.merchants;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(
        name = "merchants",
        indexes = {
                @Index(name = "idx_businessPhone", columnList = "businessPhone")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_phone", nullable = false)
    private String businessPhone = "default phone number for existing merchants";

    @Column(name = "merchant_otp")//may be null
    private String merchantOtp = "";

    @NotBlank
    @Column(name = "business_name", nullable = false)
    private String businessName;



    @NotBlank
    @Column(name = "location", nullable = false)
    private String location;


    @Pattern(regexp = "\\d{5,10}", message = "Invalid till number format")
    @Column(name = "till_number", unique = true)
    private String tillNumber;

    @NotBlank
    @Column(name = "business_type", nullable = false)
    private String businessType;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;


    @Column(name = "meta_connected")
    private Boolean metaConnected = false;

    @Column(name = "facebook_page_id")
    private String facebookPageId;

    @Column(name = "facebook_page_token", columnDefinition = "TEXT")
    private String facebookPageToken;

    @Column(name = "facebook_user_token", columnDefinition = "TEXT")
    private String facebookUserToken;

    @Column(name = "instagram_business_account_id")
    private String instagramBusinessAccountId;

    @Column(name = "lastActive")
    private LocalDateTime lastActiveAt;

    @Column(name = "meta_catalog_id")
    private String metaCatalogId;

    @Column(name = "meta_commerce_merchant_settings_id")
    private String metaCommerceMerchantSettingsId;

    @Column(name = "meta_token_expires_at")
    private LocalDateTime metaTokenExpiresAt;

    @Column(name = "meta_sync_enabled")
    private Boolean metaSyncEnabled = true;

    @Column(name = "meta_last_sync_at")
    private LocalDateTime metaLastSyncAt;

    @Column(name = "meta_sync_error", columnDefinition = "TEXT")
    private String metaSyncError;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Merchant distributor;




    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

}
