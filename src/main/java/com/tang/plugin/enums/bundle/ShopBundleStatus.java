package com.tang.plugin.enums.bundle;

/**
 * Local lifecycle for a fixed product bundle managed by this app.
 */
public enum ShopBundleStatus {
    /** productBundleCreate accepted; polling productOperation. */
    CREATING,
    /** Shopify operation complete; parent product ready. */
    ACTIVE,
    /** Operation or post-create update failed. */
    FAILED,
    /** Parent/components changed externally; needs re-sync. */
    STALE,
    /** Soft-dissolved locally (Shopify parent may still exist). */
    DISSOLVED
}
