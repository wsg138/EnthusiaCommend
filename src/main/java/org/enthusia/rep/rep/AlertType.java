package org.enthusia.rep.rep;

/**
 * Categorises the reason for a suspicious-rep alert shown to staff.
 */
public enum AlertType {
    ALT_ABUSE,          // Multiple accounts on the same IP downrepping a target
    RECIPROCITY,        // A and B exchanged rep within a short window (rep trading)
    CLUSTER_DOWNREP     // 3+ different players downrepped the same target within 6h
}
