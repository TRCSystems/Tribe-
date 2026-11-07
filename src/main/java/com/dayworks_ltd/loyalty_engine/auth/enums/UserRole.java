package com.dayworks_ltd.loyalty_engine.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dayworks_ltd.loyalty_engine.auth.enums.UserPermission.*;

@RequiredArgsConstructor
public enum UserRole {
    ADMIN(
            Set.of(
                    LEAD_WRITE,
                    LEAD_READ,

                    USER_WRITE,
                    USER_READ,
                    USER_UPDATE,

                    INVENTORY_GET_ALL,
                    INVENTORY_IMPORT,
                    INVENTORY_SALE,
                    INVENTORY_WRITE_DEDUCTION,
                    INVENTORY_ADD_STOCK,
                    INVENTORY_RECORD_EXPENSE,
                    INVENTORY_CLOSE_DAY,
                    INVENTORY_DAILY_SUMMARY,
                    INVENTORY_WEEKLY_SUMMARY,
                    INVENTORY_REPORT,

                    CAMPAIGN_CREATE,
                    CAMPAIGN_UPDATE,
                    CAMPAIGN_GET_ALL,
                    CAMPAIGN_GET_BY_ID,
                    CAMPAIGN_DELETE,

                    MERCHANT_CREATE,
                    MERCHANT_UPDATE,
                    MERCHANT_GET_ALL,
                    MERCHANT_GET_BY_ID,
                    MERCHANT_GET_BY_TILL_NUMBER,
                    MERCHANT_DELETE
            )
    ),
    LEAD_COLLECTOR(
            Set.of(
                    LEAD_WRITE,
                    LEAD_READ
            )
    ),
    MERCHANT(
            Set.of(
                    MERCHANT_UPDATE,
                    MERCHANT_GET_BY_ID,

                    INVENTORY_GET_ALL,
                    INVENTORY_IMPORT,
                    INVENTORY_SALE,
                    INVENTORY_WRITE_DEDUCTION,
                    INVENTORY_ADD_STOCK,
                    INVENTORY_RECORD_EXPENSE,
                    INVENTORY_CLOSE_DAY,
                    INVENTORY_DAILY_SUMMARY,
                    INVENTORY_WEEKLY_SUMMARY,
                    INVENTORY_REPORT,

                    CAMPAIGN_CREATE,
                    CAMPAIGN_UPDATE,
                    CAMPAIGN_GET_ALL,
                    CAMPAIGN_GET_BY_ID,
                    CAMPAIGN_DELETE
            )
    ),
    SALES_PERSON(
            Set.of(
                    MERCHANT_GET_BY_ID,

                    INVENTORY_GET_ALL,
                    INVENTORY_IMPORT,
                    INVENTORY_SALE,
                    INVENTORY_WRITE_DEDUCTION,
                    INVENTORY_ADD_STOCK,
                    INVENTORY_RECORD_EXPENSE,
                    INVENTORY_CLOSE_DAY,
                    INVENTORY_DAILY_SUMMARY,
                    INVENTORY_WEEKLY_SUMMARY,
                    INVENTORY_REPORT,

                    CAMPAIGN_CREATE,
                    CAMPAIGN_UPDATE,
                    CAMPAIGN_GET_ALL,
                    CAMPAIGN_GET_BY_ID,
                    CAMPAIGN_DELETE
            )
    ),
    /** Distributor's field sales rep (SFA app): places orders for assigned shops, sees the CRM call list. */
    FIELD_REP(
            Set.of(
                    MERCHANT_GET_BY_ID,
                    INVENTORY_GET_ALL
            )
    );

    @Getter
    private final Set<UserPermission> userPermissions;


    public List<SimpleGrantedAuthority> getAuthorities()
    {
        var authorities = getUserPermissions()
                .stream()
                .map( permission -> new SimpleGrantedAuthority( permission.getUserPermission() ))
                .collect(Collectors.toList());

        authorities.add( new SimpleGrantedAuthority("ROLE_" + this.name()) );

        return authorities;
    }
}
