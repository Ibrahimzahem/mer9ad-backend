package com.hackathon.ra9edhamad.aml;

import java.util.List;

/**
 * Feature vector for the AML classifier. The feature order MUST match the Python
 * training script exactly ({@code FEATURE_COLS} in {@code train_aml_model.py}).
 * The schema is loaded from {@code feature_schema.json} at startup to verify.
 *
 * <p>Features are computed per-transaction from the account's history, capturing
 * laundering signals: structuring (many sub-threshold transfers), rapid transit
 * (fan-in/fan-out), round-tripping, and currency mismatch.
 */
public final class AmlFeatures {

    /** Common SAR reporting threshold (USD). Transfers below this flag structuring. */
    public static final double SAR_THRESHOLD = 10_000.0;

    /** The canonical feature order — must match the Python training script. */
    public static final List<String> FEATURE_NAMES = List.of(
            "amount_paid",
            "below_threshold",
            "amount_to_avg_ratio",
            "txn_count",
            "total_sent",
            "avg_sent",
            "std_sent",
            "min_sent",
            "max_sent",
            "unique_beneficiaries",
            "active_days",
            "avg_daily_txns",
            "fan_out_ratio",
            "payment_formats_used",
            "recv_count",
            "total_received",
            "avg_received",
            "std_received",
            "unique_senders",
            "fan_in_ratio",
            "receiver_also_sends",
            "currency_mismatch"
    );

    public static final int FEATURE_COUNT = FEATURE_NAMES.size();

    private AmlFeatures() {
    }

    /**
     * Compute the feature vector for one transfer, given the sender's and receiver's
     * transaction history. Returns a float array of length {@link #FEATURE_COUNT}.
     *
     * @param amount           the current transfer amount
     * @param senderHistory    the sender's recent outgoing transactions
     * @param receiverHistory  the receiver's recent incoming transactions
     * @param receiverAlsoSends true if the receiving account also sends (pass-through signal)
     * @param currencyMismatch true if payment currency != receiving currency
     */
    public static float[] compute(
            double amount,
            List<HistoryEntry> senderHistory,
            List<HistoryEntry> receiverHistory,
            boolean receiverAlsoSends,
            boolean currencyMismatch
    ) {
        float[] f = new float[FEATURE_COUNT];

        // --- Sender stats (from outgoing history) ---
        int txnCount = senderHistory.size();
        double totalSent = 0, minSent = Double.MAX_VALUE, maxSent = 0;
        double sumSq = 0;
        var beneficiaries = new java.util.HashSet<String>();
        var activeDays = new java.util.HashSet<String>();
        var formats = new java.util.HashSet<String>();

        for (HistoryEntry e : senderHistory) {
            totalSent += e.amount;
            minSent = Math.min(minSent, e.amount);
            maxSent = Math.max(maxSent, e.amount);
            sumSq += e.amount * e.amount;
            if (e.counterpartyIban != null) beneficiaries.add(e.counterpartyIban);
            if (e.date != null) activeDays.add(e.date);
            if (e.paymentFormat != null) formats.add(e.paymentFormat);
        }
        if (txnCount == 0) minSent = 0;
        double avgSent = txnCount > 0 ? totalSent / txnCount : 0;
        double stdSent = txnCount > 0 ? Math.sqrt(Math.max(0, sumSq / txnCount - avgSent * avgSent)) : 0;
        int activeDayCount = Math.max(1, activeDays.size());
        double avgDailyTxns = (double) txnCount / activeDayCount;
        double fanOutRatio = txnCount > 0 ? (double) beneficiaries.size() / txnCount : 0;

        // --- Receiver stats (from incoming history) ---
        int recvCount = receiverHistory.size();
        double totalReceived = 0, minRecv = Double.MAX_VALUE, maxRecv = 0;
        double sumSqRecv = 0;
        var senders = new java.util.HashSet<String>();

        for (HistoryEntry e : receiverHistory) {
            totalReceived += e.amount;
            minRecv = Math.min(minRecv, e.amount);
            maxRecv = Math.max(maxRecv, e.amount);
            sumSqRecv += e.amount * e.amount;
            if (e.counterpartyIban != null) senders.add(e.counterpartyIban);
        }
        if (recvCount == 0) minRecv = 0;
        double avgReceived = recvCount > 0 ? totalReceived / recvCount : 0;
        double stdReceived = recvCount > 0 ? Math.sqrt(Math.max(0, sumSqRecv / recvCount - avgReceived * avgReceived)) : 0;
        double fanInRatio = recvCount > 0 ? (double) senders.size() / recvCount : 0;

        // --- Per-transaction features ---
        double amountToAvgRatio = avgSent > 0.01 ? amount / avgSent : 0;

        // --- Pack into the canonical order ---
        f[0]  = (float) amount;
        f[1]  = amount < SAR_THRESHOLD ? 1f : 0f;
        f[2]  = (float) amountToAvgRatio;
        f[3]  = txnCount;
        f[4]  = (float) totalSent;
        f[5]  = (float) avgSent;
        f[6]  = (float) stdSent;
        f[7]  = (float) minSent;
        f[8]  = (float) maxSent;
        f[9]  = beneficiaries.size();
        f[10] = activeDayCount;
        f[11] = (float) avgDailyTxns;
        f[12] = (float) fanOutRatio;
        f[13] = formats.size();
        f[14] = recvCount;
        f[15] = (float) totalReceived;
        f[16] = (float) avgReceived;
        f[17] = (float) stdReceived;
        f[18] = senders.size();
        f[19] = (float) fanInRatio;
        f[20] = receiverAlsoSends ? 1f : 0f;
        f[21] = currencyMismatch ? 1f : 0f;

        return f;
    }

    /** One entry in the transaction history used for feature computation. */
    public record HistoryEntry(
            String date,
            String counterpartyIban,
            double amount,
            String paymentFormat
    ) {
    }
}
