package com.dayworks_ltd.loyalty_engine.crm.models;

/** Small enums shared by the CRM. */
public final class CrmEnums {

    private CrmEnums() {}

    public enum Channel { SMS, WHATSAPP }

    public enum MessageStatus {
        SENT,
        FAILED,
        /** Not sent on purpose — see skipReason (opted out, frequency cap, holdout group, quiet hours, no phone). */
        SKIPPED
    }
}
