package com.shangzhili.electricityreminder;

public final class Reading {
    public final double surplus;
    public final double amount;
    public final long timestamp;

    public Reading(double surplus, double amount, long timestamp) {
        this.surplus = surplus;
        this.amount = amount;
        this.timestamp = timestamp;
    }
}
